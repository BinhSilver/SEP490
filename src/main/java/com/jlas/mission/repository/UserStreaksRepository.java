package com.jlas.mission.repository;

import com.jlas.mission.entity.UserStreaks;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository cho {@link UserStreaks}. */
@Repository
public interface UserStreaksRepository extends JpaRepository<UserStreaks, Long> {
    Optional<UserStreaks> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UserStreaks s where s.userId = :userId")
    Optional<UserStreaks> findForUpdate(@Param("userId") Long userId);
}
