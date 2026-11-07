package com.jlas.moderation.repository;

import com.jlas.moderation.entity.UserCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

/** Repository cho {@link UserCreditTransaction}. */
@Repository
public interface UserCreditTransactionRepository extends JpaRepository<UserCreditTransaction, Long> {
    @Query("select t from UserCreditTransaction t where t.user.id = :userId order by t.createdAt desc")
    List<UserCreditTransaction> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    /** Net credit a user holds for one reference (rewards minus take-backs). */
    @Query("""
            select coalesce(sum(t.amount), 0) from UserCreditTransaction t
            where t.user.id = :userId and (
                (t.referenceType = :referenceType and t.referenceId = :referenceId)
                or (t.referenceType = 'MODERATION_REVISION' and t.referenceId in (
                    select a.id from ModerationAuditLog a
                    where a.action = 'DICTIONARY_REVISE'
                    and a.targetType = :referenceType and a.targetId = :referenceId
                ))
            )
            """)
    long netAmount(@Param("userId") Long userId, @Param("referenceType") String referenceType,
                   @Param("referenceId") Long referenceId);

    boolean existsByUserIdAndReasonAndReferenceTypeAndReferenceId(
            Long userId, String reason, String referenceType, Long referenceId);

    /** CP earned in [from, to) from the given reasons, leaving out one-time reference types (daily earn cap). */
    @Query("""
            select coalesce(sum(t.amount), 0) from UserCreditTransaction t
            where t.user.id = :userId and t.amount > 0 and t.reason in :reasons
            and (t.referenceType is null or t.referenceType not in :exemptReferenceTypes)
            and t.createdAt >= :from and t.createdAt < :to
            """)
    long earnedBetween(@Param("userId") Long userId, @Param("reasons") List<String> reasons,
                       @Param("exemptReferenceTypes") List<String> exemptReferenceTypes,
                       @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Number of positive ledger entries for one reason in [from, to), e.g. paid word contributions today. */
    @Query("""
            select count(t) from UserCreditTransaction t
            where t.user.id = :userId and t.amount > 0 and t.reason = :reason
            and t.createdAt >= :from and t.createdAt < :to
            """)
    long countEarnedBetween(@Param("userId") Long userId, @Param("reason") String reason,
                            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** True when a ledger entry for the same reference already exists, so awards stay idempotent. */
    @Query("select count(t) > 0 from UserCreditTransaction t " +
            "where t.user.id = :userId and t.reason = :reason " +
            "and t.referenceType = :referenceType and t.referenceId = :referenceId")
    boolean existsReference(@Param("userId") Long userId,
                            @Param("reason") String reason,
                            @Param("referenceType") String referenceType,
                            @Param("referenceId") Long referenceId);
}
