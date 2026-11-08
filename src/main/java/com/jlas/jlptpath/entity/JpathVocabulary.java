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

/** [JLAS-W0108] Path vocabulary word (jpath_vocabulary). dictionaryEntryId is a soft link, no FK. */
@Entity
@Table(name = "jpath_vocabulary")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathVocabulary {

    @Id
    @Column(name = "code", length = 24)
    private String code;

    @Column(name = "level", nullable = false, length = 2)
    private String level;

    @Column(name = "topic_key", nullable = false, length = 60)
    private String topicKey;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "priority", nullable = false)
    private Integer priority;

    /** CORE or EXTENDED. */
    @Builder.Default
    @Column(name = "tier", nullable = false, length = 10)
    private String tier = "CORE";

    @Column(name = "expression", nullable = false, length = 120)
    private String expression;

    @Column(name = "reading", nullable = false, length = 120)
    private String reading;

    @Column(name = "normalized_expression", nullable = false, length = 120)
    private String normalizedExpression;

    @Column(name = "normalized_reading", nullable = false, length = 120)
    private String normalizedReading;

    @Column(name = "romaji", length = 160)
    private String romaji;

    @Column(name = "kana_only", nullable = false)
    private boolean kanaOnly;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variants_json", columnDefinition = "json")
    private String variantsJson;

    @Column(name = "meaning_vi", length = 255)
    private String meaningVi;

    @Column(name = "word_type_label", length = 40)
    private String wordTypeLabel;

    @Column(name = "mnemonic_vi", length = 500)
    private String mnemonicVi;

    @Column(name = "dictionary_entry_id")
    private Long dictionaryEntryId;

    @Column(name = "audio_path", length = 255)
    private String audioPath;

    @Column(name = "audio_sha256", columnDefinition = "char(64)")
    private String audioSha256;

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
