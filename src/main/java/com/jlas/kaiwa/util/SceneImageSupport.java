package com.jlas.kaiwa.util;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import java.util.Objects;

/** Scene illustrations follow route occurrences, not just reusable graph state codes. */
public final class SceneImageSupport {
    private SceneImageSupport() {}

    public static String current(ConversationSession session, ScenarioContentService content) {
        var flow = content.flow(session.getScenario()).orElse(null);
        var stage = StageFlowSupport.stage(session);
        if (stage != null) return forStep(stage, session.getStageCursor(), flow, session.getScenario().getThumbnail());
        return forState(session.getCurrentStateCode(), flow, session.getScenario().getThumbnail());
    }

    public static String forStep(ScenarioStage stage, int cursor, ScenarioCompactDefinition flow, String fallback) {
        if (cursor >= 0 && cursor < stage.stateCodes().size()) {
            if (stage.imageUrls() != null && cursor < stage.imageUrls().size()) {
                String image = local(stage.imageUrls().get(cursor));
                if (image != null) return image;
            }
            return forState(stage.stateCodes().get(cursor), flow, fallback);
        }
        return local(fallback);
    }

    private static String forState(String code, ScenarioCompactDefinition flow, String fallback) {
        if (flow != null && flow.states() != null) {
            String current = code == null ? flow.initialState() : code;
            for (var state : flow.states()) {
                if (Objects.equals(current, state.code())) {
                    String image = local(state.imageUrl());
                    if (image != null) return image;
                }
            }
        }
        return local(fallback);
    }

    private static String local(String url) {
        return url != null && url.matches("/images/(?:scenes|scenarios)/[a-z0-9-]+\\.(?:png|webp)") ? url : null;
    }
}
