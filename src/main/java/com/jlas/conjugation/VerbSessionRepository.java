package com.jlas.conjugation;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface VerbSessionRepository extends JpaRepository<VerbPracticeSession,String> {
    Optional<VerbPracticeSession> findByIdAndUserId(String id,Long userId);
    List<VerbPracticeSession> findByUserIdAndFinishedAtIsNull(Long userId);
    List<VerbPracticeSession> findTop5ByUserIdOrderByStartedAtDesc(Long userId);
    @Query("select s from VerbPracticeSession s where s.userId=:user and (s.startedAt>=:since or s.finishedAt>=:since or s.finishedAt is null)")
    List<VerbPracticeSession> forDailyProgress(@Param("user") Long user,@Param("since") Instant since);
    @Query("select coalesce(max(s.correctCount),0) from VerbPracticeSession s where s.userId=:user and s.mode='SPEED' and s.finishedAt is not null")
    int speedRecord(@Param("user") Long user);
}
