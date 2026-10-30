package com.jlas.mission.repository;

import com.jlas.mission.entity.UserMission;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserMission}. */
@Repository
public interface UserMissionRepository extends JpaRepository<UserMission, Long> {
    List<UserMission> findByUserId(Long userId);

    @Query("select m from UserMission m join fetch m.missionDefinition d "
            + "where m.user.id = :userId and m.occurrenceKey = :occurrenceKey")
    List<UserMission> findWithDefinition(@Param("userId") Long userId, @Param("occurrenceKey") String occurrenceKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from UserMission m where m.user.id = :userId "
            + "and m.missionDefinition.id = :definitionId and m.occurrenceKey = :occurrenceKey")
    Optional<UserMission> findForUpdate(@Param("userId") Long userId, @Param("definitionId") Long definitionId,
                                        @Param("occurrenceKey") String occurrenceKey);
}
