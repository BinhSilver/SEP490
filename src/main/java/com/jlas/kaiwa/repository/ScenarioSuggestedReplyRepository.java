package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioSuggestedReply;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioSuggestedReplyRepository extends RuntimeScenarioStore<ScenarioSuggestedReply> {
    public List<ScenarioSuggestedReply> findForState(Long scenarioId, String stateName) {
        return byScenario(scenarioId).stream()
                .filter(row -> row.getStateName() == null || row.getStateName().equals(stateName))
                .toList();
    }
}
