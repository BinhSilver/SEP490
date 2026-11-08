package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathSessionStartRequest;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.entity.JpathDailyPlan;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.entity.JpathStudySession;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathStudyMode;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.repository.JpathDailyTabRepository;
import com.jlas.jlptpath.repository.JpathStudySessionRepository;
import com.jlas.jlptpath.repository.JpathUserItemRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import com.jlas.jlptpath.service.JpathDailyPlanService;
import com.jlas.jlptpath.service.JpathStudyService;
import com.jlas.jlptpath.util.JpathQueueBuilder;
import com.jlas.jlptpath.util.JpathQueueStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [JLAS-W0108] "Học mẫu này trong lượt hôm nay" (plan Task 39, preflight C16): a learnable pattern not started yet
 * takes the first grammar NEW slot not reached of today's open TODAY GRAMMAR/COMBINED session; the last such pattern
 * leaves together with its GRAMMAR_PRACTICE step, and the new one gets a practice step only when it has a visible
 * ORDER and FILL exercise. Without an open session a TODAY session is started with the pattern. Not transactional:
 * {@code ensurePlanExists} first, then one writer transaction path → {@code lockToday} (level, plan) → tabs.
 */
@Component
public class JpathGrammarQueue {

    private static final String OPEN = "OPEN";
    private static final Set<String> SESSION_TABS = Set.of(JpathSessionTab.GRAMMAR.name(),
            JpathSessionTab.COMBINED.name());

    private final JpathContentReader content;
    private final JpathUserItemRepository userItems;
    private final JpathUserPathRepository paths;
    private final JpathDailyTabRepository dailyTabs;
    private final JpathStudySessionRepository sessions;
    private final JpathDailyPlanService dailyPlans;
    private final JpathStudyService study;
    private final TransactionTemplate tx;

