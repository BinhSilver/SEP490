package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningTopic;
import org.springframework.data.jpa.repository.JpaRepository;

/** [JLAS-W0108] Read-only access to goal topics (communication grouping, owner 2026-10-19). */
public interface LearningTopicRepository extends JpaRepository<LearningTopic, Long> {
}
