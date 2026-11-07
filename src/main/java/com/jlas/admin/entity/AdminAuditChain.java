package com.jlas.admin.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * [JLAS2-51] Single-row head of the audit hash chain: the id and hash of the last hashed audit row.
 * Locked for every audit write, so rows are chained one at a time; compared with the last row to
 * detect a deleted tail.
 */
@Entity
@Table(name = "admin_audit_chain")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditChain {
    public static final byte HEAD_ID = 1;

    @Id
    private Byte id;
    @Column(name = "last_log_id")
    private Long lastLogId;
    @Column(name = "last_hash", length = 64)
    private String lastHash;

    public static AdminAuditChain head() {
        AdminAuditChain chain = new AdminAuditChain();
        chain.id = HEAD_ID;
        return chain;
    }
}
