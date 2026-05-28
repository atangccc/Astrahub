package run.halo.astrahub;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AstraHubRelationBackfillService {

    private static final Duration STARTUP_DELAY = Duration.ofSeconds(8);

    private final AstraHubLinkEdgePushService linkEdgePushService;

    private final Scheduler scheduler = Schedulers.newSingle("astrahub-relation-backfill");
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Disposable> startupTask = new AtomicReference<>();

    public void scheduleStartupBackfill() {
        Disposable next = Mono.delay(STARTUP_DELAY, scheduler)
            .flatMap(ignored -> backfillNow("startup"))
            .onErrorResume(error -> {
                log.warn("[AstraHub] startup relation backfill failed", error);
                return Mono.empty();
            })
            .subscribe();

        Disposable old = startupTask.getAndSet(next);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
    }

    public Mono<BackfillResult> backfillNow(String trigger) {
        if (!running.compareAndSet(false, true)) {
            return Mono.just(BackfillResult.skipped(trigger, "backfill already running"));
        }

        String normalizedTrigger = trim(trigger);
        if (normalizedTrigger.isEmpty()) {
            normalizedTrigger = "manual";
        }
        final String finalTrigger = normalizedTrigger;

        return linkEdgePushService.push()
            .map(result -> new BackfillResult(
                result.success(),
                result.status(),
                finalTrigger,
                result.message(),
                result.pushedAt(),
                false
            ))
            .onErrorResume(error -> Mono.just(new BackfillResult(
                false,
                500,
                finalTrigger,
                "backfill failed: " + error.getMessage(),
                "",
                false
            )))
            .doFinally(signalType -> running.set(false));
    }

    @PreDestroy
    public void destroy() {
        Disposable current = startupTask.getAndSet(null);
        if (current != null && !current.isDisposed()) {
            current.dispose();
        }
        scheduler.dispose();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    public record BackfillResult(
        boolean success,
        int status,
        String trigger,
        String message,
        String pushedAt,
        boolean skipped
    ) {
        public static BackfillResult skipped(String trigger, String message) {
            return new BackfillResult(true, 200, trim(trigger), message, "", true);
        }
    }
}
