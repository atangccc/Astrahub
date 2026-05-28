package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubLinkChangeMonitorService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration STARTUP_DELAY = Duration.ofSeconds(5);
    private static final Duration OBSERVE_INTERVAL = Duration.ofSeconds(15);

    private final AstraHubLinkEdgeExportService exportService;
    private final AstraHubLinkEdgePushService pushService;

    private final Scheduler scheduler = Schedulers.newSingle("astrahub-link-change-monitor");
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean observing = new AtomicBoolean(false);
    private final AtomicReference<Disposable> nextTask = new AtomicReference<>();
    private final AtomicReference<String> lastPushedFingerprint = new AtomicReference<>("");

    public void start() {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        scheduleNext(STARTUP_DELAY, "startup");
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        Disposable current = nextTask.getAndSet(null);
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
    }

    public void requestImmediateObserve(String trigger) {
        if (!active.get()) {
            return;
        }
        scheduleNext(Duration.ZERO, trigger);
    }

    public Mono<ObserveResult> observeNow(String trigger) {
        return observeCycle(trigger);
    }

    private void scheduleNext(Duration delay, String trigger) {
        if (!active.get()) {
            return;
        }
        Disposable next = Mono.delay(delay, scheduler)
            .flatMap(ignored -> observeCycle(trigger))
            .onErrorResume(error -> {
                log.warn("[AstraHub] link change observe failed", error);
                return Mono.just(ObserveResult.failed(trigger, "observe failed: " + error.getMessage()));
            })
            .subscribe(result -> {
                if (!result.success()) {
                    log.warn("[AstraHub] link change observe not successful, trigger={}, message={}", result.trigger(), result.message());
                }
                if (active.get()) {
                    scheduleNext(OBSERVE_INTERVAL, "interval");
                }
            });

        Disposable old = nextTask.getAndSet(next);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
    }

    private Mono<ObserveResult> observeCycle(String trigger) {
        if (!observing.compareAndSet(false, true)) {
            return Mono.just(ObserveResult.skipped(trigger, "observe already running"));
        }

        final String normalizedTrigger = trim(trigger).isEmpty() ? "manual" : trim(trigger);
        return exportService.export()
            .flatMap(payload -> {
                String fingerprint;
                try {
                    fingerprint = buildFingerprint(payload);
                } catch (Exception error) {
                    return Mono.just(ObserveResult.failed(normalizedTrigger, "fingerprint failed: " + error.getMessage()));
                }

                String lastFingerprint = trim(lastPushedFingerprint.get());
                if (!lastFingerprint.isEmpty() && lastFingerprint.equals(fingerprint)) {
                    return Mono.just(ObserveResult.skipped(normalizedTrigger, "link snapshot unchanged"));
                }

                return pushService.push(payload)
                    .map(result -> {
                        if (result.success()) {
                            lastPushedFingerprint.set(fingerprint);
                        }
                        return new ObserveResult(
                            result.success(),
                            result.status(),
                            normalizedTrigger,
                            result.message(),
                            result.pushedAt(),
                            false
                        );
                    });
            })
            .doFinally(signalType -> observing.set(false));
    }

    private static String buildFingerprint(AstraHubLinkEdgeExportService.LinkEdgesPayload payload) throws Exception {
        List<LinkFingerprintItem> items = new ArrayList<>();
        for (AstraHubLinkEdgeExportService.LinkEdgeItem edge : payload.edges()) {
            items.add(new LinkFingerprintItem(
                trim(edge.targetUrl()),
                trim(edge.title()),
                trim(edge.description()),
                trim(edge.logo()),
                trim(edge.rssUrl()),
                edge.isActive()
            ));
        }
        items.sort(Comparator
            .comparing(LinkFingerprintItem::targetUrl)
            .thenComparing(LinkFingerprintItem::title)
            .thenComparing(LinkFingerprintItem::rssUrl));

        LinkFingerprintPayload normalized = new LinkFingerprintPayload(
            trim(payload.source() == null ? null : payload.source().siteId()),
            trim(payload.source() == null ? null : payload.source().siteName()),
            trim(payload.source() == null ? null : payload.source().siteUrl()),
            items
        );
        byte[] bytes = MAPPER.writeValueAsBytes(normalized);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(bytes));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            sb.append(String.format("%02x", value));
        }
        return sb.toString();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @PreDestroy
    public void destroy() {
        stop();
        scheduler.dispose();
    }

    private record LinkFingerprintPayload(
        String siteId,
        String siteName,
        String siteUrl,
        List<LinkFingerprintItem> edges
    ) {
    }

    private record LinkFingerprintItem(
        String targetUrl,
        String title,
        String description,
        String logo,
        String rssUrl,
        boolean isActive
    ) {
    }

    public record ObserveResult(
        boolean success,
        int status,
        String trigger,
        String message,
        String pushedAt,
        boolean skipped
    ) {
        public static ObserveResult skipped(String trigger, String message) {
            return new ObserveResult(true, 200, trim(trigger), message, "", true);
        }

        public static ObserveResult failed(String trigger, String message) {
            return new ObserveResult(false, 500, trim(trigger), message, "", false);
        }
    }
}
