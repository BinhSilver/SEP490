package com.jlas.mission.repository;

import com.jlas.mission.entity.UserLearningTargets;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserLearningTargetsRepository extends JpaRepository<UserLearningTargets, Long> { }
