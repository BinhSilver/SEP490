package com.jlas.jlptpath.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** [JLAS-W0108] A study reminder already sent (jpath_reminder_log); reminderDay is the learner's date. */
@Entity
@Table(name = "jpath_reminder_log")
@IdClass(JpathReminderLogId.class)
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathReminderLog {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "reminder_day")
    private LocalDate reminderDay;

    /** Server time; filled on insert when not set. */
    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    /** CSV of channels, e.g. "WEB,EMAIL". */
    @Column(name = "channels", nullable = false, length = 20)
    private String channels;

    @PrePersist
    void fillSentAt() {
        if (sentAt == null) {
            sentAt = LocalDateTime.now();
        }
    }
}
