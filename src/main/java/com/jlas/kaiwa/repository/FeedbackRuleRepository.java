package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.FeedbackRule;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedbackRuleRepository extends JpaRepository<FeedbackRule, Long> {
    List<FeedbackRule> findByEnabledTrueOrderByPriorityAsc();
}
