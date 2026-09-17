package com.jlas.moderation.repository;

import com.jlas.moderation.entity.UserCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link UserCreditTransaction}. */
@Repository
public interface UserCreditTransactionRepository extends JpaRepository<UserCreditTransaction, Long> {
    List<UserCreditTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** True when a ledger entry for the same reference already exists, so awards stay idempotent. */
    @Query("select count(t) > 0 from UserCreditTransaction t " +
            "where t.user.id = :userId and t.reason = :reason " +
            "and t.referenceType = :referenceType and t.referenceId = :referenceId")
    boolean existsReference(@Param("userId") Long userId,
                            @Param("reason") String reason,
                            @Param("referenceType") String referenceType,
                            @Param("referenceId") Long referenceId);
}
