package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.config.JpathProperties;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathUserSettings;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.repository.JpathUserSettingsRepository;
import com.jlas.jlptpath.service.JpathEntryRoute;
import com.jlas.jlptpath.service.JpathEntryRouter;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Every 5 minutes: walks the ACTIVE paths in id order (500 per page) and lets
 * {@link JpathReminderService#remind} decide, in its own transaction per learner, whether today's reminder is due.
 * Runs only when the path feature and the reminder job are both on; learners routed elsewhere are skipped. A learner
 * without a settings row gets the defaults (web on, email off, the configured default time). Failures are per learner.
 */
@Slf4j
@Component
public class JpathReminderJob {

    static final int PAGE_SIZE = 500;

    private final JpathProperties props;
    private final JpathEntryRouter router;
    private final JpathUserPathRepository paths;
    private final JpathUserSettingsRepository settings;
    private final JpathReminderService reminders;

    public JpathReminderJob(JpathProperties props, JpathEntryRouter router, JpathUserPathRepository paths,
            JpathUserSettingsRepository settings, JpathReminderService reminders) {
        this.props = props;
        this.router = router;
        this.paths = paths;
        this.settings = settings;
        this.reminders = reminders;
    }

    /** Returns how many reminders went out. */
    @Scheduled(initialDelay = 180_000, fixedDelay = 300_000)
    public int run() {
        if (!props.enabled() || !props.reminder().jobEnabled()) {
            return 0;
        }
        int sent = 0;
        long afterId = 0L;
        List<JpathUserPath> page;
        do {
            page = paths.findActiveAfter(afterId, PageRequest.of(0, PAGE_SIZE));
            for (JpathUserPath path : page) {
                if (remind(path)) {
                    sent++;
                }
            }
            if (!page.isEmpty()) {
                afterId = page.get(page.size() - 1).getId();
            }
        } while (page.size() == PAGE_SIZE);
        return sent;
    }

    private boolean remind(JpathUserPath path) {
        Long userId = path.getUserId();
        try {
            if (router.route(userId) != JpathEntryRoute.JPATH_PAGE) {
                return false;
            }
            JpathUserSettings s = settings.findById(userId).orElseGet(() -> JpathUserSettings.builder()
                    .userId(userId).reminderWeb(true).reminderEmail(false)
                    .reminderTime(props.reminder().defaultLocalTime()).build());
            return reminders.remind(path, s);
        } catch (RuntimeException e) {
            log.warn("Study reminder failed for user {}: {}", userId, e.toString());
            return false;
        }
    }
}
