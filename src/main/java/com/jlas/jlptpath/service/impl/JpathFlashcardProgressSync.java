package com.jlas.jlptpath.service.impl;

import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStudyAction;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.util.JpathSrsPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Owner 24/11 Q1: a path's Albazzz flashcard results count in the path's progress. Every card of the
 * learner's JLPT part sets graded since the last sync becomes one path rating of its item: quality 3–5 (✓ "Nhớ" is
 * stored as 5) = remembered, 0–2 (✗ "Chưa nhớ" is stored as 1) = not remembered. An item never learned starts
 * learning with that rating, so a part studied with flashcards counts as studied; an item already learned is rated
 * like a review, and the forget counter (3 different days → Sổ tay) works as for any other rating. KNOWN items stay
 * as they are. The quick check of the part is still the path's own.
 */
@Component
public class JpathFlashcardProgressSync {

    static final String APPLIED_DDL = "CREATE TABLE IF NOT EXISTS learning_path_flashcard_applied ("
            + " user_id BIGINT NOT NULL, card_id BIGINT NOT NULL, reviewed_at DATETIME(6) NOT NULL,"
            + " PRIMARY KEY (user_id, card_id))";

    /** One graded card not applied yet. */
    record Review(long cardId, String front, int quality, LocalDateTime reviewedAt) {
    }

    private final JdbcTemplate db;
    private final JpathItemStateMachine machine;
    private final JpathUserItemRepository items;
    private final JpathStudyEventRepository events;
    private final JpathUserPathRepository paths;
    private final JpathClock clock;
    private volatile boolean tableReady;

    public JpathFlashcardProgressSync(JdbcTemplate db, JpathItemStateMachine machine, JpathUserItemRepository items,
            JpathStudyEventRepository events, JpathUserPathRepository paths, JpathClock clock) {
        this.db = db;
        this.machine = machine;
        this.items = items;
        this.events = events;
        this.paths = paths;
        this.clock = clock;
    }

    /** Q1: 3–5 remembered, 0–2 not; a first study uses the new-card ratings, later ones the review ratings. */
    static JpathRating rating(int quality, boolean firstStudy) {
        boolean remembered = quality >= 3;
        if (firstStudy) return remembered ? JpathRating.REMEMBERED : JpathRating.FORGOT;
        return remembered ? JpathRating.GOOD : JpathRating.FORGOT;
    }

    /** True when the item has not been studied on the path yet (no row, or a row never learned and not known). */
    static boolean firstStudy(JpathUserItem i) {
        return i == null || (i.getFirstLearnedAt() == null && i.getState() != JpathItemState.KNOWN);
    }

    /**
     * Applies the new flashcard grades of one tab. {@code codeByFront}: card front → item code (the cards are made from
     * the items' word, kanji, pattern or kana). Returns how many cards changed the path. Runs in the caller's
     * transaction.
     */
    public int apply(Long userId, String tab, JpathItemType type, String level, Map<String, String> codeByFront) {
        ready();
        List<Review> reviews = db.query("SELECT c.id, c.front, p.last_quality, p.last_reviewed_at"
                        + " FROM learning_path_flashcard_set m JOIN flashcard_set s ON s.id = m.set_id AND s.deleted_at IS NULL"
                        + " JOIN flashcard_card c ON c.set_id = m.set_id"
                        + " JOIN flashcard_card_progress p ON p.card_id = c.id AND p.user_id = m.user_id"
                        + " LEFT JOIN learning_path_flashcard_applied a ON a.user_id = m.user_id AND a.card_id = c.id"
                        + " WHERE m.user_id = ? AND m.path_kind = 'JLPT' AND m.tab = ?"
                        + " AND p.last_reviewed_at IS NOT NULL AND p.last_quality IS NOT NULL"
                        + " AND (a.reviewed_at IS NULL OR p.last_reviewed_at > a.reviewed_at)"
                        + " ORDER BY p.last_reviewed_at",
                (rs, n) -> new Review(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getTimestamp(4).toLocalDateTime()),
                userId, tab);
        if (reviews.isEmpty()) return 0;
        if (paths.findActiveForUpdate(userId).isEmpty()) return 0;
        LocalDate day = clock.today(userId);
        int changed = 0;
        for (Review r : reviews) {
            String code = r.front() == null ? null : codeByFront.get(r.front());
            if (code != null && applyOne(userId, type, code, level, r, day)) changed++;
            db.update("INSERT INTO learning_path_flashcard_applied (user_id, card_id, reviewed_at) VALUES (?,?,?)"
                    + " ON DUPLICATE KEY UPDATE reviewed_at = VALUES(reviewed_at)", userId, r.cardId(), r.reviewedAt());
        }
        return changed;
    }

    private boolean applyOne(Long userId, JpathItemType type, String code, String level, Review r, LocalDate day) {
        JpathUserItem i = items.findByUserIdAndItemTypeAndItemCode(userId, type, code).orElse(null);
        if (i != null && i.getState() == JpathItemState.KNOWN) return false;
        boolean first = firstStudy(i);
        JpathRating rating = rating(r.quality(), first);
        if (first) {
            // grammar is started without an SRS rating, as on the path's own first study
            machine.startLearning(userId, type, code, level, type == JpathItemType.GRAMMAR ? null : rating);
        } else {
            machine.rate(i, rating);
        }
        events.save(JpathStudyEvent.builder().userId(userId).sessionId(null)
                .clientEventId("fc-" + r.cardId() + "-" + r.reviewedAt().toEpochSecond(ZoneOffset.UTC))
                .itemType(type).itemCode(code)
                .action((first ? JpathStudyAction.FIRST_RATE : JpathStudyAction.REVIEW_RATE).name())
                .rating(rating).quality(JpathSrsPolicy.quality(rating)).correct(rating != JpathRating.FORGOT)
                .studyDay(day).build());
        return true;
    }

    private void ready() {
        if (!tableReady) {
            db.execute(APPLIED_DDL);
            tableReady = true;
        }
    }
}
