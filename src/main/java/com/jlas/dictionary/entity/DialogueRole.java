package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Vai diễn trong hội thoại của một lesson.
 * Table: dialogue_role
 */
@Entity
@Table(name = "dialogue_role")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DialogueRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_dialogue_role_lesson"))
    private Lesson lesson;

    /** Mã vai, duy nhất trong phạm vi bài. */
    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name_vi", nullable = false, length = 255)
    private String nameVi;

    @Column(name = "name_ja", length = 255)
    private String nameJa;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Giọng mặc định của vai. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voice_code",
            foreignKey = @ForeignKey(name = "fk_dialogue_role_voice"))
    private ContentVoice voice;
}
