package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathReminderLog;
import com.jlas.jlptpath.entity.JpathReminderLogId;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Study reminders sent. */
@Repository
public interface JpathReminderLogRepository extends JpaRepository<JpathReminderLog, JpathReminderLogId> {

    /** Whether the learner was already reminded on {@code reminderDay} (the learner's date). */
    boolean existsByUserIdAndReminderDay(Long userId, LocalDate reminderDay);
}
