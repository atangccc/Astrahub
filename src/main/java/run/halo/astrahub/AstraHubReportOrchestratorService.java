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
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);
    private static final Duration MIN_RETRY_BACKOFF = Duration.ofSeconds(5);

    private final ReactiveSettingFetcher settingFetcher;
    private final AstraHubGraphTransformService graphTransformService;
    private final AstraHubPushService pushService;

    private final Scheduler reportScheduler = Schedulers.newSingle("astrahub-report-loop");
    private final AtomicBoolean schedulerActive = new AtomicBoolean(false);
    private final AtomicBoolean pushing = new AtomicBoolean(false);
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
        return readSyncPolicy().flatMap(policy ->
            runPush("auto", null, policy).map(result -> {
                if (result.success()) {
                    return policy.interval();
                }
                if (result.status() >= 500 || result.status() == 429) {
                    return policy.retryBackoff();
                }
                return policy.interval();
            })
        );
    }

    private Mono<AstraHubPushService.PushResult> runPush(String trigger) {
        return runPush(trigger, null, null);
    }

    private Mono<AstraHubPushService.PushResult> runPush(String trigger, ServerRequest request) {
        return runPush(trigger, request, null);
    }

    private Mono<AstraHubPushService.PushResult> runPush(String trigger, ServerRequest request, SyncPolicy policy) {
        if (!pushing.compareAndSet(false, true)) {
            AstraHubPushService.PushResult busy =
                AstraHubPushService.PushResult.failed(429, "push already in progress");
            publish(HubPhase.BUSY, trigger, false, false, busy.status(), busy.message(), busy.pushedAt(), nextRunAt.get());
            return Mono.just(busy);
        }

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
            ))
            .doFinally(signalType -> pushing.set(false));
    }

    private Mono<SyncPolicy> readSyncPolicy() {
        return readSetting("sync")
            .map(sync -> {
                int intervalMinutes = Math.max(1, readInt(sync, "intervalMinutes", 60));
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
