package com.jlas.shadowing.repository;

import com.jlas.shadowing.entity.PronunciationAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link PronunciationAttempt}. */
@Repository
public interface PronunciationAttemptRepository extends JpaRepository<PronunciationAttempt, Long> {
    List<PronunciationAttempt> findByUserId(Long userId);
}
