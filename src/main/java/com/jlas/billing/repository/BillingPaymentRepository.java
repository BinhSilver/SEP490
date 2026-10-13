package com.jlas.billing.repository;

import com.jlas.auth.entity.Payment;
import com.jlas.auth.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BillingPaymentRepository extends JpaRepository<Payment, Long> {
    @EntityGraph(attributePaths = "premiumPlan")
    Optional<Payment> findByProviderAndOrderCodeAndUser_Id(String provider, String orderCode, Long userId);

    @EntityGraph(attributePaths = "premiumPlan")
    List<Payment> findTop50ByUser_IdOrderByCreatedAtDescIdDesc(Long userId);

    /** The buyer's still-payable order for the same plan, so a second click reuses it instead of a new one. */
    @Query("select p from Payment p where p.provider = :provider and p.user.id = :userId and p.premiumPlan.id = :planId"
            + " and p.status = 'PENDING' and p.checkoutUrl is not null and p.expiresAt > :payableUntil"
            + " order by p.createdAt desc")
    List<Payment> findReusable(@Param("provider") String provider, @Param("userId") Long userId,
                               @Param("planId") Long planId, @Param("payableUntil") LocalDateTime payableUntil);

    /** Pending orders nobody has checked recently: the background job asks PayOS about them. */
    @Query("select p.id from Payment p where p.provider = :provider and p.status = 'PENDING'"
            + " and p.createdAt < :createdBefore and (p.checkedAt is null or p.checkedAt < :checkedBefore)"
            + " order by p.createdAt")
    List<Long> findDueForCheck(@Param("provider") String provider, @Param("createdBefore") LocalDateTime createdBefore,
                               @Param("checkedBefore") LocalDateTime checkedBefore, Limit limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p join fetch p.premiumPlan where p.id = :id")
    Optional<Payment> findForUpdate(@Param("id") Long id);

    /** Serializes Premium grants of one account, so two orders paid together both add their days. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> lockUser(@Param("id") Long id);
}
