package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.flashcard.util.SrsUtil;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathRetypeVerdict;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathNotebookChangedEvent;
import com.jlas.jlptpath.util.JpathSrsPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] State changes of one learner item (LEARNING / NOTEBOOK / KNOWN) and its SRS schedule. Every method
 * takes an entity the caller already loaded (callers that write path data take
 * {@code JpathUserPathRepository.findActiveForUpdate} first) and saves it. Times are the learner's local time.
 * GRAMMAR items never enter the notebook; only VOCAB rows linked to a dictionary entry publish
 * {@link JpathNotebookChangedEvent}.
 */
@Component
public class JpathItemStateMachine {

    static final int FORGETS_TO_NOTEBOOK = 3;
    static final int NOTEBOOK_HITS_TO_LEAVE = 3;
    static final int HARD_FORGETS = 5;
    static final int RETYPE_STREAK = 2;
    static final String TOPIC_PENDING = "TOPIC_PEND";
    static final String KNOWN_BY_USER = "USER";

    private final JpathClock clock;
    private final JpathUserItemRepository items;
    private final JpathStudyEventRepository events;
    private final JpathVocabularyRepository vocabulary;
    private final ApplicationEventPublisher publisher;

    public JpathItemStateMachine(JpathClock clock, JpathUserItemRepository items, JpathStudyEventRepository events,
                                 JpathVocabularyRepository vocabulary, ApplicationEventPublisher publisher) {
        this.clock = clock;
        this.items = items;
        this.events = events;
        this.vocabulary = vocabulary;
        this.publisher = publisher;
    }

    /**
     * First study of an item: LEARNING, {@code first_learned_at} = now, fresh SRS state scheduled by
     * {@code firstRating}. A {@code null} rating (grammar) leaves rep 0 and no next review.
     */
    public JpathUserItem startLearning(Long userId, JpathItemType t, String code, String level,
                                       JpathRating firstRating) {
        JpathUserItem i = items.findByUserIdAndItemTypeAndItemCode(userId, t, code)
                .orElseGet(() -> newRow(userId, t, code, level));
        i.setState(JpathItemState.LEARNING);
        i.setFirstLearnedAt(clock.localNow(userId));
        i.setRepetitions(0);
        i.setIntervalDays(0);
        i.setEasiness(SrsUtil.DEFAULT_EASINESS);
        i.setNextReviewAt(null);
        if (firstRating == null) {
            return items.save(i);
        }
        rate(i, firstRating);
        return i;
    }

    /** Existing row, or a new LEARNING row (rep 0, due at the start of today, never learned). */
    public JpathUserItem ensureRow(Long userId, JpathItemType t, String code, String level) {
        return items.findByUserIdAndItemTypeAndItemCode(userId, t, code).orElseGet(() -> {
            JpathUserItem i = newRow(userId, t, code, level);
            i.setNextReviewAt(startOfToday(userId));
            return items.save(i);
        });
    }

    /** One self-rating: SRS, streak, forget counter (once per day), notebook hits and the topic-pending rule. */
    public void rate(JpathUserItem i, JpathRating r) {
        Long userId = i.getUserId();
        LocalDateTime now = clock.localNow(userId);
        LocalDate today = clock.today(userId);
        int q = JpathSrsPolicy.quality(r);
        boolean pass = q >= SrsUtil.PASS_QUALITY;

        SrsUtil.Schedule s = JpathSrsPolicy.apply(
                SrsUtil.State.of(i.getRepetitions(), i.getIntervalDays(), i.getEasiness(), i.getNextReviewAt()),
                r, now);
        i.setRepetitions(s.repetitions());
        i.setIntervalDays(s.intervalDays());
        i.setEasiness(s.easiness());
        i.setNextReviewAt(s.nextReviewAt());
        i.setLastReviewedAt(now);
        i.setCorrectStreak(pass ? i.getCorrectStreak() + 1 : 0);

        if (TOPIC_PENDING.equals(i.getKnownSource())) {
            i.setKnownSource(null);
            if (pass) {
                count(i);
            }
        }

        boolean toNotebook = false;
        if (r == JpathRating.FORGOT
                && !events.existsForgotToday(userId, i.getItemType(), i.getItemCode(), today)) {
            i.setForgetCount(i.getForgetCount() + 1);
            toNotebook = i.getForgetCount() == FORGETS_TO_NOTEBOOK && notebookType(i)
                    && i.getState() == JpathItemState.LEARNING;
        }

        if (i.getState() == JpathItemState.NOTEBOOK) {
            if (!pass) {
                i.setNotebookHits(0);
            } else if (!today.equals(i.getNotebookLastHitDay())) {
                i.setNotebookHits(i.getNotebookHits() + 1);
                i.setNotebookLastHitDay(today);
                if (i.getNotebookHits() >= NOTEBOOK_HITS_TO_LEAVE) {
                    leaveNotebook(i);
                    return;
                }
            }
        }
        if (toNotebook) {
            toNotebook(i, JpathNotebookSource.FORGOT_3);
            return;
        }
        items.save(i);
    }

    /** Retype result: EXACT → GOOD, MINOR → HARD, WRONG → FORGOT, then the streak restarts. */
    public void retyped(JpathUserItem i, JpathRetypeVerdict v) {
        rate(i, switch (v) {
            case EXACT -> JpathRating.GOOD;
            case MINOR -> JpathRating.HARD;
            case WRONG -> JpathRating.FORGOT;
        });
        i.setCorrectStreak(0);
        items.save(i);
    }

