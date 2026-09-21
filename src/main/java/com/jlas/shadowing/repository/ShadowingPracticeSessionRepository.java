package com.jlas.shadowing.repository;

import com.jlas.shadowing.entity.ShadowingPracticeSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link ShadowingPracticeSession}. */
@Repository
public interface ShadowingPracticeSessionRepository extends JpaRepository<ShadowingPracticeSession, Long> {
    List<ShadowingPracticeSession> findByUserId(Long userId);
}
