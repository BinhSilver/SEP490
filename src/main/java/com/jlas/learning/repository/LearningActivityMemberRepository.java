package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningActivityMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LearningActivityMemberRepository extends JpaRepository<LearningActivityMember, Long> {
    List<LearningActivityMember> findByActivityIdOrderByOrderIndex(Long activityId);
}
