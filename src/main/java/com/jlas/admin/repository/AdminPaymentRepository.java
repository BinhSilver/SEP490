package com.jlas.admin.repository;

import com.jlas.auth.entity.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only payment lookup for System Admin; payments are written by the payment flow only. */
public interface AdminPaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {
    @Override
    @EntityGraph(attributePaths = "user")
    Page<Payment> findAll(Specification<Payment> spec, Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Optional<Payment> findWithUserById(Long id);

    @Query("select p.status, count(p) from Payment p group by p.status")
    List<Object[]> countByStatus();

    /** [currency, paid count, paid total] for payments completed since {@code from}. */
    @Query("select p.currency, count(p), sum(p.amountPaid) from Payment p"
            + " where p.status = 'PAID' and p.paidAt >= :from group by p.currency")
    List<Object[]> paidSince(@Param("from") LocalDateTime from);

    @Query("select p.productType, p.currency, count(p), sum(p.amountPaid), sum(p.discountAmount) from Payment p"
            + " where p.status = 'PAID' and p.paidAt >= :from group by p.productType, p.currency")
    List<Object[]> paidByProductSince(@Param("from") LocalDateTime from);
}
