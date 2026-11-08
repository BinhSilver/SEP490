package com.jlas.learning.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "competency_level")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class CompetencyLevel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "level_code", nullable = false, unique = true, length = 50) private String levelCode;
    @Column(name = "level_order", nullable = false, unique = true) private int levelOrder;
    @Column(name = "display_name_vi", nullable = false) private String displayNameVi;
    @Column(name = "description_vi", columnDefinition = "TEXT") private String descriptionVi;
    @Column(name = "active", nullable = false) private boolean active;
}
