package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningPathItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserLearningPathItem}. */
@Repository
public interface UserLearningPathItemRepository extends JpaRepository<UserLearningPathItem, Long> {
    List<UserLearningPathItem> findByLearningPathIdOrderByOrderIndex(Long learningPathId);

    /** Loads items together with lesson/unit/textbook so the response snapshot avoids N+1 reads. */
    @Query("""
            select item from UserLearningPathItem item
            join fetch item.lesson lesson
            join fetch lesson.unit unit
            join fetch unit.textbook textbook
            where item.learningPath.id = :learningPathId
            order by item.orderIndex
            """)
    List<UserLearningPathItem> findWithLessonByLearningPathId(@Param("learningPathId") Long learningPathId);
}
