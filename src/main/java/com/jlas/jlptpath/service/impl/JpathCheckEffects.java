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
 * [JLAS-W0108] What a submitted check changes (plan Task 37): learner items, today's tab, the session queue and the
 * stage. Runs inside the submit transaction under its locks (path → level → check set → plan → tabs); takes no lock
 * of its own and never re-reads the path.
 * <ul>
 *   <li>MICRO_CHECK passed: every item counted; grammar patterns rated with the lowest auto rating of their
 *       questions; wrong V/K items go to the notebook and get a repeat card; on today's TODAY round the tab gets
 *       {@code check_passed_at} and is evaluated; the step advances; the stage is re-evaluated.</li>
 *   <li>MICRO_CHECK failed: wrong V/K items go to the notebook; the session stays (RETRY).</li>
 *   <li>KANA_ROW passed: the row's kana are counted; on today's TODAY round the KANA tab gets {@code new_done + 1}
 *       and, once the target is reached, {@code check_passed_at} + evaluation (plan decision 27: FIRST_RATE on kana
 *       never touches {@code new_done}).</li>
 *   <li>GROUP_EXERCISE: never passes or fails; wrong words are repeated right after it; no reward.</li>
 * </ul>
 * A check whose plan is not today's still counts its items but writes no plan (the caller passes no plan).
 */
@Component
public class JpathCheckEffects {

    static final int MAX_REPEATS = 2;
    static final String STUDY_URL = "/learning/jlpt/study?session=";
    static final String DONE_URL = "/learning/jlpt/done";

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
                         Map<String, JpathRating> grammarRatings) {
    }

    /** CP paid by the evaluation (0 when nothing was paid) and the page's next step. */
    public record Applied(int cpAwarded, JpathCheckNext next) {
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
        switch (set.getKind()) {
            case GROUP_EXERCISE -> {
                repeatAfterCurrent(session, set, s.wrongCodes());
                advance = true;
            }
            case KANA_ROW -> {
                if (Boolean.TRUE.equals(s.passed())) {
                    rows.forEach(items::count);
                    cp = kanaRowDone(userId, today, tabs, s.tooFast());
                    reevaluate(userId);
                    advance = true;
                } else {
                    advance = false;
                }
            }
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
            return new Applied(cp, new JpathCheckNext("BACK", "Quay lại", null, null, null, null));
        }
        if (!advance) {
            return new Applied(cp, new JpathCheckNext("RETRY", "Làm lại bài kiểm tra", STUDY_URL + session.getId(),
                    set.getTab(), null, session.getId()));
        }
        return new Applied(cp, advance(session, set.getToken()));
    }

    // ---- items ---------------------------------------------------------------------------------------------

    static JpathItemType itemType(JpathCheckSet set) {
        if (set.getKind() == JpathCheckKind.GROUP_EXERCISE) {
            return JpathItemType.VOCAB;
        }
        if (set.getKind() == JpathCheckKind.KANA_ROW) {
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
