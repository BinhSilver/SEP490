package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathEventOutcome;
import com.jlas.jlptpath.dto.JpathStudyEventBatch;
import com.jlas.jlptpath.dto.JpathStudyEventInput;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathStudyEvent;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.entity.JpathVocabulary;
import com.jlas.jlptpath.enums.JpathItemState;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathRetypeVerdict;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathStudyAction;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathStudyEventRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.util.JpathKanaText;
import com.jlas.jlptpath.util.JpathQueueBuilder;
import com.jlas.jlptpath.util.JpathQueueStep;
import com.jlas.jlptpath.util.JpathSrsPolicy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Applies the card buttons of a study session (plan Task 35): memory buttons, retype, mark known, skip.
 * Runs inside the caller's writer transaction, after {@link JpathDailyPlanService#ensurePlanExists} ran outside it.
 *
 * <p>Locks: path {@code findActiveForUpdate} (first code that loads the path) → {@link JpathDailyPlanService#lockToday}
 * (level, then plan) → tabs {@code findForUpdateByPlanId} → the session row ({@code (id, userId)}, plan decision 43).
 * Queue and position are written only under these locks.
 *
 * <p>Per event, in input order: (1) client event id already recorded → DUPLICATE; (2) session not OPEN, step at
 * {@code position} not the current one or another item, action not allowed on the step kind → REJECTED; (3) the
 * state change through {@link JpathItemStateMachine}, then the day counters; (4) the event row (after the rating, so
 * {@code existsForgotToday} keeps the forget counter once per day), position and completion. Every event batch
 * with an applied event ends with {@link JpathDayCompletion#evaluate}.
 *
 * <p>Counters: FIRST_RATE (not a repeat) in a TODAY round of today's plan on an item of the open level →
 * {@code new_done + 1}; in an EXTRA round → {@code extra_done + 1}. KANA cards never count (plan Decision 27: only a
 * passed KANA_ROW check counts the kana tab). REVIEW_RATE / RETYPE (not a repeat) of a card that was due →
 * {@code review_done + 1} on the step's tab of today's plan.
 */
@Component
public class JpathStudyEventApplier {

    static final String APPLIED = "APPLIED";
    static final String DUPLICATE = "DUPLICATE";
    static final String REJECTED = "REJECTED";
    static final String SESSION_CLOSED = "SESSION_CLOSED";
    static final String STEP_MISMATCH = "STEP_MISMATCH";
    static final String ACTION_MISMATCH = "ACTION_MISMATCH";
    static final String RATING_INVALID = "RATING_INVALID";
    static final String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";
    static final String NOT_READY = "NOT_READY";
    /** Repeats of one card queued at most (same rule as the check effects). */
    static final int MAX_REPEATS = 2;

    private static final String OPEN = "OPEN";
    private static final String COMPLETED = "COMPLETED";
    private static final String KNOWN_BY_USER = "USER";
    private static final Set<JpathRating> FIRST_RATINGS = EnumSet.of(JpathRating.REMEMBERED, JpathRating.FORGOT);
    private static final Set<JpathRating> REVIEW_RATINGS =
            EnumSet.of(JpathRating.FORGOT, JpathRating.HARD, JpathRating.GOOD, JpathRating.EASY);

    private final JpathUserPathRepository paths;
    private final JpathStudySessionRepository sessions;
    private final JpathDailyPlanService dailyPlans;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathUserItemRepository userItems;
    private final JpathStudyEventRepository events;
    private final JpathContentReader content;
    private final JpathItemStateMachine states;
    private final JpathDayCompletion completion;
    private final JpathClock clock;

    public JpathStudyEventApplier(JpathUserPathRepository paths, JpathStudySessionRepository sessions,
            JpathDailyPlanService dailyPlans, JpathDailyTabRepository dailyTabs, JpathUserItemRepository userItems,
            JpathStudyEventRepository events, JpathContentReader content, JpathItemStateMachine states,
            JpathDayCompletion completion, JpathClock clock) {
        this.paths = paths;
        this.sessions = sessions;
        this.dailyPlans = dailyPlans;
        this.dailyTabs = dailyTabs;
        this.userItems = userItems;
        this.events = events;
        this.content = content;
        this.states = states;
        this.completion = completion;
        this.clock = clock;
    }

    /** Outcomes in input order and the locked, updated session. */
    public record Applied(List<JpathEventOutcome> outcomes, JpathStudySession session) {
    }

    /**
     * Applies {@code batch} to the learner's session {@code sessionId}. Another learner's or unknown session →
     * {@code JPATH_SESSION_NOT_FOUND} with nothing written; no ACTIVE path → {@code JPATH_NO_ACTIVE_PATH}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Applied apply(Long userId, Long sessionId, JpathStudyEventBatch batch) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathDailyPlan plan = dailyPlans.lockToday(path);
        List<JpathDailyTab> tabs = dailyTabs.findForUpdateByPlanId(plan.getId());
        JpathStudySession session = sessions.findForUpdateByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_SESSION_NOT_FOUND));

        Batch b = new Batch(userId, path, plan, tabs, session);
        List<JpathEventOutcome> outcomes = new ArrayList<>(batch.events().size());
        for (JpathStudyEventInput e : batch.events()) {
            outcomes.add(one(b, e));
        }
        if (b.applied) {
            if (b.touched.isEmpty()) {
                completion.evaluate(userId, plan, tabs, null, true);
            }
            for (JpathTab t : b.touched) {
                completion.evaluate(userId, plan, tabs, t, true);
            }
        }
        return new Applied(outcomes, session);
    }

    // ---- one event -----------------------------------------------------------------------------------------

    /** State of one batch: the locked rows, the queue being edited, tabs whose counters moved. */
    private static final class Batch {
        final Long userId;
        final JpathUserPath path;
        final JpathDailyPlan plan;
        final List<JpathDailyTab> tabs;
        final JpathStudySession session;
        final Set<String> seenIds = new HashSet<>();
        final Set<JpathTab> touched = new LinkedHashSet<>();
        List<JpathQueueStep> queue;
        boolean applied;

        Batch(Long userId, JpathUserPath path, JpathDailyPlan plan, List<JpathDailyTab> tabs,
              JpathStudySession session) {
            this.userId = userId;
            this.path = path;
            this.plan = plan;
            this.tabs = tabs;
            this.session = session;
            this.queue = new ArrayList<>(JpathStudyServiceImpl.parse(session.getQueueJson()));
        }
    }

    private JpathEventOutcome one(Batch b, JpathStudyEventInput e) {
        String id = e.clientEventId();
        if (!b.seenIds.add(id)) {
            return duplicate(e, null);
        }
        Optional<JpathStudyEvent> recorded = events.findByUserIdAndClientEventId(b.userId, id);
        if (recorded.isPresent()) {
            return duplicate(e, recorded.get());
        }
        if (!OPEN.equals(b.session.getStatus())) {
            return rejected(id, SESSION_CLOSED);
        }
        int position = b.session.getPosition();
        // Only the current step: an event of a step already passed or still ahead (offline queue sent after the
        // queue changed) is stale and the page reloads the session instead.
        if (e.position() != position || position >= b.queue.size()) {
            return rejected(id, STEP_MISMATCH);
        }
        JpathQueueStep step = b.queue.get(position);
        if (step.itemType() == null || step.itemType() != e.itemType()
                || !Objects.equals(step.itemCode(), e.itemCode())) {
            return rejected(id, STEP_MISMATCH);
        }
        if (!allowed(step.kind(), e.action())) {
            return rejected(id, ACTION_MISMATCH);
        }
        return switch (e.action()) {
            case FIRST_RATE -> firstRate(b, step, e);
            case REVIEW_RATE -> reviewRate(b, step, e);
            case RETYPE -> retype(b, step, e);
            case MARK_KNOWN -> markKnown(b, step, e);
            // Task 38 issues the SKIP_ONE check here and returns its token.
            case SKIP_KNOWN -> rejected(id, NOT_READY);
            case CHECK_ANSWER -> rejected(id, ACTION_MISMATCH);
        };
    }

    /** NEW → FIRST_RATE / SKIP_KNOWN; REVIEW → REVIEW_RATE / MARK_KNOWN; RETYPE → RETYPE / MARK_KNOWN; checks → none. */
    private static boolean allowed(JpathStepKind kind, JpathStudyAction action) {
        if (action == null) {
            return false;
        }
        return switch (kind) {
            case NEW -> action == JpathStudyAction.FIRST_RATE || action == JpathStudyAction.SKIP_KNOWN;
            case REVIEW -> action == JpathStudyAction.REVIEW_RATE || action == JpathStudyAction.MARK_KNOWN;
            case RETYPE -> action == JpathStudyAction.RETYPE || action == JpathStudyAction.MARK_KNOWN;
            case GROUP_EXERCISE, MICRO_CHECK -> false;
        };
    }

    private JpathEventOutcome firstRate(Batch b, JpathQueueStep step, JpathStudyEventInput e) {
        JpathItemType type = step.itemType();
        boolean grammar = type == JpathItemType.GRAMMAR;
        JpathRating rating = grammar ? null : e.rating();
        if (!grammar && !FIRST_RATINGS.contains(rating)) {
            return rejected(e.clientEventId(), RATING_INVALID);
        }
        Optional<JpathUserItem> existing = userItems.findByUserIdAndItemTypeAndItemCode(b.userId, type,
                step.itemCode());
        // A repeat (or a card started elsewhere since the queue was frozen) is rated, never restarted or counted.
        boolean learnedBefore = existing.isPresent() && existing.get().getFirstLearnedAt() != null;
        if (step.repeat() || learnedBefore) {
            if (existing.isEmpty()) {
                return rejected(e.clientEventId(), ITEM_NOT_FOUND);
            }
            if (rating != null) {
                states.rate(existing.get(), rating);
            }
        } else {
            Optional<Visible> item = visible(type, step.itemCode());
            if (item.isEmpty()) {
                return rejected(e.clientEventId(), ITEM_NOT_FOUND);
            }
            states.startLearning(b.userId, type, step.itemCode(), item.get().level(), rating);
            countNew(b, step, item.get().level());
        }
        record(b, e, rating, rating == null ? null : rating != JpathRating.FORGOT);
        if (rating == JpathRating.FORGOT) {
            b.queue = JpathQueueBuilder.appendRepeat(b.queue, step, MAX_REPEATS);
        }
        return advance(b, e, null, null);
    }

    private JpathEventOutcome reviewRate(Batch b, JpathQueueStep step, JpathStudyEventInput e) {
        JpathRating rating = e.rating();
        if (!REVIEW_RATINGS.contains(rating)) {
            return rejected(e.clientEventId(), RATING_INVALID);
        }
        JpathUserItem row = userItems.findByUserIdAndItemTypeAndItemCode(b.userId, step.itemType(), step.itemCode())
                .orElse(null);
        if (row == null) {
            return rejected(e.clientEventId(), ITEM_NOT_FOUND);
        }
        boolean due = due(b, row);
        states.rate(row, rating);
        countReview(b, step, due);
        record(b, e, rating, rating != JpathRating.FORGOT);
        if (rating == JpathRating.FORGOT) {
            b.queue = JpathQueueBuilder.appendRepeat(b.queue, step, MAX_REPEATS);
        }
        return advance(b, e, null, null);
    }

    private JpathEventOutcome retype(Batch b, JpathQueueStep step, JpathStudyEventInput e) {
        JpathUserItem row = userItems.findByUserIdAndItemTypeAndItemCode(b.userId, step.itemType(), step.itemCode())
                .orElse(null);
        String expected = expectedReading(step);
        if (row == null || expected == null) {
            return rejected(e.clientEventId(), ITEM_NOT_FOUND);
        }
        JpathRetypeVerdict verdict = JpathKanaText.retypeVerdict(expected, e.typed());
        boolean due = due(b, row);
        states.retyped(row, verdict);
        countReview(b, step, due);
        record(b, e, retypeRating(verdict), verdict != JpathRetypeVerdict.WRONG);
        if (verdict == JpathRetypeVerdict.WRONG) {
            b.queue = JpathQueueBuilder.appendRepeat(b.queue, step, MAX_REPEATS);
        }
        return advance(b, e, verdict, expected);
    }

    private JpathEventOutcome markKnown(Batch b, JpathQueueStep step, JpathStudyEventInput e) {
        JpathUserItem row = userItems.findByUserIdAndItemTypeAndItemCode(b.userId, step.itemType(), step.itemCode())
                .orElse(null);
        if (row == null) {
            return rejected(e.clientEventId(), ITEM_NOT_FOUND);
        }
        if (row.getState() != JpathItemState.KNOWN) {
            states.markKnown(row, KNOWN_BY_USER);
        }
        record(b, e, null, null);
        return advance(b, e, null, null);
    }

    // ---- counters ------------------------------------------------------------------------------------------

    /** new_done (TODAY round, open level) or extra_done (EXTRA round) of today's plan; never for KANA cards. */
    private void countNew(Batch b, JpathQueueStep step, String itemLevel) {
        if (step.itemType() == JpathItemType.KANA || !ofTodaysPlan(b)) {
            return;
        }
        JpathDailyTab tab = tab(b, step.tab());
        if (tab == null) {
            return;
        }
        String mode = b.session.getMode();
        if (JpathSessionMode.TODAY.name().equals(mode) && Objects.equals(itemLevel, b.path.getOpenLevel())) {
            tab.setNewDone(tab.getNewDone() + 1);
            b.touched.add(tab.getTab());
        } else if (JpathSessionMode.EXTRA.name().equals(mode)) {
            tab.setExtraDone(tab.getExtraDone() + 1);
            b.touched.add(tab.getTab());
        }
    }

    /** review_done of the step's tab of today's plan, for a first (not repeated) review of a card that was due. */
    private void countReview(Batch b, JpathQueueStep step, boolean wasDue) {
        if (step.repeat() || !wasDue) {
            return;
        }
        JpathDailyTab tab = tab(b, step.tab());
        if (tab == null) {
            return;
        }
        tab.setReviewDone(tab.getReviewDone() + 1);
        b.touched.add(tab.getTab());
    }

    private boolean due(Batch b, JpathUserItem row) {
        LocalDateTime localNow = clock.localNow(b.userId);
        return row.getState() != JpathItemState.KNOWN && row.getNextReviewAt() != null
                && !row.getNextReviewAt().isAfter(localNow);
    }

    /** TODAY / EXTRA rounds count only on the plan they were built from (an older plan's round is not today's). */
    private static boolean ofTodaysPlan(Batch b) {
        return b.plan.getId().equals(b.session.getPlanId());
    }

    private static JpathDailyTab tab(Batch b, JpathTab t) {
        return b.tabs.stream().filter(x -> x.getTab() == t).findFirst().orElse(null);
    }

    // ---- writes --------------------------------------------------------------------------------------------

    private void record(Batch b, JpathStudyEventInput e, JpathRating rating, Boolean correct) {
        Long ms = e.elapsedMs();
        Integer elapsed = ms == null || ms < 0 ? null : (int) Math.min(ms, Integer.MAX_VALUE);
        LocalDate day = clock.today(b.userId);
        events.save(JpathStudyEvent.builder().userId(b.userId).sessionId(b.session.getId())
                .clientEventId(e.clientEventId()).itemType(e.itemType()).itemCode(e.itemCode())
                .action(e.action().name()).rating(rating)
                .quality(rating == null ? null : JpathSrsPolicy.quality(rating)).correct(correct)
                .elapsedMs(elapsed).studyDay(day).build());
        b.applied = true;
    }

    /** position = max(position, event position + 1); the queue is written back; past the end → COMPLETED. */
    private JpathEventOutcome advance(Batch b, JpathStudyEventInput e, JpathRetypeVerdict verdict, String expected) {
        JpathStudySession s = b.session;
        s.setPosition(Math.max(s.getPosition(), e.position() + 1));
        s.setQueueJson(JpathStudyServiceImpl.write(b.queue));
        s.setTotal(b.queue.size());
        if (s.getPosition() >= b.queue.size()) {
            s.setStatus(COMPLETED);
            s.setCompletedAt(clock.now());
        }
        return new JpathEventOutcome(e.clientEventId(), APPLIED, null, verdict, expected, null);
    }

    // ---- content -------------------------------------------------------------------------------------------

    /** A visible item and its level (null for KANA, which has none). */
    private record Visible(String level) {
    }

    private Optional<Visible> visible(JpathItemType type, String code) {
        return switch (type) {
            case VOCAB -> content.vocab(code).map(v -> new Visible(v.getLevel()));
            case KANJI -> content.kanji(code).map(k -> new Visible(k.getLevel()));
            case GRAMMAR -> content.grammar(code).map(g -> new Visible(g.getLevel()));
            case KANA -> content.kanaItem(code).map(k -> new Visible(null));
        };
    }

    /** Reading a RETYPE card expects (retype cards are words). */
    private String expectedReading(JpathQueueStep step) {
        if (step.itemType() != JpathItemType.VOCAB) {
            return null;
        }
        return content.vocab(step.itemCode()).map(JpathVocabulary::getReading).orElse(null);
    }

    /** The rating {@link JpathItemStateMachine#retyped} applies for {@code v} (stored on the RETYPE event). */
    static JpathRating retypeRating(JpathRetypeVerdict v) {
        return switch (v) {
            case EXACT -> JpathRating.GOOD;
            case MINOR -> JpathRating.HARD;
            case WRONG -> JpathRating.FORGOT;
        };
    }

    private static JpathRetypeVerdict verdictOf(JpathRating r) {
        if (r == null) {
            return null;
        }
        return switch (r) {
            case GOOD -> JpathRetypeVerdict.EXACT;
            case HARD -> JpathRetypeVerdict.MINOR;
            case FORGOT -> JpathRetypeVerdict.WRONG;
            default -> null;
        };
    }

    // ---- outcomes ------------------------------------------------------------------------------------------

    /** A replay answers like the first time for a recorded RETYPE (verdict from the stored rating). */
    private JpathEventOutcome duplicate(JpathStudyEventInput e, JpathStudyEvent recorded) {
        if (recorded != null && JpathStudyAction.RETYPE.name().equals(recorded.getAction())) {
            String expected = recorded.getItemType() == JpathItemType.VOCAB
                    ? content.vocab(recorded.getItemCode()).map(JpathVocabulary::getReading).orElse(null) : null;
            return new JpathEventOutcome(e.clientEventId(), DUPLICATE, null, verdictOf(recorded.getRating()),
                    expected, null);
        }
        return new JpathEventOutcome(e.clientEventId(), DUPLICATE, null, null, null, null);
    }

    private static JpathEventOutcome rejected(String clientEventId, String reason) {
        return new JpathEventOutcome(clientEventId, REJECTED, reason, null, null, null);
    }
}
