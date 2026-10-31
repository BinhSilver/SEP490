package com.jlas.kaiwa.service;

import com.jlas.kaiwa.model.ConversationSession;
import com.jlas.kaiwa.model.SessionStatus;
import com.jlas.kaiwa.repository.ConversationSessionRepository;
import com.jlas.kaiwa.service.ai.AIConversationService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI Tán Gẫu keeps one conversation per day: opening it again the same day continues that session.
 * A new day starts a new one, unless the learner is still chatting across midnight (a message in the
 * last 30 minutes). Earlier days carry over through the AI's long-term memory, not the transcript.
 */
@Service
public class FreeChatSessionService {
    private static final Logger log = LoggerFactory.getLogger(FreeChatSessionService.class);
    static final Duration ONLINE = Duration.ofMinutes(30);

    private final AIConversationService conversationService;
    private final FreeChatScenarioService scenarios;
    private final ConversationSessionRepository sessions;

    public FreeChatSessionService(AIConversationService conversationService, FreeChatScenarioService scenarios,
                                  ConversationSessionRepository sessions) {
        this.conversationService = conversationService;
        this.scenarios = scenarios;
        this.sessions = sessions;
    }

    /** Today's session: the one still current, else a new one after closing those left from earlier days. */
    public ConversationSession open(long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<ConversationSession> open = sessions
                .findByUserIdAndScenario_IdOrderByStartedAtDescIdDesc(userId, scenarios.ensureFreeChatScenario().getId())
                .stream().filter(s -> s.getStatus() == SessionStatus.IN_PROGRESS).toList();
        ConversationSession today = open.stream().filter(s -> current(s, now)).findFirst().orElse(null);
        for (ConversationSession earlier : open) {
            if (current(earlier, now)) continue;
            // Closing writes the session's memory summary; each closes on its own, so one that fails
            // (its last reply still being written) does not keep the learner out of today's chat.
            try { conversationService.endSession(earlier.getId()); }
            catch (RuntimeException e) { log.warn("Free chat {} not closed ({})", earlier.getId(), e.getClass().getSimpleName()); }
        }
        return today != null ? today : conversationService.startFreeChat(userId);
    }

    /** True while a session is still today's: started today, or the learner wrote in the last 30 minutes. */
    public static boolean current(ConversationSession session, LocalDateTime now) {
        LocalDateTime last = session.getLastActivityAt() != null ? session.getLastActivityAt() : session.getStartedAt();
        return session.getStartedAt().toLocalDate().equals(now.toLocalDate()) || last.isAfter(now.minus(ONLINE));
    }
}
