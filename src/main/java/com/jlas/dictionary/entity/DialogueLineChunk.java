package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Cụm tách nghĩa của một lời thoại; meaning_vi NULL với dấu câu.
 * Table: dialogue_line_chunk
 */
@Entity
@Table(name = "dialogue_line_chunk")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DialogueLineChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_item_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_dialogue_chunk_item"))
    private ContentItem contentItem;

    /** Thứ tự cụm trong câu, bắt đầu từ 1; không default để DB chặn sớm khi quên set. */
    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    /** Cụm tiếng Nhật. */
    @Column(name = "surface", nullable = false, length = 255)
    private String surface;

    @Column(name = "meaning_vi", length = 512)
    private String meaningVi;
}
