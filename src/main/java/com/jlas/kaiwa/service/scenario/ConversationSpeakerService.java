package com.jlas.kaiwa.service.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.util.StageFlowSupport;
import java.util.*;
import org.springframework.stereotype.Service;

/** Resolves authored actors and snapshots the actual speaker of each utterance. */
@Service
public class ConversationSpeakerService {
    private final ScenarioContentService content;
    private final ObjectMapper json;

    public ConversationSpeakerService(ScenarioContentService content, ObjectMapper json) {
        this.content = content; this.json = json;
    }

    public ScenarioSpeaker current(ConversationSession session) {
        var stage = StageFlowSupport.stage(session);
        if (stage != null && stage.speakers() != null && stage.speakerCodes() != null
                && session.getStageCursor() >= 0 && session.getStageCursor() < stage.speakerCodes().size()) {
            String code = stage.speakerCodes().get(session.getStageCursor());
            return stage.speakers().stream().filter(s -> code.equals(s.code())).findFirst().orElse(null);
        }
        return forState(session.getScenario(), session.getCurrentStateCode());
    }

    public List<ScenarioSpeaker> cast(ConversationSession session) {
        var stage = StageFlowSupport.stage(session);
        if (stage != null && stage.speakers() != null) return stage.speakers();
        return content.flow(session.getScenario()).map(ScenarioCompactDefinition::speakers).orElse(List.of());
    }

    public ScenarioSpeaker forState(Scenario scenario, String stateCode) {
        var flow = content.flow(scenario).orElse(null);
        if (flow == null || flow.speakers() == null || flow.speakers().isEmpty()) return null;
        String state = stateCode == null ? flow.initialState() : stateCode;
        String code = flow.states().stream().filter(s -> state.equals(s.code()))
                .map(ScenarioCompactDefinition.State::speakerCode).filter(Objects::nonNull)
                .findFirst().orElse(flow.speakers().getFirst().code());
        return flow.speakers().stream().filter(s -> code.equals(s.code())).findFirst().orElse(null);
    }

    public ScenarioSpeaker forMessage(ConversationMessage message) {
        if (!"assistant".equals(message.getRole())) return null;
        if (message.getMetaJson() != null) {
            try {
                var node = json.readTree(message.getMetaJson()).get("speaker");
                if (node != null && !node.isNull()) return json.treeToValue(node, ScenarioSpeaker.class);
            } catch (Exception ignored) { }
        }
        // Old transcripts predate casting: do not retrospectively assign them a new actor/voice.
        return null;
    }

    public String metadata(Object modelReply, ScenarioSpeaker speaker) {
        try {
            var node = modelReply == null ? json.createObjectNode() : (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(modelReply);
            if (speaker != null) node.set("speaker", json.valueToTree(speaker));
            return json.writeValueAsString(node);
        } catch (Exception e) { throw new IllegalStateException("Cannot snapshot conversation speaker", e); }
    }

    public String opening(ConversationSession session) {
        String stateCode = session.getCurrentStateCode();
        return content.flow(session.getScenario()).stream().flatMap(f -> f.states().stream())
                .filter(s -> Objects.equals(stateCode, s.code()))
                .map(ScenarioCompactDefinition.State::openingLine).filter(Objects::nonNull)
                .filter(s -> !s.isBlank()).findFirst().orElse(null);
    }
}
