package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ConversationState;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationStateRepository extends RuntimeScenarioStore<ConversationState> {
    public List<ConversationState> findByScenarioIdOrderByDisplayOrderAsc(Long id) { return byScenario(id); }
    public Optional<ConversationState> findFirstByScenarioIdOrderByDisplayOrderAsc(Long id) {
        return byScenario(id).stream().findFirst();
    }
}
