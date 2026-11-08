package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathContentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Grammar pattern of the path (jpath_grammar). */
@Entity
@Table(name = "jpath_grammar")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathGrammar {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "unit_no", nullable = false)
    private Integer unitNo;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    @Column(name = "pattern", nullable = false, length = 120)
    private String pattern;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pattern_variants_json", columnDefinition = "json")
    private String patternVariantsJson;

    @Column(name = "family_key", nullable = false, length = 120)
    private String familyKey;

    @Column(name = "meaning_vi", length = 1000)
    private String meaningVi;

    @Column(name = "formation", length = 255)
    private String formation;

    @Column(name = "usage_vi", columnDefinition = "text")
    private String usageVi;

    @Column(name = "nuance_vi", columnDefinition = "text")
    private String nuanceVi;

    @Column(name = "common_mistakes_vi", columnDefinition = "text")
    private String commonMistakesVi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "related_codes_json", columnDefinition = "json")
    private String relatedCodesJson;

    /** Secondary pattern: code of the main pattern it is attached to (never learned on its own); null = main. */
    @Column(name = "parent_code", length = 24)
    private String parentCode;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private JpathContentStatus status = JpathContentStatus.DRAFT;

    @Column(name = "report_hidden", nullable = false)
    private boolean reportHidden;

    @Column(name = "authored", nullable = false)
    private boolean authored;

    @Column(name = "seed_version", length = 16)
    private String seedVersion;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
