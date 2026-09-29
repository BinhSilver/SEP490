package com.jlas.mission.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the "use a freeze?" notification to learners who have not opened the app since missing a day.
 * Learners who do open it get the same offer from {@link StreakService#refresh} directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreakReminderJob {

    private static final int BATCH_SIZE = 500;

    private final StreakService streaks;

    @Scheduled(initialDelay = 120_000, fixedDelayString = "${mission.freeze-reminder-delay-ms:1800000}")
    public void remind() {
        int checked = streaks.remindAtRiskStreaks(BATCH_SIZE);
        if (checked > 0) {
            log.info("Checked {} streaks for missed days", checked);
        }
    }
}
