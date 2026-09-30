package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.learning.dto.GrammarFillSubmitRequest;
import com.jlas.learning.dto.GrammarOrderingSubmitRequest;
import com.jlas.learning.dto.GrammarPracticeResult;
import com.jlas.learning.dto.GrammarStudyState;
import com.jlas.learning.dto.GrammarStudyState.ComponentToken;
import com.jlas.learning.dto.GrammarStudyState.ExampleView;
import com.jlas.learning.dto.GrammarStudyState.FillPractice;
import com.jlas.learning.dto.GrammarStudyState.GrammarPointView;
import com.jlas.learning.dto.GrammarStudyState.OrderingPractice;
import com.jlas.learning.dto.GrammarStudyState.OrderingWord;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningGrammarPracticeProgress;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningGrammarPracticeProgressRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.service.InteractiveGrammarService;
import com.jlas.learning.service.impl.engine.InteractiveContentAdapter;
import com.jlas.learning.service.impl.engine.InteractiveStudyEngine;
import com.jlas.learning.service.impl.engine.QuestionSpec;
import com.jlas.learning.service.impl.grammar.GrammarTextAnalyzer;
import com.jlas.learning.service.impl.grammar.GrammarTextAnalyzer.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** [JLAS-W0193] Task 6 — Grammar: 1-3 approved points, each gated by ordering + fill-blank practice before
 * its micro-check opens. Unlike Vocabulary/Kanji, Grammar has no flip/remember card, so the study/practice
 * half is Grammar-specific code (this class), while the micro-check half (issue/submit/state/review —
 * attempt cycle, cooldown, review, idempotent submit, 70% pass, first/latest/best) is unchanged, reused
 * verbatim via {@link InteractiveStudyEngine}, exactly the same as Kanji reuses it. The engine's eligibility
 * check is generalized (not duplicated) via {@link InteractiveContentAdapter#requiresFlipRememberGate()} /
 * {@link InteractiveContentAdapter#assertPracticeGateSatisfied}, which this class's adapter implements by
 * consulting {@link LearningGrammarPracticeProgressRepository} instead of the flip/remember card table. */
@Service
public class InteractiveGrammarServiceImpl implements InteractiveGrammarService {
    static final String MICRO_CHECK_TYPE = "GRAMMAR_MICRO_CHECK";

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningGrammarPracticeProgressRepository progress;
    private final GrammarPointRepository grammarPoints;
    private final InteractiveStudyEngine<GrammarPoint> engine;

    public InteractiveGrammarServiceImpl(UserLearningPathItemRepository items, LearningActivityMemberRepository members,
            LearningStudySessionRepository studySessions, LearningStudyCardRepository studyCards,
            LearningMicroCheckStateRepository checkStates, LearningQuizSessionRepository quizSessions,
            LearningGrammarPracticeProgressRepository progress, GrammarPointRepository grammarPoints,
            LearningMapper mapper) {
        this.items = items;
        this.members = members;
        this.progress = progress;
        this.grammarPoints = grammarPoints;
        this.engine = new InteractiveStudyEngine<>(items, members, studySessions, studyCards, checkStates,
                quizSessions, mapper, new GrammarContentAdapter(grammarPoints, progress));
    }

    // ---- Study / practice (Grammar-specific; does not go through the engine) ----

    @Override
    @Transactional
    public GrammarStudyState studyState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        Map<Long, GrammarPoint> byId = loadPoints(membership);

        List<GrammarPointView> views = new ArrayList<>();
        int satisfied = 0;
        for (LearningActivityMember member : membership) {
            GrammarPoint point = byId.get(member.getCanonicalId());
            LearningGrammarPracticeProgress p = currentProgress(activityId, member);
            GrammarPointView view = toView(member, point, p);
            if (view.gateSatisfied()) satisfied++;
            views.add(view);
        }
        boolean checkEligible = !membership.isEmpty() && satisfied == membership.size();
        return new GrammarStudyState(activity.getId(), activity.getActivityType(), activity.getActivityState(),
                membership.getFirst().getSourceRevision(), membership.size(), satisfied, checkEligible, views);
    }

    @Override
    @Transactional
    public GrammarPracticeResult submitOrdering(Long userId, Long activityId, Long grammarPointId,
            GrammarOrderingSubmitRequest request) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        LearningActivityMember member = requireMember(activity, grammarPointId);
        GrammarPoint point = requirePoint(grammarPointId);
        List<String> words = exampleWords(point);
        if (words.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Mẫu ngữ pháp này chưa có câu ví dụ để luyện tập.");
        }
        List<Integer> canonicalOrder = IntStream.range(0, words.size()).boxed().toList();
        boolean correct = canonicalOrder.equals(request.order());

        LearningGrammarPracticeProgress state = lockOrCreateProgress(activity, member);
        if (correct) {
            state.setOrderingDone(true);
            state.setOrderingDoneAt(LocalDateTime.now());
        }
        progress.save(state);

        String correctText = String.join(" ", words);
        String explanation = correct
                ? "Chính xác! Câu đúng thứ tự là: " + correctText
                : "Câu đúng là: " + correctText + ". " + meaningExplanation(point);
        return new GrammarPracticeResult(grammarPointId, "ORDERING", correct, state.isGateSatisfied(),
                correctText, explanation);
    }

    @Override
    @Transactional
    public GrammarPracticeResult submitFill(Long userId, Long activityId, Long grammarPointId,
            GrammarFillSubmitRequest request) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        LearningActivityMember member = requireMember(activity, grammarPointId);
        GrammarPoint point = requirePoint(grammarPointId);
        List<String> words = exampleWords(point);
        int blankIndex = GrammarTextAnalyzer.chooseBlankIndex(words);
        if (blankIndex < 0) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Mẫu ngữ pháp này chưa có câu ví dụ để luyện tập.");
        }
        String canonicalAnswer = words.get(blankIndex);
        boolean correct = GrammarTextAnalyzer.normalizeAnswer(request.answer())
                .equals(GrammarTextAnalyzer.normalizeAnswer(canonicalAnswer));

        LearningGrammarPracticeProgress state = lockOrCreateProgress(activity, member);
        if (correct) {
            state.setFillDone(true);
            state.setFillDoneAt(LocalDateTime.now());
        }
        progress.save(state);

        String explanation = correct
                ? "Chính xác! Phần còn thiếu là “" + canonicalAnswer + "”."
                : "Phần còn thiếu là “" + canonicalAnswer + "”. " + meaningExplanation(point);
        return new GrammarPracticeResult(grammarPointId, "FILL_BLANK", correct, state.isGateSatisfied(),
                canonicalAnswer, explanation);
    }

    // ---- Micro-check (unchanged engine contract, reused verbatim) ----

    @Override
    @Transactional
    public MicroCheckResponse issueMicroCheck(Long userId, Long activityId) {
        return engine.issueMicroCheck(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engine.submitMicroCheck(userId, activityId, request);
    }

    @Override
    @Transactional
    public MicroCheckStateResponse microCheckState(Long userId, Long activityId) {
        return engine.microCheckState(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckStateResponse completeReview(Long userId, Long activityId) {
        return engine.completeReview(userId, activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<com.jlas.learning.dto.AttemptFeedbackResponse> reviewFeedback(Long userId, Long activityId) {
        return engine.reviewFeedback(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckResponse issuePractice(Long userId, Long activityId) {
        return engine.issuePractice(userId, activityId);
    }

    @Override
    @Transactional
    public MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        return engine.submitPractice(userId, activityId, request);
    }

    // ---- Internal helpers (study/practice side; the engine owns its own identical-shaped helpers) ----

    private UserLearningPathItem loadActivity(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findOwnedByIdForUpdate(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != LearningActivityType.GRAMMAR) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return activity;
    }

    private List<LearningActivityMember> loadMembership(UserLearningPathItem activity) {
        List<LearningActivityMember> list = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .filter(m -> m.getMemberType() == LearningMemberType.GRAMMAR_POINT)
                .toList();
        if (list.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có danh sách nội dung.");
        }
        return list;
    }

    /** Rejects a grammar point id that does not belong to this activity's own membership as NOT_FOUND —
     * the same "cross-activity/cross-package reference is indistinguishable from missing" IDOR posture the
     * engine uses for {@code memberId}. */
    private LearningActivityMember requireMember(UserLearningPathItem activity, Long grammarPointId) {
        return loadMembership(activity).stream()
                .filter(m -> m.getCanonicalId().equals(grammarPointId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    private GrammarPoint requirePoint(Long grammarPointId) {
        return grammarPoints.findById(grammarPointId)
                .orElseThrow(() -> new ApiException(ErrorCode.BLOCKED_CONTENT, "Không tìm thấy mẫu ngữ pháp."));
    }

    private Map<Long, GrammarPoint> loadPoints(List<LearningActivityMember> membership) {
        List<Long> ids = membership.stream().map(LearningActivityMember::getCanonicalId).distinct().toList();
        return grammarPoints.findAllById(ids).stream().collect(Collectors.toMap(GrammarPoint::getId, g -> g));
    }

    private LearningGrammarPracticeProgress currentProgress(Long activityId, LearningActivityMember member) {
        return progress.findByActivityIdAndGrammarPointId(activityId, member.getCanonicalId())
                .filter(p -> p.getSourceRevision().equals(member.getSourceRevision()))
                .orElse(null);
    }

    /** Locks (or creates) the point's progress row. A membership revision bump (re-composition) resets
     * stale progress from a previous revision rather than silently carrying it over to different content. */
    private LearningGrammarPracticeProgress lockOrCreateProgress(UserLearningPathItem activity,
            LearningActivityMember member) {
        LearningGrammarPracticeProgress state = progress.findForUpdate(activity.getId(), member.getCanonicalId())
                .orElse(null);
        if (state == null) {
            state = LearningGrammarPracticeProgress.builder().activity(activity)
                    .grammarPointId(member.getCanonicalId()).sourceRevision(member.getSourceRevision()).build();
        } else if (!state.getSourceRevision().equals(member.getSourceRevision())) {
            state.setSourceRevision(member.getSourceRevision());
            state.setOrderingDone(false);
            state.setFillDone(false);
            state.setOrderingDoneAt(null);
            state.setFillDoneAt(null);
        }
        return state;
    }

    private GrammarPointView toView(LearningActivityMember member, GrammarPoint point,
            LearningGrammarPracticeProgress p) {
        String pattern = point == null ? null : point.getPattern();
        String[] meaningParts = splitFirstLine(point == null ? null : point.getMeaning());
        String[] exampleParts = splitFirstLine(point == null ? null : point.getExample());
        List<ComponentToken> components = pattern == null ? List.of()
                : GrammarTextAnalyzer.classifyPattern(pattern).stream()
                        .map(t -> new ComponentToken(t.text(), t.role().name())).toList();
        ExampleView example = exampleParts[0] == null ? null : new ExampleView(exampleParts[0], exampleParts[1]);

        List<String> words = exampleParts[0] == null ? List.of() : GrammarTextAnalyzer.splitWords(exampleParts[0]);
        boolean orderingDone = p != null && p.isOrderingDone();
        boolean fillDone = p != null && p.isFillDone();
        OrderingPractice ordering = buildOrderingView(member.getCanonicalId(), words, orderingDone);
        FillPractice fill = buildFillView(words, fillDone);

        boolean gateSatisfied = orderingDone && fillDone;
        String note = point == null || point.getNote() == null || point.getNote().isBlank() ? null : point.getNote();
        return new GrammarPointView(member.getCanonicalId(), member.getOrderIndex(), pattern, meaningParts[0],
                meaningParts[1], components, example, note, ordering, fill, gateSatisfied);
    }

    private OrderingPractice buildOrderingView(long seed, List<String> words, boolean completed) {
        if (words.isEmpty()) return new OrderingPractice(List.of(), completed);
        List<Integer> shuffled = GrammarTextAnalyzer.shuffledOrder(seed, words.size());
        List<OrderingWord> view = shuffled.stream().map(idx -> new OrderingWord(idx, words.get(idx))).toList();
        return new OrderingPractice(view, completed);
    }

    private FillPractice buildFillView(List<String> words, boolean completed) {
        if (words.isEmpty()) return new FillPractice(null, completed);
        int blankIndex = GrammarTextAnalyzer.chooseBlankIndex(words);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(i == blankIndex ? "_____" : words.get(i));
        }
        return new FillPractice(sb.toString(), completed);
    }

    private List<String> exampleWords(GrammarPoint point) {
        String[] parts = splitFirstLine(point.getExample());
        return parts[0] == null ? List.of() : GrammarTextAnalyzer.splitWords(parts[0]);
    }

    private String meaningExplanation(GrammarPoint point) {
        String summary = splitFirstLine(point.getMeaning())[0];
        return summary == null ? "" : "Mẫu “" + point.getPattern() + "” nghĩa là: " + summary;
    }

    /** Splits a {@code meaning}/{@code example} canonical field on its first newline: index 0 is the short
     * first line, index 1 is everything after it ({@code null} when there is nothing after). Never invents
     * content beyond what the canonical row already has. */
    private static String[] splitFirstLine(String text) {
        if (text == null || text.isBlank()) return new String[]{null, null};
        int idx = text.indexOf('\n');
        if (idx < 0) return new String[]{text.trim(), null};
        String first = text.substring(0, idx).trim();
        String rest = text.substring(idx + 1).trim();
        return new String[]{first, rest.isEmpty() ? null : rest};
    }

    // ---- Content adapter for the micro-check half (engine-owned state machine, Grammar-owned content) ----

    /** [JLAS-W0193] Task 6 — Grammar's seam into {@link InteractiveStudyEngine}: supplies canonical loading
     * and deterministic, no-AI question generation, and swaps the flip/remember eligibility gate for the
     * ordering+fill practice-progress gate via {@link #requiresFlipRememberGate()} /
     * {@link #assertPracticeGateSatisfied}. The attempt/cooldown/review/idempotent-submit machinery itself
     * is not touched — it stays entirely inside the engine. */
    static final class GrammarContentAdapter implements InteractiveContentAdapter<GrammarPoint> {
        private final GrammarPointRepository grammarPoints;
        private final LearningGrammarPracticeProgressRepository progress;

        GrammarContentAdapter(GrammarPointRepository grammarPoints, LearningGrammarPracticeProgressRepository progress) {
            this.grammarPoints = grammarPoints;
            this.progress = progress;
        }

        @Override
        public LearningActivityType activityType() {
            return LearningActivityType.GRAMMAR;
        }

        @Override
        public LearningMemberType memberType() {
            return LearningMemberType.GRAMMAR_POINT;
        }

        @Override
        public String microCheckType() {
            return MICRO_CHECK_TYPE;
        }

        @Override
        public Map<Long, GrammarPoint> loadByIds(List<Long> canonicalIds) {
            return grammarPoints.findAllById(canonicalIds).stream()
                    .collect(Collectors.toMap(GrammarPoint::getId, g -> g));
        }

        @Override
        public boolean requiresFlipRememberGate() {
            return false;
        }

        @Override
        public void assertPracticeGateSatisfied(Long activityId, List<LearningActivityMember> membership) {
            for (LearningActivityMember member : membership) {
                var state = progress.findByActivityIdAndGrammarPointId(activityId, member.getCanonicalId())
                        .filter(p -> p.getSourceRevision().equals(member.getSourceRevision()))
                        .orElse(null);
                if (state == null || !state.isGateSatisfied()) {
                    throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                            "Hãy hoàn thành luyện tập sắp xếp câu và điền từ trước khi kiểm tra.");
                }
            }
        }

        @Override
        public List<QuestionSpec> buildQuestions(List<GrammarPoint> batch, int batchIndex, int variationSeed) {
            List<QuestionSpec> specs = new ArrayList<>();
            long qid = 1;
            for (int i = 0; i < batch.size(); i++) {
                GrammarPoint point = batch.get(i);
                String[] meaningParts = splitFirstLine(point.getMeaning());
                String[] exampleParts = splitFirstLine(point.getExample());
                String meaningSummary = meaningParts[0];
                String exampleJapanese = exampleParts[0];

                if (meaningSummary != null) {
                    List<String> distractors = batch.stream().map(b -> splitFirstLine(b.getMeaning())[0]).toList();
                    specs.add(optionQuestion(qid++, i + variationSeed, "MEANING_MATCH",
                            "Mẫu “" + point.getPattern() + "” có nghĩa là gì?", meaningSummary, distractors));
                }
                if (exampleJapanese != null) {
                    List<String> distractors = batch.stream().map(b -> splitFirstLine(b.getExample())[0]).toList();
                    specs.add(optionQuestion(qid++, i + variationSeed, "USAGE_SELECT",
                            "Câu nào dùng đúng mẫu “" + point.getPattern() + "”?", exampleJapanese, distractors));
                }
            }
            return specs;
        }

        private static QuestionSpec optionQuestion(long questionId, int rotateSeed, String type, String prompt,
                String correctValue, List<String> distractorValues) {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            if (correctValue != null) set.add(correctValue);
            for (String d : distractorValues) {
                if (d != null && !d.isEmpty() && !d.equals(correctValue)) set.add(d);
            }
            List<String> options = new ArrayList<>(set);
            int size = Math.min(4, options.size());
            options = new ArrayList<>(options.subList(0, size));
            int rotate = rotateSeed % size;
            List<String> rotated = new ArrayList<>(size);
            for (int k = 0; k < size; k++) rotated.add(options.get((k + rotate) % size));
            int correctOption = (options.indexOf(correctValue) - rotate + size) % size + 1;
            return new QuestionSpec(questionId, type, prompt, rotated, correctOption, correctValue);
        }
    }
}
