package com.jlas.moderation.repository;

import com.jlas.moderation.entity.UserCreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/** Repository cho {@link UserCreditTransaction}. */
@Repository
public interface UserCreditTransactionRepository extends JpaRepository<UserCreditTransaction, Long> {
    List<UserCreditTransaction> findByUserIdOrderByCreatedAtDesc(Long userId);
}
