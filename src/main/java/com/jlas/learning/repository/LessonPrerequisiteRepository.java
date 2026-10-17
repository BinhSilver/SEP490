package com.jlas.learning.repository;

import com.jlas.learning.entity.LessonPrerequisite;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface LessonPrerequisiteRepository extends JpaRepository<LessonPrerequisite, Long> {
    List<LessonPrerequisite> findByLessonIdInAndReviewStatus(Collection<Long> lessonIds, String reviewStatus);
}
