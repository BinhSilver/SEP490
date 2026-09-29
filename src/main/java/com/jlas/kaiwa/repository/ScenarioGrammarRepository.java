package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioGrammar;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioGrammarRepository extends RuntimeScenarioStore<ScenarioGrammar> {
    public List<ScenarioGrammar> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
}
