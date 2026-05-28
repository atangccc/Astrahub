package run.halo.astrahub;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import reactor.core.publisher.Mono;
import run.halo.app.theme.dialect.TemplateHeadProcessor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE - 20)
public class AstraHubGalaxyWidgetHeadProcessor implements TemplateHeadProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STYLE_RESOURCE = "static/widget/galaxy-link-widget.css";
    private static final String SCRIPT_RESOURCE = "static/widget/galaxy-link-widget.js";
    private static final String WIDGET_STYLE = loadResourceText(STYLE_RESOURCE);
    private static final String WIDGET_SCRIPT = loadResourceText(SCRIPT_RESOURCE);

    private final AstraHubPublicStatusService publicStatusService;
    private final AstraHubCollectionService collectionService;

    @Override
    public Mono<Void> process(ITemplateContext context, IModel model,
                              IElementModelStructureHandler structureHandler) {
        if (WIDGET_STYLE.isBlank() || WIDGET_SCRIPT.isBlank()) {
            return Mono.empty();
        }

        return Mono.zip(
                publicStatusService.currentStatus(),
                collectionService.collect()
                    .onErrorResume(error -> {
                        log.debug("[AstraHub] fallback to empty widget payload: {}", error.getMessage());
                        return Mono.just(emptyCollectedPayload());
                    })
            )
            .map(tuple -> buildWidgetData(tuple.getT1(), tuple.getT2()))
            .filter(data -> readBoolean(data.get("render"), false))
            .doOnNext(data -> injectWidget(context, model, data))
            .onErrorResume(error -> {
                log.warn("[AstraHub] inject galaxy widget failed", error);
                return Mono.empty();
            })
            .then();
    }

    private Map<String, Object> buildWidgetData(AstraHubPublicStatusService.PublicStatusSnapshot snapshot,
                                                AstraHubCollectionService.CollectedPayload payload) {
        List<Map<String, String>> creators = new ArrayList<>();
        if (payload != null && payload.links() != null) {
            for (AstraHubCollectionService.LinkSnapshot link : payload.links()) {
                if (creators.size() >= 4) {
                    break;
                }
                if (link == null) {
                    continue;
                }
                String title = trim(link.title());
                if (title.isBlank()) {
                    continue;
                }
                boolean exists = creators.stream()
                    .map(item -> trim(item.get("name")))
                    .anyMatch(existing -> existing.equalsIgnoreCase(title));
                if (exists) {
                    continue;
                }
                Map<String, String> creator = new LinkedHashMap<>();
                creator.put("name", title);
                creator.put("avatar", trim(link.logo()));
                creators.add(creator);
            }
        }
        if (creators.isEmpty()) {
            creators.add(Map.of("name", "AstraHub", "avatar", ""));
        }

        int totalLinks = payload == null ? 0 : Math.max(0, payload.totalLinks());
        int totalGroups = payload == null ? 0 : Math.max(0, payload.totalGroups());
        int groupedLinks = payload == null ? 0 : Math.max(0, payload.groupedLinks());
        int standaloneLinks = payload == null ? 0 : Math.max(0, payload.standaloneLinks());
        int moreCreatorCount = Math.max(0, totalLinks - creators.size());
        String featuredCreator = trim(creators.get(0).get("name"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("render", snapshot.widgetEnabled() && snapshot.linked());
        data.put("linked", snapshot.linked());
        data.put("healthy", snapshot.healthy());
        data.put("siteName", snapshot.siteName());
        data.put("siteUrl", snapshot.siteUrl());
        data.put("nodeName", snapshot.nodeName());
        data.put("nodeAvatar", snapshot.nodeAvatar());
        data.put("hubBaseUrl", snapshot.hubBaseUrl());
        data.put("joinUrl", !trim(snapshot.hubBaseUrl()).isBlank() ? snapshot.hubBaseUrl() : snapshot.siteUrl());
        data.put("protocol", "GALAXY-X9");
        data.put("statusLabel", snapshot.statusLabel());
        data.put("realtimeBroadcast", snapshot.realtimeBroadcast());
        data.put("creators", creators);
        data.put("featuredCreator", featuredCreator);
        data.put("moreCreatorCount", moreCreatorCount);
        data.put("metrics", Map.of(
            "totalLinks", totalLinks,
            "totalGroups", totalGroups,
            "groupedLinks", groupedLinks,
            "standaloneLinks", standaloneLinks
        ));
        return data;
    }

    private void injectWidget(ITemplateContext context, IModel model, Map<String, Object> data) {
        IModelFactory modelFactory = context.getModelFactory();
        String json;
        try {
            json = MAPPER.writeValueAsString(data);
        } catch (Exception error) {
            log.warn("[AstraHub] serialize widget data failed", error);
            return;
        }
        String safeJson = escapeInlineJson(json);

        model.add(modelFactory.createText("\n<style id=\"astrahub-galaxy-widget-style\">\n"
            + WIDGET_STYLE + "\n</style>\n"));
        model.add(modelFactory.createText(
            "<script id=\"astrahub-galaxy-widget-data\" type=\"application/json\">"
                + safeJson
                + "</script>\n"
        ));
        model.add(modelFactory.createText(
            "<script id=\"astrahub-galaxy-widget-script\">"
                + WIDGET_SCRIPT
                + "</script>\n"
        ));
    }

    private static boolean readBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        String text = trim(Objects.toString(value, ""));
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return fallback;
    }

    private static String escapeInlineJson(String raw) {
        return raw
            .replace("&", "\\u0026")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e");
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static AstraHubCollectionService.CollectedPayload emptyCollectedPayload() {
        return new AstraHubCollectionService.CollectedPayload(
            "",
            0,
            0,
            0,
            0,
            List.of(),
            List.of()
        );
    }

    private static String loadResourceText(String path) {
        try (InputStream stream = AstraHubGalaxyWidgetHeadProcessor.class.getClassLoader()
            .getResourceAsStream(path)) {
            if (stream == null) {
                return "";
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "";
        }
    }
}
