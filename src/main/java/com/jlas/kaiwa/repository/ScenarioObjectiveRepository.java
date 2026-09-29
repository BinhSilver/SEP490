package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioObjective;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioObjectiveRepository extends RuntimeScenarioStore<ScenarioObjective> {
    public List<ScenarioObjective> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
}
