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
    @Query("select t from UserCreditTransaction t where t.user.id = :userId order by t.createdAt desc")
    List<UserCreditTransaction> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    boolean existsByUserIdAndReasonAndReferenceTypeAndReferenceId(
            Long userId, String reason, String referenceType, Long referenceId);
}
