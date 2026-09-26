package com.jlas.learning.repository;

import com.jlas.learning.entity.LessonProgress;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link LessonProgress}. */
@Repository
public interface LessonProgressRepository extends JpaRepository<LessonProgress, Long> {
    List<LessonProgress> findByUserId(Long userId);

    Optional<LessonProgress> findByUserIdAndLessonId(Long userId, Long lessonId);

    /** Completed lesson ids for a user, used to mark items in the path response. */
    @Query("select p.lesson.id from LessonProgress p where p.user.id = :userId and p.status = 'COMPLETED'")
    Set<Long> findCompletedLessonIds(@Param("userId") Long userId);
}
