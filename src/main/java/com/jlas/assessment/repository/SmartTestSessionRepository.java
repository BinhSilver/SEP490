package com.jlas.assessment.repository;

import com.jlas.assessment.entity.SmartTestSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository cho {@link SmartTestSession}. */
@Repository
public interface SmartTestSessionRepository extends JpaRepository<SmartTestSession, Long> { }
