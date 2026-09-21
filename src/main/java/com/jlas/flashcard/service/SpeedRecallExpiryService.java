package com.jlas.flashcard.service;
import com.jlas.flashcard.repository.FlashcardSpeedRecallSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
@Component @EnableScheduling @RequiredArgsConstructor
public class SpeedRecallExpiryService {
    private final FlashcardSpeedRecallSessionRepository sessions;
    private final SpeedRecallService service;
    private final SpeedRecallClock clock;
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void expire() {
        try { sessions.expiredIds(clock.now(), PageRequest.of(0, 100)).forEach(service::expire); }
        catch (org.springframework.dao.DataAccessException e) {
            LoggerFactory.getLogger(getClass()).warn("Speed Recall expiry unavailable; verify migration or database connectivity.");
        }
    }
}
