package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh sách chuyên ngành phẳng: GENERAL, IT, MEDICAL, ELECTRICAL...
 * Table: dictionary_domain
 */
@Entity
@Table(name = "dictionary_domain")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictionaryDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 80, unique = true)
    private String code;

    @Column(name = "name_vi", nullable = false, length = 150)
    private String nameVi;

    @Column(name = "name_en", length = 150)
    private String nameEn;

    @Column(name = "name_ja", length = 150)
    private String nameJa;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;
}
