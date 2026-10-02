package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningActivityMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.jlas.learning.enums.LearningMemberType;

public interface LearningActivityMemberRepository extends JpaRepository<LearningActivityMember, Long> {
    List<LearningActivityMember> findByActivityIdOrderByOrderIndex(Long activityId);
    long countByActivityLearningPackageIdAndMemberType(Long packageId, LearningMemberType memberType);
}
