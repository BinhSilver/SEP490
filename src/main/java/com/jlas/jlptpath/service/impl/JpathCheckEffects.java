package com.jlas.jlptpath.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.jlptpath.dto.JpathCheckNext;
import com.jlas.jlptpath.dto.JpathCompletionOutcome;
import com.jlas.jlptpath.entity.JpathCheckSet;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathDailyTab;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserItem;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathPathService;
import com.jlas.jlptpath.service.JpathProgressService;
import com.jlas.jlptpath.util.JpathQueueBuilder;
import com.jlas.jlptpath.util.JpathQueueStep;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] What a submitted check changes (plan Tasks 37, 38): learner items, today's tab, the session queue and
 * the stage. Runs inside the submit transaction under its locks (path → level → check set → plan → tabs) and takes no
 * lock of its own; {@code reevaluateStage} / {@code refreshPacing} re-lock the same path and level rows in the same
 * transaction (already held, so no new lock order).
 * <ul>
 *   <li>MICRO_CHECK passed: every item counted; grammar patterns rated with the lowest auto rating of their
 *       questions; wrong V/K items go to the notebook and get a repeat card; on today's TODAY round the tab gets
 *       {@code check_passed_at} and is evaluated; the step advances; the stage is re-evaluated.</li>
 *   <li>MICRO_CHECK failed: wrong V/K items go to the notebook; the session stays (RETRY).</li>
 *   <li>KANA_ROW passed: the row's kana are counted; on today's TODAY round the KANA tab gets {@code new_done + 1}
 *       and, once the target is reached, {@code check_passed_at} + evaluation (plan decision 27: FIRST_RATE on kana
 *       never touches {@code new_done}).</li>
 *   <li>GROUP_EXERCISE: never passes or fails; wrong words are repeated right after it; no reward.</li>
 *   <li>KANA_ROW skill review (#25, {@code issue_json.skillReview}): nothing counted, no {@code new_done}; passed →
 *       SKILL {@code check_passed_at} + evaluation.</li>
 *   <li>SKIP_ONE: right → row (if missing) KNOWN "SKIP_CHECK" + counted + stage; wrong → no row. In a session the
 *       step advances; right on today's TODAY round → the tab's {@code new_done + 1}; wrong → a repeat of the
 *       card at the end.</li>
 *   <li>TOPIC_KNOWN applied (≥ 90%): right items KNOWN "TOPIC_CHECK" + counted; items not asked and without a row →
 *       LEARNING rep 0 due today, {@code first_learned_at} now, {@code known_source = TOPIC_PEND}; wrong items
 *       unchanged. Below the threshold nothing changes.</li>
 *   <li>FREE_QUIZ: wrong items get a row if missing, then {@code wrongInCheck(QUIZ)} (grammar: LEARNING due today,
 *       never the notebook); nothing counted, no CP; a skill-day quiz (≥ 10 questions on LEARNED/NOTEBOOK) on
 *       today's SKILL plan → SKILL {@code check_passed_at} + evaluation.</li>
 *   <li>GRAMMAR_REVIEW: each pattern rated with its auto rating, GRAMMAR {@code review_done += patterns},
 *       evaluation.</li>
 *   <li>CONFUSABLE, GRAMMAR_PRACTICE, GRAMMAR_EXTRA: no state change, no CP (a session step still advances).</li>
 * </ul>
 * A check whose plan is not today's still counts its items but writes no plan (the caller passes no plan).
 */
@Component
public class JpathCheckEffects {

    static final int MAX_REPEATS = 2;
    /** known_source of an item marked known by "skip one" / by the topic check. */
    static final String SKIP_CHECK = "SKIP_CHECK";
    static final String TOPIC_CHECK = "TOPIC_CHECK";
    static final String STUDY_URL = "/learning/jlpt/study?session=";
    static final String DONE_URL = "/learning?saved=1"; // owner 05/11: no summary page, back to the path

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JpathItemStateMachine items;
    private final JpathUserItemRepository userItems;
    private final JpathDayCompletion completion;
    private final JpathDailyPlanService planService;
    private final JpathProgressService progress;
    private final JpathPathService pathService;
    private final JpathClock clock;

    public JpathCheckEffects(JpathItemStateMachine items, JpathUserItemRepository userItems,
                             JpathDayCompletion completion, JpathDailyPlanService planService,
                             JpathProgressService progress, JpathPathService pathService, JpathClock clock) {
        this.items = items;
        this.userItems = userItems;
        this.completion = completion;
        this.planService = planService;
        this.progress = progress;
        this.pathService = pathService;
        this.clock = clock;
    }

    /**
     * Score of a check as the effects need it.
     *
     * @param itemCodes      items the check covers
     * @param wrongCodes     items with at least one wrong answer or wrong pick
     * @param grammarRatings grammar pattern → lowest auto rating of its questions
     */
    public record Scored(Boolean passed, boolean tooFast, List<String> itemCodes, Set<String> wrongCodes,
                         Map<String, JpathRating> grammarRatings, List<String> pendingCodes, boolean skillReview,
                         boolean skillDayQuiz) {
        public Scored {
            pendingCodes = pendingCodes == null ? List.of() : pendingCodes;
        }

        public Scored(Boolean passed, boolean tooFast, List<String> itemCodes, Set<String> wrongCodes,
                      Map<String, JpathRating> grammarRatings) {
            this(passed, tooFast, itemCodes, wrongCodes, grammarRatings, List.of(), false, false);
        }
    }

    /**
     * CP paid by the evaluation (0 when nothing was paid), the page's next step, and whether today's plan took the
     * result ({@code dayCredit}: the check may pay the day's reward unless it was too fast).
     */
    public record Applied(int cpAwarded, JpathCheckNext next, boolean dayCredit) {
    }

    /**
     * @param session the check's session (locked by the path lock), null for a check without one
     * @param today   today's plan, locked, only when the check belongs to today's TODAY round; else null
     * @param tabs    the locked tabs of {@code today} (empty when {@code today} is null)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Applied apply(JpathCheckSet set, Scored s, JpathStudySession session, JpathDailyPlan today,
                         List<JpathDailyTab> tabs) {
        Long userId = set.getUserId();
        JpathItemType type = itemType(set);
        List<JpathUserItem> rows = rows(userId, type, s.itemCodes());
        int cp = 0;
        boolean advance;
        boolean credit = false;
        switch (set.getKind()) {
            case GROUP_EXERCISE -> {
                repeatAfterCurrent(session, set, s.wrongCodes());
                advance = true;
            }
            case KANA_ROW -> {
                if (!Boolean.TRUE.equals(s.passed())) {
                    advance = false;
                } else if (s.skillReview()) {
                    // #25: a review of rows already passed — nothing new is counted, new_done stays.
                    cp = skillDone(userId, today, tabs, s.tooFast());
                    credit = today != null;
                    advance = true;
                } else {
                    rows.forEach(items::count);
                    cp = kanaRowDone(userId, today, tabs, s.tooFast());
                    credit = today != null;
                    reevaluate(userId);
                    advance = true;
                }
            }
            case SKIP_ONE -> {
                if (!Boolean.TRUE.equals(s.passed())) {
                    // Wrong: no row; in a session the learner stays on the card and rates it (FIRST_RATE counts it).
                    if (session == null) {
                        return new Applied(0, new JpathCheckNext("BACK", "Quay lại", null, null, null, null), false);
                    }
                    return new Applied(0, new JpathCheckNext("CONTINUE", "Học thẻ này", STUDY_URL + session.getId(),
                            set.getTab(), null, session.getId()), false);
                }
                for (String code : s.itemCodes()) {
                    JpathUserItem row = items.ensureRow(userId, type, code, set.getLevel());
                    items.markKnown(row, SKIP_CHECK);
                    items.count(row);
                }
                // A new item of today's round only when it belongs to the plan's (open) level (plan line 1912).
                if (session != null && today != null && Objects.equals(set.getLevel(), today.getLevel())) {
                    cp = newDone(userId, set.getTab(), today, tabs, s.tooFast());
                    credit = true;
                }
                reevaluate(userId);
                advance = true;
            }
            case TOPIC_KNOWN, WEEK_KNOWN -> {
                if (Boolean.TRUE.equals(s.passed())) {
                    applyTopic(userId, set, s);
                    reevaluate(userId);
                }
                advance = true;
            }
            case FREE_QUIZ -> {
                for (String code : s.itemCodes()) {
                    if (s.wrongCodes().contains(code)) {
                        items.wrongInCheck(items.ensureRow(userId, type, code, set.getLevel()),
                                JpathNotebookSource.QUIZ);
                    }
                }
                if (s.skillDayQuiz()) {
                    cp = skillDone(userId, today, tabs, s.tooFast());
                    credit = today != null && tab(tabs, JpathTab.SKILL) != null;
                }
                advance = true;
            }
            case GRAMMAR_REVIEW -> {
                for (JpathUserItem row : rows) {
                    items.rate(row, s.grammarRatings().getOrDefault(row.getItemCode(), JpathRating.GOOD));
                }
                JpathDailyTab grammar = tab(tabs, JpathTab.GRAMMAR);
                if (today != null && grammar != null) {
                    grammar.setReviewDone(grammar.getReviewDone() + rows.size());
                    cp = completion.evaluate(userId, today, tabs, JpathTab.GRAMMAR, !s.tooFast()).cpExpected();
                    credit = true;
                }
                advance = true;
            }
            case CONFUSABLE, GRAMMAR_PRACTICE, GRAMMAR_EXTRA -> advance = true;
            // Plan 3 skill checks: their session moves on in JpathSkillPracticeService (no item rows, no plan)
            case SKILL_LESSON, SKILL_PRACTICE, SKILL_DICTATION, SKILL_SECTION -> advance = true;
            default -> { // MICRO_CHECK
                boolean notebookType = type == JpathItemType.VOCAB || type == JpathItemType.KANJI;
                if (Boolean.TRUE.equals(s.passed())) {
                    for (JpathUserItem row : rows) {
                        items.count(row);
                        if (type == JpathItemType.GRAMMAR) {
                            items.rate(row, s.grammarRatings().getOrDefault(row.getItemCode(), JpathRating.GOOD));
                        } else if (notebookType && s.wrongCodes().contains(row.getItemCode())) {
                            items.wrongInCheck(row, JpathNotebookSource.MICRO_CHECK);
                            appendRepeat(session, set, row);
                        }
                    }
                    cp = microCheckDone(userId, set.getTab(), today, tabs, s.tooFast());
                    credit = today != null;
                    reevaluate(userId);
                    advance = true;
                } else {
                    if (notebookType) {
                        rows.stream().filter(r -> s.wrongCodes().contains(r.getItemCode()))
                                .forEach(r -> items.wrongInCheck(r, JpathNotebookSource.MICRO_CHECK));
                    }
                    advance = false;
                }
            }
        }
        if (session == null) {
            return new Applied(cp, new JpathCheckNext("BACK", "Quay lại", null, null, null, null), credit);
        }
        if (!advance) {
            return new Applied(cp, new JpathCheckNext("RETRY", "Làm lại bài kiểm tra", STUDY_URL + session.getId(),
                    set.getTab(), null, session.getId()), credit);
        }
        return new Applied(cp, advance(session, set.getToken()), credit);
    }

    /**
     * Topic / week check applied: right items KNOWN (TOPIC_CHECK) and counted; items not asked wait for a review.
     * A topic check is always Từ vựng; a week check (owner 06/11) uses its tab's item type.
     */
    private void applyTopic(Long userId, JpathCheckSet set, Scored s) {
        JpathItemType type = set.getKind() == JpathCheckKind.WEEK_KNOWN ? itemType(set) : JpathItemType.VOCAB;
        for (String code : s.itemCodes()) {
            if (!s.wrongCodes().contains(code)) {
                JpathUserItem row = items.ensureRow(userId, type, code, set.getLevel());
                items.markKnown(row, TOPIC_CHECK);
                items.count(row);
            }
        }
        for (String code : s.pendingCodes()) {
            if (userItems.findByUserIdAndItemTypeAndItemCode(userId, type, code).isPresent()) {
                continue;
            }
            JpathUserItem row = items.ensureRow(userId, type, code, set.getLevel());
            row.setFirstLearnedAt(clock.localNow(userId));
            row.setKnownSource(JpathItemStateMachine.TOPIC_PENDING);
            userItems.save(row);
        }
    }

    // ---- items ---------------------------------------------------------------------------------------------

    static JpathItemType itemType(JpathCheckSet set) {
        if (set.getKind() == JpathCheckKind.GROUP_EXERCISE) {
            return JpathItemType.VOCAB;
        }
        if (set.getKind() == JpathCheckKind.KANA_ROW || set.getTab() == JpathTab.KANA
                || set.getTab() == JpathTab.SKILL) {
            return JpathItemType.KANA;
        }
        return JpathItemType.valueOf(set.getTab().name());
    }

    /** Learner rows of the check's items, in check order; items without a row are skipped (never learned). */
    private List<JpathUserItem> rows(Long userId, JpathItemType type, List<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        Map<String, JpathUserItem> byCode = new java.util.HashMap<>();
        userItems.findByUserIdAndItemTypeAndItemCodeIn(userId, type, codes)
                .forEach(r -> byCode.put(r.getItemCode(), r));
        return codes.stream().map(byCode::get).filter(Objects::nonNull).toList();
    }

    private void reevaluate(Long userId) {
        if (progress.reevaluateStage(userId)) {
            pathService.refreshPacing(userId);
        }
    }

    // ---- today's plan --------------------------------------------------------------------------------------

    private int microCheckDone(Long userId, JpathTab tab, JpathDailyPlan today, List<JpathDailyTab> tabs,
                               boolean tooFast) {
        JpathDailyTab row = tab(tabs, tab);
        if (today == null || row == null) {
            return 0;
        }
        LocalDateTime now = clock.now();
        if (row.getCheckPassedAt() == null) {
            row.setCheckPassedAt(now);
        }
        if (tooFast) {
            row.setTooFast(true);
        }
        JpathCompletionOutcome outcome = completion.evaluate(userId, today, tabs, tab, !tooFast);
        return outcome.cpExpected();
    }

    private int kanaRowDone(Long userId, JpathDailyPlan today, List<JpathDailyTab> tabs, boolean tooFast) {
        JpathDailyTab row = tab(tabs, JpathTab.KANA);
        if (today == null || row == null) {
            return 0;
        }
        row.setNewDone(row.getNewDone() + 1);
        if (tooFast) {
            row.setTooFast(true);
        }
        if (row.getNewDone() < planService.effectiveTarget(today, row)) {
            return 0;
        }
        if (row.getCheckPassedAt() == null) {
            row.setCheckPassedAt(clock.now());
        }
        return completion.evaluate(userId, today, tabs, JpathTab.KANA, !tooFast).cpExpected();
    }

    /** Skill day (#25): the SKILL tab's check is done; evaluation pays the skill day when the reviews are done. */
    private int skillDone(Long userId, JpathDailyPlan today, List<JpathDailyTab> tabs, boolean tooFast) {
        JpathDailyTab row = tab(tabs, JpathTab.SKILL);
        if (today == null || row == null) {
            return 0;
        }
        if (row.getCheckPassedAt() == null) {
            row.setCheckPassedAt(clock.now());
        }
        return completion.evaluate(userId, today, tabs, JpathTab.SKILL, !tooFast).cpExpected();
    }

    /** A NEW card answered by "skip one" on today's TODAY round counts as one of the tab's new items. */
    private int newDone(Long userId, JpathTab tab, JpathDailyPlan today, List<JpathDailyTab> tabs, boolean tooFast) {
        JpathDailyTab row = tab(tabs, tab);
        if (today == null || row == null) {
            return 0;
        }
        row.setNewDone(row.getNewDone() + 1);
        return completion.evaluate(userId, today, tabs, tab, !tooFast).cpExpected();
    }

    private static JpathDailyTab tab(List<JpathDailyTab> tabs, JpathTab tab) {
        return tabs.stream().filter(t -> t.getTab() == tab).findFirst().orElse(null);
    }

    // ---- session queue -------------------------------------------------------------------------------------

    /** A repeat card at the end of the queue for a wrong item (its NEW step, or a review card for an orphan). */
    private void appendRepeat(JpathStudySession session, JpathCheckSet set, JpathUserItem row) {
        if (session == null) {
            return;
        }
        List<JpathQueueStep> q = parse(session.getQueueJson());
        JpathQueueStep of = q.stream().filter(st -> st.kind() == JpathStepKind.NEW && !st.repeat()
                        && row.getItemCode().equals(st.itemCode()) && st.itemType() == row.getItemType())
                .findFirst()
                .orElse(new JpathQueueStep(0, JpathStepKind.REVIEW, set.getTab(), row.getItemType(),
                        row.getItemCode(), null, null, false));
        write(session, JpathQueueBuilder.appendRepeat(q, of, MAX_REPEATS));
    }

    /**
     * A step with nothing to ask, done by the server: a skill quiz with no source gets the #25 "nothing to quiz"
     * rule (SKILL check_passed_at + evaluation); every such step then moves the session on.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public JpathCheckNext skipStep(Long userId, JpathStudySession session, int position, JpathDailyPlan today,
                                   List<JpathDailyTab> tabs, boolean skillNothing) {
        if (skillNothing) {
            skillDone(userId, today, tabs, false);
        }
        if (session.getPosition() == position) {
            session.setPosition(position + 1);
        }
        return next(session);
    }

    /** Group exercise: repeat cards of the wrong words right after the exercise step. */
    private void repeatAfterCurrent(JpathStudySession session, JpathCheckSet set, Set<String> wrong) {
        if (session == null || wrong.isEmpty()) {
            return;
        }
        List<JpathQueueStep> q = parse(session.getQueueJson());
        int at = stepOf(q, set.getToken());
        if (at < 0) {
            return;
        }
        List<JpathQueueStep> repeats = new ArrayList<>();
        for (int i = 0; i < at; i++) {
            JpathQueueStep st = q.get(i);
            if (st.kind() == JpathStepKind.NEW && !st.repeat() && st.itemType() == JpathItemType.VOCAB
                    && wrong.contains(st.itemCode())
                    && repeats.stream().noneMatch(r -> r.itemCode().equals(st.itemCode()))) {
                repeats.add(st.asRepeat(0));
            }
        }
        write(session, JpathQueueBuilder.insertAfter(q, at, repeats));
    }

    /** Moves the session past the check step (when it is the current one) and describes what comes next. */
    private JpathCheckNext advance(JpathStudySession session, String token) {
        List<JpathQueueStep> q = parse(session.getQueueJson());
        int at = stepOf(q, token);
        if (at >= 0 && at == session.getPosition()) {
            session.setPosition(at + 1);
        }
        return next(session);
    }

    /** What comes after the session's position (DONE and COMPLETED past the end). */
    private JpathCheckNext next(JpathStudySession session) {
        List<JpathQueueStep> q = parse(session.getQueueJson());
        session.setTotal(q.size());
        if (session.getPosition() >= q.size()) {
            if ("OPEN".equals(session.getStatus())) {
                session.setStatus("COMPLETED");
                session.setCompletedAt(clock.now());
            }
            return new JpathCheckNext("DONE", "Xong lượt học", DONE_URL, null, null, session.getId());
        }
        JpathQueueStep next = q.get(session.getPosition());
        String url = STUDY_URL + session.getId();
        if (next.kind() == JpathStepKind.NEW) {
            int count = 0;
            for (int i = session.getPosition(); i < q.size(); i++) {
                JpathQueueStep st = q.get(i);
                if (st.kind() == JpathStepKind.NEW && st.tab() == next.tab() && !st.repeat()) {
                    count++;
                } else if (st.kind() == JpathStepKind.NEW && st.tab() != next.tab()) {
                    break;
                }
            }
            String label = count > 0 ? "Tiếp: " + tabLabel(next.tab()) + " mới (" + count + ")" : "Tiếp tục";
            return new JpathCheckNext("CONTINUE", label, url, next.tab(), count > 0 ? count : null,
                    session.getId());
        }
        return new JpathCheckNext("CONTINUE", "Tiếp tục", url, next.tab(), null, session.getId());
    }

    static String tabLabel(JpathTab tab) {
        return switch (tab) {
            case VOCAB -> "Từ vựng";
            case KANJI -> "Kanji";
            case GRAMMAR -> "Ngữ pháp";
            case KANA -> "Chữ cái";
            case SKILL -> "Kỹ năng";
        };
    }

    static int stepOf(List<JpathQueueStep> q, String token) {
        for (int i = 0; i < q.size(); i++) {
            if (Objects.equals(q.get(i).checkToken(), token)) {
                return i;
            }
        }
        return -1;
    }

    static List<JpathQueueStep> parse(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(json, JpathQueueStep[].class));
        } catch (Exception e) {
            throw new IllegalStateException("Broken queue_json", e);
        }
    }

    static void write(JpathStudySession session, List<JpathQueueStep> q) {
        try {
            session.setQueueJson(JSON.writeValueAsString(q));
            session.setTotal(q.size());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot write queue_json", e);
        }
    }

    static boolean todayRound(JpathStudySession session) {
        return session != null && JpathSessionMode.TODAY.name().equals(session.getMode());
    }
}