    /**
     * Wrong answer in a check or quiz. GRAMMAR (any state) goes back to LEARNING, rated FORGOT and due today, never
     * the notebook. VOCAB/KANJI: KNOWN → NOTEBOOK (KNOWN_WRONG), LEARNING → NOTEBOOK ({@code src}), NOTEBOOK → hits 0.
     */
    public void wrongInCheck(JpathUserItem i, JpathNotebookSource src) {
        if (!notebookType(i)) {
            if (i.getState() == JpathItemState.KNOWN) {
                i.setState(JpathItemState.LEARNING);
                i.setKnownAt(null);
                i.setKnownSource(null);
            }
            rate(i, JpathRating.FORGOT);
            i.setNextReviewAt(startOfToday(i.getUserId()));
            items.save(i);
            return;
        }
        switch (i.getState()) {
            case KNOWN -> toNotebook(i, JpathNotebookSource.KNOWN_WRONG);
            case LEARNING -> toNotebook(i, src);
            case NOTEBOOK -> {
                i.setNotebookHits(0);
                items.save(i);
            }
        }
    }

    /**
     * Puts a VOCAB/KANJI item in the notebook, due no later than the start of today. Owner 20/11: a GRAMMAR pattern
     * only enters it from a Smart Test import (source SMART_TEST); it then leaves after the usual hits.
     */
    public void toNotebook(JpathUserItem i, JpathNotebookSource src) {
        if (!notebookType(i) && !(i.getItemType() == JpathItemType.GRAMMAR && src == JpathNotebookSource.SMART_TEST)) {
            throw new ApiException(ErrorCode.JPATH_GRAMMAR_NO_NOTEBOOK);
        }
        Long userId = i.getUserId();
        boolean wasIn = i.getState() == JpathItemState.NOTEBOOK;
        LocalDateTime startOfToday = startOfToday(userId);
        i.setState(JpathItemState.NOTEBOOK);
        i.setNotebookSource(src);
        i.setNotebookSince(clock.localNow(userId));
        i.setNotebookHits(0);
        i.setNotebookLastHitDay(null);
        i.setKnownAt(null);
        i.setKnownSource(null);
        if (i.getNextReviewAt() == null || i.getNextReviewAt().isAfter(startOfToday)) {
            i.setNextReviewAt(startOfToday);
        }
        items.save(i);
        if (!wasIn) {
            publish(i, true);
        }
    }

    /** Leaves the notebook back to LEARNING (3 hits, or removed by the learner) and clears the notebook fields. */
    public void leaveNotebook(JpathUserItem i) {
        boolean wasIn = i.getState() == JpathItemState.NOTEBOOK;
        i.setState(JpathItemState.LEARNING);
        clearNotebook(i);
        items.save(i);
        if (wasIn) {
            publish(i, false);
        }
    }

    /** KNOWN now; leaves the notebook; "USER" on an item learned before also counts it. */
    public void markKnown(JpathUserItem i, String knownSource) {
        boolean wasIn = i.getState() == JpathItemState.NOTEBOOK;
        i.setState(JpathItemState.KNOWN);
        i.setKnownAt(clock.localNow(i.getUserId()));
        i.setKnownSource(knownSource);
        clearNotebook(i);
        if (KNOWN_BY_USER.equals(knownSource) && i.getFirstLearnedAt() != null) {
            count(i);
        }
        items.save(i);
        if (wasIn) {
            publish(i, false);
        }
    }

    /** Counts the item towards progress once ({@code counted_at} is kept when already set). */
    public void count(JpathUserItem i) {
        if (i.getCountedAt() == null) {
            i.setCountedAt(clock.localNow(i.getUserId()));
        }
        items.save(i);
    }

    public boolean hard(JpathUserItem i) {
        return i.getForgetCount() >= HARD_FORGETS;
    }

    public boolean retypeDue(JpathUserItem i) {
        return i.getItemType() == JpathItemType.VOCAB && (i.getCorrectStreak() >= RETYPE_STREAK || hard(i));
    }

    private JpathUserItem newRow(Long userId, JpathItemType t, String code, String level) {
        return JpathUserItem.builder().userId(userId).itemType(t).itemCode(code).level(level)
                .state(JpathItemState.LEARNING).build();
    }

    private LocalDateTime startOfToday(Long userId) {
        return clock.today(userId).atStartOfDay();
    }

    private static boolean notebookType(JpathUserItem i) {
        return i.getItemType() == JpathItemType.VOCAB || i.getItemType() == JpathItemType.KANJI;
    }

    private static void clearNotebook(JpathUserItem i) {
        i.setNotebookSource(null);
        i.setNotebookSince(null);
        i.setNotebookHits(0);
        i.setNotebookLastHitDay(null);
    }

    private void publish(JpathUserItem i, boolean added) {
        if (i.getItemType() != JpathItemType.VOCAB) {
            return;
        }
        Long entryId = vocabulary.findById(i.getItemCode()).map(JpathVocabulary::getDictionaryEntryId).orElse(null);
        if (entryId != null) {
            publisher.publishEvent(new JpathNotebookChangedEvent(i.getUserId(), entryId, added));
        }
    }
}
