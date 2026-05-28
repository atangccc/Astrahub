package run.halo.astrahub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.astrahub.model.FriendInvitation;

@Slf4j
@Component
public class AstraHubPlugin extends BasePlugin {

    private final SchemeManager schemeManager;
    private final AstraHubReportOrchestratorService reportOrchestratorService;
    private final AstraHubFriendInboxSyncService friendInboxSyncService;
    private final AstraHubFriendOutboxSyncService friendOutboxSyncService;
    private final AstraHubFriendRetryService friendRetryService;
    private final AstraHubLinkChangeMonitorService linkChangeMonitorService;

    public AstraHubPlugin(PluginContext pluginContext,
                          SchemeManager schemeManager,
                          AstraHubReportOrchestratorService reportOrchestratorService,
                          AstraHubFriendInboxSyncService friendInboxSyncService,
                          AstraHubFriendOutboxSyncService friendOutboxSyncService,
                          AstraHubFriendRetryService friendRetryService,
                          AstraHubLinkChangeMonitorService linkChangeMonitorService) {
        super(pluginContext);
        this.schemeManager = schemeManager;
        this.reportOrchestratorService = reportOrchestratorService;
        this.friendInboxSyncService = friendInboxSyncService;
        this.friendOutboxSyncService = friendOutboxSyncService;
        this.friendRetryService = friendRetryService;
        this.linkChangeMonitorService = linkChangeMonitorService;
    }

    @Override
    public void start() {
        schemeManager.register(FriendInvitation.class);
        reportOrchestratorService.startScheduler();
        friendInboxSyncService.start();
        friendOutboxSyncService.start();
        friendRetryService.start();
        linkChangeMonitorService.start();
        log.info("[AstraHub] Plugin started");
    }

    @Override
    public void stop() {
        reportOrchestratorService.stopScheduler();
        friendInboxSyncService.stop();
        friendOutboxSyncService.stop();
        friendRetryService.stop();
        linkChangeMonitorService.stop();
        try {
            schemeManager.unregister(Scheme.buildFromType(FriendInvitation.class));
        } catch (Exception error) {
            log.warn("[AstraHub] unregister FriendInvitation scheme failed", error);
        }
        log.info("[AstraHub] Plugin stopped");
    }
}
