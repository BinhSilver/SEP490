package com.jlas.jlptpath.dev;

import com.jlas.jlptpath.dto.JpathCardView;
import com.jlas.jlptpath.dto.JpathSessionView;
import com.jlas.jlptpath.dto.JpathStudyEventBatch;
import com.jlas.jlptpath.dto.JpathStudyEventInput;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathStudyAction;
import com.jlas.jlptpath.service.JpathStudyService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * [JLAS-W0108] Demo tools only: goes through the card steps of the caller's own study session by sending the same
 * events the study page sends (rate "Nhớ" on a new or review card, "Đã thuộc" on a retype card) through the real
 * {@link JpathStudyService#applyEvents}. It stops at the first check step: that check is then taken (or finished
 * with the check demo buttons) like any other, so the session never skips its checks.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mission", name = "dev-tools", havingValue = "true")
public class JpathStudyDemoService {

    /** A session never has this many cards; the loop stops here even if the queue keeps growing. */
    static final int MAX_STEPS = 300;

    public record Outcome(int cardsDone, String status, boolean atCheck, int position, int total) {
    }

    private final JpathStudyService study;

    public Outcome finishCards(Long userId, Long sessionId) {
        JpathCheckDemoService.guard(userId);
        JpathSessionView view = study.get(userId, sessionId); // NOT_FOUND for another learner's session
        int done = 0;
        while (done < MAX_STEPS && "OPEN".equals(view.status()) && view.current() != null) {
            JpathCardView card = view.current();
            JpathStudyEventInput event = event(card, view.position());
            if (event == null) {
                return new Outcome(done, view.status(), true, view.position(), view.total());
            }
            study.applyEvents(userId, sessionId, new JpathStudyEventBatch(List.of(event)));
            done++;
            view = study.get(userId, sessionId);
        }
        return new Outcome(done, view.status(), false, view.position(), view.total());
    }

    /** The event a learner who knows the card sends; null for a check step. */
    static JpathStudyEventInput event(JpathCardView card, int position) {
        JpathStepKind kind = card.kind();
        if (kind == null || kind == JpathStepKind.MICRO_CHECK || kind == JpathStepKind.GROUP_EXERCISE) {
            return null;
        }
        String id = "demo-" + UUID.randomUUID().toString().substring(0, 30);
        JpathRating rating = card.itemType() == JpathItemType.GRAMMAR ? null : JpathRating.GOOD;
        JpathStudyAction action = switch (kind) {
            case NEW -> JpathStudyAction.FIRST_RATE;
            case REVIEW -> JpathStudyAction.REVIEW_RATE;
            default -> JpathStudyAction.MARK_KNOWN;
        };
        return new JpathStudyEventInput(id, card.itemType(), card.code(), action,
                action == JpathStudyAction.MARK_KNOWN ? null : rating, null, 6000L, position);
    }
}
