package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * [JLAS-W0108] Display settings of a learner (jpath_user_settings). quizTimerOn is the vocabulary/kanji quiz timer
 * (off by default), grammarTimerOn the grammar timer (on by default).
 */
@Entity
@Table(name = "jpath_user_settings")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathUserSettings {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "furigana_hidden_all", nullable = false)
    private boolean furiganaHiddenAll;

    /** AUTO, ON or OFF. */
    @Builder.Default
    @Column(name = "romaji_mode", nullable = false, length = 4)
    private String romajiMode = "AUTO";

    /** S, M or L. */
    @Builder.Default
    @Column(name = "font_size", nullable = false, length = 1)
    private String fontSize = "M";

    @Builder.Default
    @Column(name = "sound_on", nullable = false)
    private boolean soundOn = true;

    @Builder.Default
    @Column(name = "autoplay_audio", nullable = false)
    private boolean autoplayAudio = true;

    @Column(name = "quiz_timer_on", nullable = false)
    private boolean quizTimerOn;

    @Builder.Default
    @Column(name = "grammar_timer_on", nullable = false)
    private boolean grammarTimerOn = true;

    @Builder.Default
    @Column(name = "reminder_web", nullable = false)
    private boolean reminderWeb = true;

    @Column(name = "reminder_email", nullable = false)
    private boolean reminderEmail;

    @Builder.Default
    @Column(name = "reminder_time", nullable = false)
    private LocalTime reminderTime = LocalTime.of(20, 0);

    /** TOPICS or FLAT. */
    @Builder.Default
    @Column(name = "vocab_list_view", nullable = false, length = 6)
    private String vocabListView = "TOPICS";

    @Column(name = "tour_done_at")
    private LocalDateTime tourDoneAt;

    @Column(name = "swipe_hint_done_at")
    private LocalDateTime swipeHintDoneAt;

    @Column(name = "team_notebook_id")
    private Long teamNotebookId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
