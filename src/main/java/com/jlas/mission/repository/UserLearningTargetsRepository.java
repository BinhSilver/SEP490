package com.jlas.mission.repository;

import com.jlas.mission.entity.UserLearningTargets;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link UserLearningTargets}. */
@Repository
public interface UserLearningTargetsRepository extends JpaRepository<UserLearningTargets, Long> {

    Optional<UserLearningTargets> findByUserId(Long userId);
}
