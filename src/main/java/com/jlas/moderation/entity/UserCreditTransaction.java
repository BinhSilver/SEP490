package com.jlas.moderation.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Ledger credit; users.credit_balance là số dư hiện tại.
 * Table: user_credit_transaction
 */
@Entity
@Table(name = "user_credit_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_credit_transaction_user"))
    private User user;

    /** Số dương là cộng, số âm là trừ. */
    @Column(name = "amount", nullable = false)
    private Integer amount;

    @Column(name = "reason", nullable = false, length = 30)
    private String reason;

    @Column(name = "reference_type", length = 30)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "note", length = 200)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
