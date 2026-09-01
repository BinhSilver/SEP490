package com.jlas.auth.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Vai trò hệ thống, ví dụ ADMIN hoặc LEARNER.
 * Table: roles
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã vai trò duy nhất. */
    @Column(name = "name", nullable = false, length = 50, unique = true)
    private String name;

    /** Mô tả quyền của vai trò. */
    @Column(name = "description", length = 255)
    private String description;
}
