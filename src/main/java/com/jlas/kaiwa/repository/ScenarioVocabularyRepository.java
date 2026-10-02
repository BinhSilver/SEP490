package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ScenarioVocabulary;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ScenarioVocabularyRepository extends RuntimeScenarioStore<ScenarioVocabulary> {
    public List<ScenarioVocabulary> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
}
