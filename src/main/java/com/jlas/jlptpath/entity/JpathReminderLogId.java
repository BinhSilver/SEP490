package com.jlas.jlptpath.entity;

import java.io.Serializable;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathReminderLog}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathReminderLogId implements Serializable {
    private Long userId;
    private LocalDate reminderDay;
}
