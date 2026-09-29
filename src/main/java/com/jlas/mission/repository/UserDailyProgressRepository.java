package com.jlas.mission.repository;

import com.jlas.mission.entity.UserDailyProgress;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserDailyProgressRepository extends JpaRepository<UserDailyProgress, Long> {
    Optional<UserDailyProgress> findByUser_IdAndProgressDate(Long userId, LocalDate progressDate);

    List<UserDailyProgress> findByUser_IdAndProgressDateBetweenOrderByProgressDateAsc(
            Long userId, LocalDate from, LocalDate to);
}
