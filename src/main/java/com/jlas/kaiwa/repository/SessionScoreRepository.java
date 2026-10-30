package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.ConversationSession;
import com.jlas.kaiwa.model.SessionScore;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Điểm cuối phiên ghi vào các cột sẵn có của conversation_session
 * (overall_score, score_details_json, mistakes_count, suggestions, scored_at), không dùng bảng riêng.
 */
@Repository
@RequiredArgsConstructor
public class SessionScoreRepository {

    private final ConversationSessionRepository sessions;

    public Optional<SessionScore> findBySessionId(Long sessionId) {
        return sessions.findById(sessionId)
                .filter(session -> session.getScoredAt() != null)
                .map(SessionScoreRepository::toScore);
    }

    public SessionScore save(SessionScore score) {
        // Ghi lên chính đối tượng phiên của luồng chat, để lần lưu phiên sau đó không ghi đè mất điểm.
        ConversationSession session = score.getSession();
        session.setOverallScore(score.getOverallScore());
        session.setScoreDetailsJson(score.getDetailsJson());
        session.setMistakesCount(score.getMistakesCount() == null ? 0 : score.getMistakesCount());
        session.setSuggestions(score.getSuggestions());
        session.setScoredAt(LocalDateTime.now());
        sessions.save(session);
        return score;
    }

    private static SessionScore toScore(ConversationSession session) {
        SessionScore score = new SessionScore();
        score.setId(session.getId());
        score.setSession(session);
        score.setScenario(session.getScenario());
        score.setOverallScore(session.getOverallScore());
        score.setDetailsJson(session.getScoreDetailsJson());
        score.setMistakesCount(session.getMistakesCount());
        score.setSuggestions(session.getSuggestions());
        return score;
    }
}
