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
@Table(name = "verb_practice_catalog_context")
@Getter @Setter @NoArgsConstructor
public class VerbPracticeCatalogContext {
    @Id
    @Column(length = 40)
    private String id;

    @Column(name = "verb_id", nullable = false, length = 40)
    private String verbId;

    @Column(name = "form_code", nullable = false, length = 30)
    private String formCode;

    @Column(nullable = false, length = 500)
    private String sentence;

    @Column(nullable = false, length = 500)
    private String translation;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;
}
