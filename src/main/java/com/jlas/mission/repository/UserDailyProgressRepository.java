package com.jlas.mission.repository;

import com.jlas.mission.entity.UserDailyProgress;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Repository for {@link UserDailyProgress}. */
@Repository
public interface UserDailyProgressRepository extends JpaRepository<UserDailyProgress, Long> {

    Optional<UserDailyProgress> findByUserIdAndProgressDate(Long userId, LocalDate progressDate);
}
