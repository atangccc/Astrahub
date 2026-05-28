package run.halo.astrahub;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import run.halo.app.theme.finders.Finder;

/**
 * Finder API for theme-side AstraHub status rendering.
 */
@Finder("astraHubFinder")
@RequiredArgsConstructor
public class AstraHubFinder {

    private final AstraHubPublicStatusService publicStatusService;

    public Mono<FooterStatus> footerStatus() {
        return publicStatusService.currentStatus()
            .map(snapshot -> new FooterStatus(
                snapshot.available(),
                snapshot.brand(),
                snapshot.primarySuffix(),
                snapshot.secondaryLine(),
                snapshot.nodeName(),
                snapshot.linked(),
                snapshot.healthy(),
                snapshot.phase(),
                snapshot.status(),
                snapshot.updatedAt()
            ))
            .onErrorResume(error -> Mono.just(FooterStatus.fallback()));
    }

    public record FooterStatus(
        boolean available,
        String brand,
        String primarySuffix,
        String secondaryLine,
        String nodeName,
        boolean linked,
        boolean healthy,
        String phase,
        int status,
        String updatedAt
    ) {
        public static FooterStatus fallback() {
            return new FooterStatus(
                true,
                "未命名节点",
                " 未接入主星",
                "尚未与主星建立链路 // 协议版本 " + AstraHubPublicStatusService.PROTOCOL_VERSION,
                "未命名节点",
                false,
                false,
                "UNKNOWN",
                0,
                ""
            );
        }
    }
}