    public JpathGrammarQueue(JpathContentReader content, JpathUserItemRepository userItems,
            JpathUserPathRepository paths, JpathDailyTabRepository dailyTabs, JpathStudySessionRepository sessions,
            JpathDailyPlanService dailyPlans, JpathStudyService study, PlatformTransactionManager transactionManager) {
        this.content = content;
        this.userItems = userItems;
        this.paths = paths;
        this.dailyTabs = dailyTabs;
        this.sessions = sessions;
        this.dailyPlans = dailyPlans;
        this.study = study;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Hidden → JPATH_ITEM_NOT_FOUND; not learnable → JPATH_CONTENT_NOT_LEARNABLE; already started → unchanged. */
    public JpathTodayView queueToday(Long userId, String code) {
        JpathGrammar g = content.grammar(code).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        if (!content.learnable(g, content.questions(code, JpathContentReader.CHECK_BANK).size())) {
            throw new ApiException(ErrorCode.JPATH_CONTENT_NOT_LEARNABLE);
        }
        if (started(userId, code)) {
            return dailyPlans.today(userId);
        }
        boolean practice = !content.questions(code, "ORDER").isEmpty() && !content.questions(code, "FILL").isEmpty();
        dailyPlans.ensurePlanExists(userId);
        if (!Boolean.TRUE.equals(tx.execute(s -> intoOpenSession(userId, code, practice)))) {
            JpathStudyMode mode = paths.findFirstByUserIdAndStatusOrderByIdDesc(userId, JpathPathStatus.ACTIVE)
                    .map(JpathUserPath::getStudyMode)
                    .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
            JpathSessionTab tab = mode == JpathStudyMode.SPLIT ? JpathSessionTab.GRAMMAR : JpathSessionTab.COMBINED;
            study.start(userId, new JpathSessionStartRequest(tab, JpathSessionMode.TODAY, null, null, null, code,
                    null));
            // the new session holds the next patterns, not necessarily this one: put it in now
            tx.execute(s -> intoOpenSession(userId, code, practice));
        }
        return dailyPlans.today(userId);
    }

    private boolean started(Long userId, String code) {
        return userItems.findByUserIdAndItemTypeAndItemCode(userId, JpathItemType.GRAMMAR, code).isPresent();
    }

    /** Writer transaction; false when today's plan has no open TODAY GRAMMAR/COMBINED session. */
    private boolean intoOpenSession(Long userId, String code, boolean practice) {
        JpathUserPath path = paths.findActiveForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_NO_ACTIVE_PATH));
        JpathDailyPlan plan = dailyPlans.lockToday(path);
        dailyTabs.findForUpdateByPlanId(plan.getId());
        Optional<JpathStudySession> open = sessions.findByUserIdAndStatusAndModeOrderByIdDesc(userId, OPEN,
                        JpathSessionMode.TODAY.name()).stream()
                .filter(s -> plan.getId().equals(s.getPlanId()) && SESSION_TABS.contains(s.getTab()))
                .findFirst();
        if (open.isEmpty()) {
            return false;
        }
        if (started(userId, code)) {
            return true;
        }
        JpathStudySession s = open.get();
        List<JpathQueueStep> q = swap(JpathStudyServiceImpl.parse(s.getQueueJson()), s.getPosition(), code,
                practice);
        s.setQueueJson(JpathStudyServiceImpl.write(q));
        s.setTotal(q.size());
        return true;
    }

    /**
     * C16 swap on a queue (steps before {@code position} are never touched): the pattern goes to the first grammar
     * NEW step not reached (with its practice step when {@code practice}); the last grammar NEW step not reached and
     * its practice step leave, or the pattern's own steps move when it is already queued. No grammar NEW step left →
     * the pattern goes before the grammar micro-check not reached, or at the end with a new micro-check.
     */
    static List<JpathQueueStep> swap(List<JpathQueueStep> q, int position, String code, boolean practice) {
        List<JpathQueueStep> out = new ArrayList<>(q);
        int existing = firstNew(out, 0, code);
        if (existing >= 0 && existing < position) {
            return q; // already learned in this session
        }
        int first = firstNew(out, position, null);
        if (existing >= 0 && existing == first) {
            return q;
        }
        String leaving = existing >= 0 ? code : lastNewCode(out, position);
        if (first >= 0 && leaving != null) {
            removeFrom(out, first, leaving);
        }
        List<JpathQueueStep> insert = new ArrayList<>(3);
        insert.add(new JpathQueueStep(0, JpathStepKind.NEW, JpathTab.GRAMMAR, JpathItemType.GRAMMAR, code, null,
                null, false));
        if (practice) {
            insert.add(new JpathQueueStep(0, JpathStepKind.MICRO_CHECK, JpathTab.GRAMMAR, JpathItemType.GRAMMAR, code,
                    JpathCheckKind.GRAMMAR_PRACTICE, null, false));
        }
        int at = first;
        if (at < 0) {
            at = grammarCheck(out, position);
            if (at < 0) {
                at = out.size();
                insert.add(new JpathQueueStep(0, JpathStepKind.MICRO_CHECK, JpathTab.GRAMMAR, null, null,
                        JpathCheckKind.MICRO_CHECK, null, false));
            }
        }
        out.addAll(at, insert);
        return JpathQueueBuilder.insertAfter(out, out.size() - 1, List.of()); // renumbers
    }

    private static boolean grammarNew(JpathQueueStep s) {
        return s.kind() == JpathStepKind.NEW && s.tab() == JpathTab.GRAMMAR && !s.repeat();
    }

    private static int firstNew(List<JpathQueueStep> q, int from, String code) {
        for (int i = from; i < q.size(); i++) {
            if (grammarNew(q.get(i)) && (code == null || code.equals(q.get(i).itemCode()))) {
                return i;
            }
        }
        return -1;
    }

    private static String lastNewCode(List<JpathQueueStep> q, int from) {
        for (int i = q.size() - 1; i >= from; i--) {
            if (grammarNew(q.get(i))) {
                return q.get(i).itemCode();
            }
        }
        return null;
    }

    /** Removes the NEW step of {@code code} and its practice step at or after {@code from}. */
    private static void removeFrom(List<JpathQueueStep> q, int from, String code) {
        for (int i = q.size() - 1; i >= from; i--) {
            JpathQueueStep s = q.get(i);
            boolean ownPractice = s.checkKind() == JpathCheckKind.GRAMMAR_PRACTICE
                    && Objects.equals(code, s.itemCode());
            if ((grammarNew(s) && code.equals(s.itemCode())) || ownPractice) {
                q.remove(i);
            }
        }
    }

    private static int grammarCheck(List<JpathQueueStep> q, int from) {
        for (int i = from; i < q.size(); i++) {
            JpathQueueStep s = q.get(i);
            if (s.tab() == JpathTab.GRAMMAR && s.checkKind() == JpathCheckKind.MICRO_CHECK) {
                return i;
            }
        }
        return -1;
    }
}
