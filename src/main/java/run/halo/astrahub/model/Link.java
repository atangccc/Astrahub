package run.halo.astrahub.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "core.halo.run", version = "v1alpha1", kind = "Link", plural = "links", singular = "link")
public class Link extends AbstractExtension {

    private LinkSpec spec;

    @Data
    public static class LinkSpec {
        @Schema(description = "Link URL")
        private String url;

        @Schema(description = "Display name")
        private String displayName;

        @Schema(description = "Description")
        private String description;

        @Schema(description = "Logo")
        private String logo;

        @Schema(description = "Priority")
        private Integer priority;

        @Schema(description = "Group metadata.name, returned by some PluginLinks APIs")
        private String groupName;
    }
}