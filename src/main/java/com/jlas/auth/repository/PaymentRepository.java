package com.jlas.auth.repository;

import com.jlas.auth.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository thao tác dữ liệu cho {@link Payment}.
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByUserId(Long userId);

    List<Payment> findTop20ByUser_IdOrderByCreatedAtDesc(Long userId);
}
