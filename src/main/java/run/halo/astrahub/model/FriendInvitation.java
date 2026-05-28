package run.halo.astrahub.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(
    group = "astrahub.plugin.halo.run",
    version = "v1alpha1",
    kind = "FriendInvitation",
    plural = "friendinvitations",
    singular = "friendinvitation"
)
public class FriendInvitation extends AbstractExtension {

    private FriendInvitationSpec spec;

    @Data
    public static class FriendInvitationSpec {
        @Schema(description = "Shared invitation id across inbox/outbox records")
        private String invitationId;

        @Schema(description = "Record direction: inbox/outbox")
        private InvitationDirection direction;

        @Schema(description = "Current invitation status")
        private InvitationStatus status;

        @Schema(description = "Current delivery status")
        private String deliveryStatus;

        @Schema(description = "Peer site id")
        private String peerSiteId;

        @Schema(description = "Peer site name")
        private String peerSiteName;

        @Schema(description = "Peer site url")
        private String peerSiteUrl;

        @Schema(description = "Peer site description")
        private String peerSiteDescription;

        @Schema(description = "Peer site avatar")
        private String peerSiteAvatar;

        @Schema(description = "Peer site rss url")
        private String peerSiteRssUrl;

        @Schema(description = "Peer contact email")
        private String peerContactEmail;

        @Schema(description = "Hub base url")
        private String peerHubBaseUrl;

        @Schema(description = "Ack callback url")
        private String callbackUrl;

        @Schema(description = "Optional message")
        private String message;

        @Schema(description = "Review reason")
        private String reviewReason;

        @Schema(description = "Selected link group name")
        private String linkGroupName;

        @Schema(description = "Remote record name")
        private String remoteRecordName;

        @Schema(description = "Created time from hub")
        private String createdAt;

        @Schema(description = "Updated time from hub")
        private String updatedAt;

        @Schema(description = "Reviewed time")
        private String reviewedAt;

        @Schema(description = "Last ack time")
        private String ackedAt;

        @Schema(description = "Last error message")
        private String lastError;

        @Schema(description = "Retry count")
        private Integer retryCount;
    }

    public enum InvitationDirection {
        inbox,
        outbox
    }

    public enum InvitationStatus {
        pending,
        accepted,
        rejected,
        cancelled,
        expired
    }
}
