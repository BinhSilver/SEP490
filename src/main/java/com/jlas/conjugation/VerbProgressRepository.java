package com.jlas.conjugation;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface VerbProgressRepository extends JpaRepository<VerbPracticeProgress,Long> {
    List<VerbPracticeProgress> findByUserId(Long userId);
    Optional<VerbPracticeProgress> findByUserIdAndVerbIdAndFormCode(Long userId,String verbId,String formCode);
}
