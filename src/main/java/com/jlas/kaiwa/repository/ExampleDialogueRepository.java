package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ExampleDialogue;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ExampleDialogueRepository extends RuntimeScenarioStore<ExampleDialogue> {
    public List<ExampleDialogue> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
}
