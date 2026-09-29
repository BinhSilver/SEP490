package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioHint;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioHintRepository extends RuntimeScenarioStore<ScenarioHint> {
    public List<ScenarioHint> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
}
