package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioEnding;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioEndingRepository extends RuntimeScenarioStore<ScenarioEnding> {
    public List<ScenarioEnding> findByScenarioId(Long id) { return byScenario(id); }
}
