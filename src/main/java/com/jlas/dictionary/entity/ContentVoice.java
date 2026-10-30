package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Giọng đọc dùng cho audio nội dung (6 giọng: chuan-nu, chuan-nam, nam-tre, nu-tre, ong-cu, tinh-nghich).
 * Table: content_voice
 */
@Entity
@Table(name = "content_voice")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContentVoice {

    /** Mã giọng, đồng thời là khoá chính (không tự sinh). */
    @Id
    @Column(name = "code", nullable = false, length = 32)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** MALE/FEMALE. */
    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;
}
