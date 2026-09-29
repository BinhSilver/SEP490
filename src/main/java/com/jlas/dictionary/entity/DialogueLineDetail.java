package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Thông tin bổ sung cho một lời thoại (content_item LISTENING): vai diễn, cảnh, vị trí bong bóng.
 * Khoá chính dùng chung với content_item (quan hệ 1-1).
 * Table: dialogue_line_detail
 */
@Entity
@Table(name = "dialogue_line_detail")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DialogueLineDetail {

    @Id
    @Column(name = "content_item_id")
    private Long contentItemId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "content_item_id",
            foreignKey = @ForeignKey(name = "fk_dialogue_detail_item"))
    private ContentItem contentItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dialogue_role_id",
            foreignKey = @ForeignKey(name = "fk_dialogue_detail_role"))
    private DialogueRole role;

    /** Mã cảnh trong bài. */
    @Column(name = "scene_code", length = 64)
    private String sceneCode;

    /** trai/phai. */
    @Column(name = "bubble_side", length = 10)
    private String bubbleSide;

    @Column(name = "bubble_x")
    private Short bubbleX;

    @Column(name = "bubble_y")
    private Short bubbleY;
}
