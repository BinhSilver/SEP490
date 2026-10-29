package com.jlas.learning.repository;

import com.jlas.learning.entity.LearningActivityMember;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.jlas.learning.enums.LearningMemberType;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningActivityMemberRepository extends JpaRepository<LearningActivityMember, Long> {
    @Query("select m from LearningActivityMember m where m.activity.learningPath.id = :pathId "
            + "order by m.activity.orderIndex, m.orderIndex, m.id")
    List<LearningActivityMember> findByPathInStudyOrder(@Param("pathId") Long pathId);
    List<LearningActivityMember> findByActivityIdOrderByOrderIndex(Long activityId);

    /** One query for many activities (roadmap reconcilers), in the same per-activity order as above. */
    @Query("select m from LearningActivityMember m where m.activity.id in :activityIds "
            + "order by m.activity.id, m.orderIndex, m.id")
    List<LearningActivityMember> findByActivityIdsInOrder(@Param("activityIds") java.util.Collection<Long> activityIds);
    long countByActivityLearningPackageIdAndMemberType(Long packageId, LearningMemberType memberType);

    @Query("""
            select m.activity.learningPackage.id as packageId, m.memberType as memberType, count(m.id) as memberCount
              from LearningActivityMember m
             where m.activity.learningPackage.id in :packageIds
             group by m.activity.learningPackage.id, m.memberType
            """)
    List<PackageMemberCount> findPackageMemberCounts(@Param("packageIds") List<Long> packageIds);

    interface PackageMemberCount {
        Long getPackageId();
        LearningMemberType getMemberType();
        Long getMemberCount();
    }
}
