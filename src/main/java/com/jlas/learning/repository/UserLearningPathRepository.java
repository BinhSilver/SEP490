package com.jlas.learning.repository;

import com.jlas.learning.entity.UserLearningPath;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

/** Repository cho {@link UserLearningPath}. */
@Repository
public interface UserLearningPathRepository extends JpaRepository<UserLearningPath, Long> {
    Optional<UserLearningPath> findByUserId(Long userId);
}
