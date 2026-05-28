package run.halo.astrahub.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "core.halo.run", version = "v1alpha1", kind = "LinkGroup", plural = "linkgroups", singular = "linkgroup")
public class LinkGroup extends AbstractExtension {

    private LinkGroupSpec spec;

    /**
     * Runtime grouped links. Some Halo APIs return link objects here instead of spec.links.
     */
    private List<Link> links = new ArrayList<>();

    @Data
    public static class LinkGroupSpec {
        @Schema(description = "Group display name")
        private String displayName;

        @Schema(description = "Group priority")
        private Integer priority;

        @Schema(description = "Link metadata.name list in this group")
        private List<String> links = new ArrayList<>();
    }
}