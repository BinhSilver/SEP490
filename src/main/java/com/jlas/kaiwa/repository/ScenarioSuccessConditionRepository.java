package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioSuccessCondition;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioSuccessConditionRepository extends RuntimeScenarioStore<ScenarioSuccessCondition> {
    public List<ScenarioSuccessCondition> findByScenarioId(Long id) { return byScenario(id); }
}
