package com.jlas.conjugation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "verb_practice_catalog_verb")
@Getter @Setter @NoArgsConstructor
public class VerbPracticeCatalogVerb {
    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 80)
    private String word;

    @Column(nullable = false, length = 100)
    private String reading;

    @Column(nullable = false, length = 240)
    private String meaning;

    @Column(name = "verb_group", nullable = false)
    private int groupNumber;

    @Column(name = "level_code", nullable = false, length = 2)
    private String level;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
