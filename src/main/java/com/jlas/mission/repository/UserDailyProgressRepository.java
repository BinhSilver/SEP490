package com.jlas.mission.repository;

import com.jlas.mission.entity.UserDailyProgress;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Repository for {@link UserDailyProgress}. */
@Repository
public interface UserDailyProgressRepository extends JpaRepository<UserDailyProgress, Long> {

    Optional<UserDailyProgress> findByUserIdAndProgressDate(Long userId, LocalDate progressDate);

    List<UserDailyProgress> findByUserIdAndProgressDateBetween(Long userId, LocalDate from, LocalDate to);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserDailyProgress p where p.user.id = :userId and p.progressDate = :day")
    Optional<UserDailyProgress> findForUpdate(@Param("userId") Long userId, @Param("day") LocalDate day);
}
