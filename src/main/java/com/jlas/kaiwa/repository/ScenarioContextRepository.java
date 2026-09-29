package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioContext;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioContextRepository extends RuntimeScenarioStore<ScenarioContext> {
    public Optional<ScenarioContext> findByScenarioId(Long id) { return byScenario(id).stream().findFirst(); }
}
