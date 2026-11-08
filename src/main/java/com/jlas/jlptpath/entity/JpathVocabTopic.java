package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [JLAS-W0108] Vocabulary topic of a level (jpath_vocab_topic); 'khac' = "Từ thông dụng khác". */
@Entity
@Table(name = "jpath_vocab_topic")
@IdClass(JpathVocabTopicId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathVocabTopic {

    @Id
    @Column(name = "level", length = 2)
    private String level;

    @Id
    @Column(name = "topic_key", length = 60)
    private String topicKey;

    @Column(name = "title_vi", nullable = false, length = 120)
    private String titleVi;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
