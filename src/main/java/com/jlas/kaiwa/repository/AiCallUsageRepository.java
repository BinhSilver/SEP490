package com.jlas.kaiwa.repository;

import com.jlas.moderation.entity.AiUsageDaily;
import com.jlas.moderation.entity.AiUsageDaily.AiUsageDailyId;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Số phút AI Call đã dùng theo ngày (bảng ai_usage_daily, entity dùng chung {@link AiUsageDaily}). */
public interface AiCallUsageRepository extends JpaRepository<AiUsageDaily, AiUsageDailyId> {

    @Query("SELECT u FROM AiUsageDaily u WHERE u.id.userId = :userId AND u.id.usageDate = :date")
    Optional<AiUsageDaily> findByUserIdAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AiUsageDaily u WHERE u.id.userId = :userId AND u.id.usageDate = :date")
    Optional<AiUsageDaily> lockByUserIdAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Query("SELECT SUM(u.minutesUsed) FROM AiUsageDaily u WHERE u.id.userId = :userId AND u.id.usageDate BETWEEN :from AND :to")
    BigDecimal sumMinutesBetween(@Param("userId") Long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
