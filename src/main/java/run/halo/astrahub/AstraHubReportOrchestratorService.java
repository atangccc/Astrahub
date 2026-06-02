package run.halo.astrahub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.astrahub.util.HubPhase;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubReportOrchestratorService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(6);
    private static final int DEFAULT_INTERVAL_MINUTES = (int) DEFAULT_INTERVAL.toMinutes();
    private static final Duration MIN_RETRY_BACKOFF = Duration.ofSeconds(5);
    // 单次推送的硬上限：底层是阻塞 HttpClient（连接 10s + 请求 15s），这里给整条链兜底，
    // 任何卡死到点都会被强制终止，触发资源释放，绝不依赖重启来恢复。
    private static final Duration PUSH_HARD_TIMEOUT = Duration.ofSeconds(60);

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubGraphTransformService graphTransformService;
    private final AstraHubPushService pushService;

    private final Scheduler reportScheduler = Schedulers.newSingle("astrahub-report-loop");
    private final AtomicBoolean schedulerActive = new AtomicBoolean(false);
    // 当前正在执行的推送任务句柄。它的"非空且未 disposed"直接等价于"正在推送"，
    // 不再用独立布尔标志（旧实现里 AtomicBoolean pushing 会因异常路径或未订阅而永久泄漏，
    // 导致此后所有推送 429 且只能重启）。句柄是自校正的：跑完/取消/为空都精确表示空闲。
    private final AtomicReference<Disposable> currentPushTask = new AtomicReference<>();
    private final AtomicBoolean linkEstablished = new AtomicBoolean(false);
    private final AtomicReference<Disposable> currentLoopTask = new AtomicReference<>();
    private final AtomicReference<String> nextRunAt = new AtomicReference<>("");
    private final AtomicReference<String> lastSuccessfulPushAt = new AtomicReference<>("");
    private final AtomicLong sequence = new AtomicLong(0);
    private final Sinks.Many<ReportStatusEvent> statusSink = Sinks.many().multicast().directBestEffort();
    private final AtomicReference<ReportStatusEvent> currentStatus = new AtomicReference<>(
        new ReportStatusEvent(
            0,
            HubPhase.INIT,
            "system",
            false,
            false,
            0,
            "scheduler not started",
            "",
            "",
            nowIso(),
            false,
            ""
        )
    );

    public void startScheduler() {
        if (!schedulerActive.compareAndSet(false, true)) {
            return;
        }
        publish(HubPhase.IDLE, "system", false, true, 200, "scheduler started", "", "");
        scheduleNext(Duration.ZERO);
    }

    public void stopScheduler() {
        if (!schedulerActive.compareAndSet(true, false)) {
            return;
        }
        Disposable loopTask = currentLoopTask.getAndSet(null);
        if (loopTask != null && !loopTask.isDisposed()) {
            loopTask.dispose();
        }
        nextRunAt.set("");
        publish(HubPhase.STOPPED, "system", false, true, 200, "scheduler stopped", "", "");
    }

    @PreDestroy
    public void destroy() {
        stopScheduler();
        reportScheduler.dispose();
    }

    public Mono<AstraHubPushService.PushResult> triggerManualPush() {
        return runPush("manual", null);
    }

    public Mono<AstraHubPushService.PushResult> triggerManualPush(ServerRequest request) {
        return runPush("manual", request);
    }

    public Flux<ReportStatusEvent> statusStream() {
        return Flux.defer(() -> Flux.concat(Mono.just(currentStatus.get()), statusSink.asFlux()));
    }

    public ReportStatusEvent currentStatus() {
        return currentStatus.get();
    }

    public RuntimeStatus runtimeStatus() {
        ReportStatusEvent status = currentStatus.get();
        return new RuntimeStatus(
            linkEstablished.get(),
            lastSuccessfulPushAt.get(),
            status.phase(),
            status.status(),
            status.message(),
            status.nextRunAt(),
            status.updatedAt()
        );
    }

    private void scheduleNext(Duration delay) {
        if (!schedulerActive.get()) {
            return;
        }
        Duration safeDelay = sanitizeDelay(delay, DEFAULT_INTERVAL);
        String plannedAt = nowPlus(safeDelay);
        nextRunAt.set(plannedAt);
        publish(HubPhase.IDLE, "auto", false, true, 200, "next auto push scheduled", "", plannedAt);

        Disposable nextTask = Mono.delay(safeDelay, reportScheduler)
            .flatMap(ignored -> runAutoCycle())
            .onErrorResume(error -> {
                log.warn("[AstraHub] auto report cycle failed", error);
                return Mono.just(DEFAULT_INTERVAL);
            })
            .subscribe(this::scheduleNext);

        Disposable oldTask = currentLoopTask.getAndSet(nextTask);
        if (oldTask != null && !oldTask.isDisposed()) {
            oldTask.dispose();
        }
    }

    private Mono<Duration> runAutoCycle() {
        return readSyncPolicy().flatMap(policy -> {
            // 自动推送是最低优先级：若已有推送在跑（手动或上一轮自动），直接跳过本轮，
            // 绝不取消别人、也绝不抢占。等下个周期再来。
            Disposable active = currentPushTask.get();
            if (active != null && !active.isDisposed()) {
                publish(HubPhase.BUSY, "auto", false, true, 200,
                    "another push in progress, auto cycle skipped", "", nextRunAt.get());
                return Mono.just(policy.interval());
            }
            return runPush("auto", null, policy).map(result -> {
                if (result.success()) {
                    return policy.interval();
                }
                if (result.status() >= 500 || result.status() == 429) {
                    return policy.retryBackoff();
                }
                return policy.interval();
            });
        });
    }

    private Mono<AstraHubPushService.PushResult> runPush(String trigger) {
        return runPush(trigger, null, null);
    }

    private Mono<AstraHubPushService.PushResult> runPush(String trigger, ServerRequest request) {
        return runPush(trigger, request, null);
    }

    // 推送统一入口。
    //
    // 优先级与并发语义（彻底根治 429 卡死的核心）：
    //   - 手动（manual）：最高优先级，永不返回 429。进入时先取消当前正在跑的任何推送，
    //     再立即接管。用户点一次就立刻推最新数据。
    //   - 自动（auto）：runAutoCycle 已在外层判定"有活任务就跳过"，到这里默认是空闲的；
    //     即便竞态下被手动抢占，executePush 的 using 也保证资源正确释放。
    //
    // 资源安全：用 Mono.using 把"占位/释放"绑死在订阅生命周期内，无论正常完成、出错、
    //   超时还是被取消，释放逻辑都成对执行；再叠加 PUSH_HARD_TIMEOUT 兜底，任何卡死都自愈。
    private Mono<AstraHubPushService.PushResult> runPush(String trigger, ServerRequest request, SyncPolicy policy) {
        boolean manual = "manual".equalsIgnoreCase(trigger);
        return Mono.defer(() -> {
            if (manual) {
                // 手动抢占：取消正在跑的推送（自动或上一次手动），让位给本次。
                Disposable previous = currentPushTask.getAndSet(null);
                if (previous != null && !previous.isDisposed()) {
                    previous.dispose();
                }
            }
            return executePush(trigger, request, policy);
        });
    }

    private Mono<AstraHubPushService.PushResult> executePush(
        String trigger, ServerRequest request, SyncPolicy policy) {
        // slot 既是"占位令牌"也是可被外部 dispose 的句柄：
        //   - 资源获取期：把它注册到 currentPushTask，对外表示"正在推送"
        //   - 资源释放期：仅当 currentPushTask 仍指向自己时清空（避免误清后来的手动任务）
        Sinks.Empty<Void> cancelSignal = Sinks.empty();
        return Mono.usingWhen(
            Mono.fromSupplier(() -> {
                Disposable token = cancelSignal::tryEmitEmpty;
                currentPushTask.set(token);
                return token;
            }),
            token -> buildAndPush(trigger, request, policy)
                .timeout(PUSH_HARD_TIMEOUT,
                    Mono.fromSupplier(() -> {
                        AstraHubPushService.PushResult timeout =
                            AstraHubPushService.PushResult.failed(504, "push timed out");
                        publish(HubPhase.ERROR, trigger, false, false, timeout.status(),
                            timeout.message(), timeout.pushedAt(), nextRunAt.get());
                        return timeout;
                    }))
                // 被手动抢占（token 触发 cancelSignal）时，取消当前推送并返回让位结果。
                .takeUntilOther(cancelSignal.asMono())
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    AstraHubPushService.PushResult superseded =
                        AstraHubPushService.PushResult.failed(409, "push superseded by a newer push");
                    return superseded;
                })),
            token -> Mono.fromRunnable(() -> currentPushTask.compareAndSet(token, null)),
            (token, error) -> Mono.fromRunnable(() -> currentPushTask.compareAndSet(token, null)),
            token -> Mono.fromRunnable(() -> currentPushTask.compareAndSet(token, null))
        );
    }

    private Mono<AstraHubPushService.PushResult> buildAndPush(
        String trigger, ServerRequest request, SyncPolicy policy) {
        String incrementalSince = resolveIncrementalSince(trigger, request, policy);
        boolean incremental = !incrementalSince.isBlank();
        String syncReason = resolveSyncReason(trigger, request);
        String startMessage = incremental
            ? "incremental push in progress"
            : "push in progress";
        publish(HubPhase.RUNNING, trigger, true, true, 0, startMessage, "", nextRunAt.get());
        if (policy != null && policy.debugLoggingEnabled()) {
            log.info("[AstraHub] {} push start trigger={} since={}",
                incremental ? "incremental" : "full",
                trigger,
                incrementalSince);
        }
        Mono<AstraHubGraphTransformService.GraphPayload> payloadMono = incremental
            ? (request == null
                ? graphTransformService.buildBpGraphV1PayloadSince(incrementalSince, syncReason)
                : graphTransformService.buildBpGraphV1PayloadSince(incrementalSince, request, syncReason))
            : (request == null
                ? graphTransformService.buildBpGraphV1Payload(syncReason)
                : graphTransformService.buildBpGraphV1Payload(request, syncReason));
        return payloadMono
            .flatMap(pushService::pushGraph)
            .onErrorResume(error -> {
                log.warn("[AstraHub] push execution failed", error);
                return Mono.just(AstraHubPushService.PushResult.failed(500, "push failed: " + error.getMessage()));
            })
            .doOnNext(result -> {
                if (policy != null && policy.debugLoggingEnabled()) {
                    log.info("[AstraHub] {} push finished trigger={} status={} success={} message={}",
                        incremental ? "incremental" : "full",
                        trigger,
                        result.status(),
                        result.success(),
                        result.message());
                }
            })
            .doOnNext(result -> publish(
                result.success() ? HubPhase.SUCCESS : HubPhase.ERROR,
                trigger,
                false,
                result.success(),
                result.status(),
                result.message(),
                result.pushedAt(),
                nextRunAt.get()
            ));
    }

    private Mono<SyncPolicy> readSyncPolicy() {
        return readSetting("sync")
            .map(sync -> {
                int intervalMinutes = Math.max(1, readInt(sync, "intervalMinutes", DEFAULT_INTERVAL_MINUTES));
                int retryBackoffSeconds = Math.max((int) MIN_RETRY_BACKOFF.getSeconds(),
                    readInt(sync, "retryBackoffSeconds", 30));
                boolean incrementalEnabled = readBoolean(sync, "incrementalEnabled", false);
                boolean debugLoggingEnabled = readBoolean(sync, "debugLoggingEnabled", false);
                return new SyncPolicy(
                    incrementalEnabled,
                    debugLoggingEnabled,
                    Duration.ofMinutes(intervalMinutes),
                    Duration.ofSeconds(retryBackoffSeconds)
                );
            });
    }

    private String resolveSyncReason(String trigger, ServerRequest request) {
        if (request != null) {
            String fromRequest = request.queryParam("reason").map(String::trim).orElse("");
            if (!fromRequest.isBlank()) {
                return fromRequest;
            }
        }
        return trigger == null ? "" : trigger.trim();
    }

    private Mono<JsonNode> readSetting(String key) {
        return settingFetcher.getSettingValue(key)
            .flatMap(value -> Mono.fromCallable(() -> toJsonNode(value)))
            .onErrorResume(error -> Mono.empty())
            .switchIfEmpty(Mono.fromSupplier(MAPPER::createObjectNode));
    }

    private static JsonNode toJsonNode(Object value) throws Exception {
        if (value == null) {
            return MAPPER.createObjectNode();
        }
        if (value instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        String raw = value.toString();
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(raw);
    }

    private void publish(
        String phase,
        String trigger,
        boolean running,
        boolean success,
        int status,
        String message,
        String pushedAt,
        String nextAt
    ) {
        updateLinkState(phase, status, message, pushedAt);
        String safePushedAt = resolvePushedAt(pushedAt);
        ReportStatusEvent event = new ReportStatusEvent(
            sequence.incrementAndGet(),
            safe(phase),
            safe(trigger),
            running,
            success,
            status,
            safe(message),
            safePushedAt,
            safe(nextAt),
            nowIso(),
            linkEstablished.get(),
            safe(lastSuccessfulPushAt.get())
        );
        currentStatus.set(event);
        statusSink.tryEmitNext(event);
    }

    private void updateLinkState(String phase, int status, String message, String pushedAt) {
        String safePhase = safe(phase);
        String safeMessage = safe(message).toLowerCase();
        if (HubPhase.SUCCESS.equalsIgnoreCase(safePhase)) {
            linkEstablished.set(true);
            if (!safe(pushedAt).isBlank()) {
                lastSuccessfulPushAt.set(safe(pushedAt));
            }
            return;
        }
        if (HubPhase.STOPPED.equalsIgnoreCase(safePhase)) {
            linkEstablished.set(false);
            return;
        }
        if (HubPhase.ERROR.equalsIgnoreCase(safePhase) && isTerminalDisconnect(status, safeMessage)) {
            linkEstablished.set(false);
        }
    }

    private String resolvePushedAt(String pushedAt) {
        String value = safe(pushedAt);
        if (!value.isBlank()) {
            return value;
        }
        return safe(lastSuccessfulPushAt.get());
    }

    private boolean isTerminalDisconnect(int status, String message) {
        if (status == 403 && message.contains("site is not active")) {
            return true;
        }
        return message.contains("invalid api key")
            || message.contains("invalid signature")
            || message.contains("site is not active")
            || message.contains("site not found");
    }

    private static int readInt(JsonNode node, String field, int fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asInt(fallback);
    }

    private static boolean readBoolean(JsonNode node, String field, boolean fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        return value.asBoolean(fallback);
    }

    private static Duration sanitizeDelay(Duration delay, Duration fallback) {
        if (delay == null || delay.isNegative()) {
            return fallback;
        }
        if (delay.isZero()) {
            return Duration.ofSeconds(1);
        }
        return delay;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String resolveIncrementalSince(String trigger, ServerRequest request, SyncPolicy policy) {
        if (request != null) {
            String since = trim(request.queryParam("since").orElse(""));
            if (!since.isBlank()) {
                return since;
            }
            String mode = trim(request.queryParam("mode").orElse(""));
            if ("incremental".equalsIgnoreCase(mode)) {
                return safe(lastSuccessfulPushAt.get());
            }
        }
        if (!"auto".equalsIgnoreCase(trigger) || policy == null || !policy.incrementalEnabled()) {
            return "";
        }
        return safe(lastSuccessfulPushAt.get());
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private static String nowPlus(Duration delay) {
        return OffsetDateTime.now(ZoneOffset.UTC)
            .plusNanos(delay.toNanos())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private record SyncPolicy(
        boolean incrementalEnabled,
        boolean debugLoggingEnabled,
        Duration interval,
        Duration retryBackoff
    ) {
    }

    public record ReportStatusEvent(
        long sequence,
        String phase,
        String trigger,
        boolean running,
        boolean success,
        int status,
        String message,
        String pushedAt,
        String nextRunAt,
        String updatedAt,
        boolean linked,
        String lastSuccessfulPushAt
    ) {
    }

    public record RuntimeStatus(
        boolean connected,
        String lastSuccessfulPushAt,
        String phase,
        int status,
        String message,
        String nextRunAt,
        String updatedAt
    ) {
    }
}
