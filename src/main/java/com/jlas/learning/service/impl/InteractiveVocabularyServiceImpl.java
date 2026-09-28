package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.dto.VocabularyStudyState;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.LearningStudyCard;
import com.jlas.learning.entity.LearningStudySession;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.InteractiveVocabularyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** [JLAS-W0193] Vocabulary study + micro-check engine. Every mutating method locks the owned activity row
 * first so concurrent tabs cannot double-spend attempts or duplicate a study session. */
@Service
@RequiredArgsConstructor
public class InteractiveVocabularyServiceImpl implements InteractiveVocabularyService {
    private static final String MICRO_CHECK_TYPE = "VOCAB_MICRO_CHECK";
    private static final int PASS_THRESHOLD = 70;
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 10;
    private static final int COOLDOWN_MINUTES = 60;
    private static final String[] SELECT_TYPES = {"WORD_TO_MEANING", "READING_TO_WORD", "MEANING_TO_WORD"};

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningStudySessionRepository studySessions;
    private final LearningStudyCardRepository studyCards;
    private final LearningMicroCheckStateRepository checkStates;
    private final LearningQuizSessionRepository quizSessions;
    private final VocabularyEntryRepository vocabularyRepo;
    private final LearningMapper mapper;

    // ---- Study ----

    @Override
    @Transactional
    public VocabularyStudyState studyState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningStudySession session = getOrCreateSession(activity, revisionOf(membership), membership);
        return buildStudyState(activity, session, membership);
    }

    @Override
    @Transactional
    public VocabularyStudyState flip(Long userId, Long activityId, Long memberId) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningStudySession session = getOrCreateSession(activity, revisionOf(membership), membership);
        LearningStudyCard card = studyCards.findBySessionIdAndCanonicalId(session.getId(), memberId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Thẻ không thuộc phiên học này."));
        if (!card.isFlipped()) {
            card.setFlipped(true);
            card.setFlippedAt(LocalDateTime.now());
        }
        return buildStudyState(activity, session, membership);
    }

    @Override
    @Transactional
    public VocabularyStudyState remember(Long userId, Long activityId, Long memberId, boolean remembered) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningStudySession session = getOrCreateSession(activity, revisionOf(membership), membership);
        LearningStudyCard card = studyCards.findBySessionIdAndCanonicalId(session.getId(), memberId)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Thẻ không thuộc phiên học này."));
        if (!card.isFlipped()) {
            throw new ApiException(ErrorCode.CONFLICT, "Hãy lật thẻ trước khi chọn.");
        }
        if (remembered) {
            if (!card.isRemembered()) {
                card.setRemembered(true);
                card.setRememberedAt(LocalDateTime.now());
            }
        } else {
            // Unremembered card returns to the review queue: it must be flipped again before choosing.
            card.setRemembered(false);
            card.setRememberedAt(null);
            card.setFlipped(false);
            card.setFlippedAt(null);
        }
        return buildStudyState(activity, session, membership);
    }

    // ---- Micro-check ----

    @Override
    @Transactional
    public MicroCheckResponse issueMicroCheck(Long userId, Long activityId) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        requireAllRemembered(activityId, membership);

        int batchCount = batchCount(membership.size());
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        int currentBatch = firstUnpassedBatch(states, batchCount);
        if (currentBatch >= batchCount) {
            throw new ApiException(ErrorCode.CONFLICT, "Phần từ vựng này đã hoàn thành.");
        }
        LearningMicroCheckState state = stateForBatch(states, currentBatch);
        LocalDateTime now = LocalDateTime.now();
        if (state != null && !canAttempt(state, now)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Bạn đang trong thời gian chờ. Hãy ôn lại phần vừa sai và quay lại sau.");
        }

        Map<Long, VocabularyEntry> vocabById = loadVocabById(membership);
        List<VocabularyEntry> batch = batchEntries(vocabById, membership, currentBatch);
        List<QuestionSpec> specs = buildQuestions(batch, currentBatch);

        LearningQuizSession snapshot = LearningQuizSession.builder()
                .user(activity.getLearningPath().getUser())
                .quizType(MICRO_CHECK_TYPE)
                .activityId(activity.getId())
                .batchIndex(currentBatch)
                .questionCount(specs.size())
                .build();
        snapshot.setQuestionsJson(mapper.writeQuizQuestions(toLessonQuizQuestions(specs)));
        snapshot.setAnswerKeyJson(mapper.writeQuizAnswerKey(toAnswerKey(specs)));
        snapshot = quizSessions.save(snapshot);

        return new MicroCheckResponse(snapshot.getId(), activity.getId(), currentBatch, batchCount,
                specs.size(), toMicroCheckQuestions(specs));
    }

    @Override
    @Transactional
    public MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        LearningQuizSession session = quizSessions.findOwnedByActivity(
                        request.attemptId(), userId, MICRO_CHECK_TYPE, activityId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Phiên kiểm tra không hợp lệ."));
        if ("SUBMITTED".equals(session.getStatus())) {
            return reconstructResult(session, activity);
        }
        Integer batchIndex = session.getBatchIndex();
        if (batchIndex == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Phiên kiểm tra thiếu batch.");
        }

        LearningMicroCheckState state = checkStates.findForUpdate(activityId, batchIndex).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (state != null && state.isPassed()) {
            throw new ApiException(ErrorCode.CONFLICT, "Phần này đã hoàn thành.");
        }
        if (state != null && !canAttempt(state, now)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Bạn chưa thể nộp lần này. Hãy ôn lại phần vừa sai và quay lại sau.");
        }

        Grade grade = grade(session, request);
        if (state == null) {
            state = LearningMicroCheckState.builder().activity(activity).batchIndex(batchIndex)
                    .cycleNumber(1).attemptsUsed(0).passed(false).reviewRequired(false).build();
        }
        if (state.getAttemptsUsed() >= MAX_ATTEMPTS) {
            state.setCycleNumber(state.getCycleNumber() + 1);
            state.setAttemptsUsed(0);
            state.setCooldownUntil(null);
            state.setReviewRequired(false);
        }
        state.setAttemptsUsed(state.getAttemptsUsed() + 1);
        if (state.getFirstScore() == null) state.setFirstScore(grade.score());
        state.setLatestScore(grade.score());
        state.setBestScore(Math.max(state.getBestScore() == null ? 0 : state.getBestScore(), grade.score()));
        if (grade.passed()) {
            state.setPassed(true);
            state.setCooldownUntil(null);
            state.setReviewRequired(false);
        } else if (state.getAttemptsUsed() >= MAX_ATTEMPTS) {
            state.setCooldownUntil(now.plusMinutes(COOLDOWN_MINUTES));
            state.setReviewRequired(true);
        }
        checkStates.save(state);

        session.setStatus("SUBMITTED");
        session.setSubmittedAt(now);
        session.setQuestionCount(grade.total());
        session.setCorrectCount(grade.correct());
        session.setScorePercent(grade.score());
        session.setPassed(grade.passed());
        LessonQuizResult stored = new LessonQuizResult(grade.total(), grade.correct(), grade.score(),
                grade.passed(), PASS_THRESHOLD, false, grade.review(), List.of());
        session.setResultJson(mapper.writeQuizResult(stored));
        quizSessions.save(session);

        List<LearningActivityMember> membership = loadMembership(activity);
        int batchCount = batchCount(membership.size());
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        boolean allPassed = allBatchesPassed(states, batchCount);
        LearningActivityState activityState = refreshActivityState(activity, state, allPassed, now);

        return new MicroCheckResult(grade.total(), grade.correct(), grade.score(), grade.passed(),
                PASS_THRESHOLD, state.getAttemptsUsed(), attemptsRemaining(state.getAttemptsUsed()),
                state.getCooldownUntil(), state.isReviewRequired(), activityState, grade.review());
    }

    @Override
    @Transactional
    public MicroCheckStateResponse microCheckState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        return buildStateResponse(activity);
    }

    @Override
    @Transactional
    public MicroCheckStateResponse completeReview(Long userId, Long activityId) {
        UserLearningPathItem activity = loadVocabActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        int batchCount = batchCount(membership.size());
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        int currentBatch = firstUnpassedBatch(states, batchCount);
        LearningMicroCheckState state = stateForBatch(states, currentBatch);
        if (state == null || !state.isReviewRequired()) {
            throw new ApiException(ErrorCode.CONFLICT, "Không có phần cần ôn lại.");
        }
        state.setReviewRequired(false);
        checkStates.save(state);
        return buildStateResponse(activity);
    }

    // ---- Internal helpers ----

    private UserLearningPathItem loadVocabActivityForUpdate(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findOwnedByIdForUpdate(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != LearningActivityType.VOCABULARY) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return activity;
    }

    private List<LearningActivityMember> loadMembership(UserLearningPathItem activity) {
        List<LearningActivityMember> list = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .filter(m -> m.getMemberType() == LearningMemberType.VOCABULARY_ENTRY)
                .toList();
        if (list.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có danh sách từ vựng.");
        }
        return list;
    }

    private String revisionOf(List<LearningActivityMember> membership) {
        return membership.getFirst().getSourceRevision();
    }

    private LearningStudySession getOrCreateSession(UserLearningPathItem activity, String revision,
            List<LearningActivityMember> membership) {
        LearningStudySession session = studySessions.findByActivityId(activity.getId()).orElse(null);
        if (session != null && !revision.equals(session.getSourceRevision())) {
            studyCards.deleteAll(studyCards.findBySessionIdOrderByOrderIndex(session.getId()));
            session.setSourceRevision(revision);
        }
        if (session == null) {
            session = LearningStudySession.builder().activity(activity).sourceRevision(revision).build();
        }
        session = studySessions.save(session);
        ensureCards(session, membership);
        return session;
    }

    private void ensureCards(LearningStudySession session, List<LearningActivityMember> membership) {
        Set<Long> existing = studyCards.findBySessionIdOrderByOrderIndex(session.getId()).stream()
                .map(LearningStudyCard::getCanonicalId).collect(Collectors.toSet());
        for (LearningActivityMember member : membership) {
            if (!existing.contains(member.getCanonicalId())) {
                studyCards.save(LearningStudyCard.builder().session(session).canonicalId(member.getCanonicalId())
                        .orderIndex(member.getOrderIndex()).build());
            }
        }
    }

    private VocabularyStudyState buildStudyState(UserLearningPathItem activity, LearningStudySession session,
            List<LearningActivityMember> membership) {
        List<LearningStudyCard> cards = studyCards.findBySessionIdOrderByOrderIndex(session.getId());
        Map<Long, LearningStudyCard> cardById = cards.stream()
                .collect(Collectors.toMap(LearningStudyCard::getCanonicalId, c -> c));
        Map<Long, VocabularyEntry> vocabById = loadVocabById(membership);

        List<VocabularyStudyState.VocabularyStudyCard> views = new ArrayList<>();
        int flipped = 0;
        int remembered = 0;
        for (LearningActivityMember member : membership) {
            VocabularyEntry entry = vocabById.get(member.getCanonicalId());
            LearningStudyCard card = cardById.get(member.getCanonicalId());
            boolean isFlipped = card != null && card.isFlipped();
            boolean isRemembered = card != null && card.isRemembered();
            if (isFlipped) flipped++;
            if (isRemembered) remembered++;
            views.add(new VocabularyStudyState.VocabularyStudyCard(member.getCanonicalId(), member.getOrderIndex(),
                    value(entry, VocabularyEntry::getExpression), value(entry, VocabularyEntry::getReading),
                    value(entry, VocabularyEntry::getMeaning), value(entry, VocabularyEntry::getWordType),
                    value(entry, VocabularyEntry::getJlptLevel), value(entry, VocabularyEntry::getExampleJa),
                    value(entry, VocabularyEntry::getExampleVi), isFlipped, isRemembered));
        }
        boolean checkEligible = !membership.isEmpty() && remembered == membership.size();
        if (checkEligible && activity.getActivityState() == LearningActivityState.STUDYING) {
            activity.setActivityState(LearningActivityState.CHECK_READY);
        }
        return new VocabularyStudyState(activity.getId(), activity.getActivityType(), activity.getActivityState(),
                session.getSourceRevision(), membership.size(), flipped, remembered, membership.size() - remembered,
                checkEligible, views);
    }

    private static String value(VocabularyEntry entry, java.util.function.Function<VocabularyEntry, String> getter) {
        return entry == null ? null : getter.apply(entry);
    }

    private void requireAllRemembered(Long activityId, List<LearningActivityMember> membership) {
        LearningStudySession session = studySessions.findByActivityId(activityId).orElse(null);
        Set<Long> remembered = session == null ? Set.of()
                : studyCards.findBySessionIdOrderByOrderIndex(session.getId()).stream()
                        .filter(LearningStudyCard::isRemembered).map(LearningStudyCard::getCanonicalId)
                        .collect(Collectors.toSet());
        boolean all = membership.stream().allMatch(m -> remembered.contains(m.getCanonicalId()));
        if (!all) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Hãy nhớ hết thẻ từ vựng trước khi kiểm tra.");
        }
    }

    private Map<Long, VocabularyEntry> loadVocabById(List<LearningActivityMember> membership) {
        List<Long> ids = membership.stream().map(LearningActivityMember::getCanonicalId).distinct().toList();
        return vocabularyRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(VocabularyEntry::getId, v -> v));
    }

    private int batchCount(int size) {
        return (size + BATCH_SIZE - 1) / BATCH_SIZE;
    }

    private List<VocabularyEntry> batchEntries(Map<Long, VocabularyEntry> vocabById,
            List<LearningActivityMember> membership, int batchIndex) {
        int from = batchIndex * BATCH_SIZE;
        int to = Math.min(membership.size(), from + BATCH_SIZE);
        List<VocabularyEntry> batch = new ArrayList<>();
        for (int i = from; i < to; i++) {
            batch.add(vocabById.get(membership.get(i).getCanonicalId()));
        }
        return batch;
    }

    private int firstUnpassedBatch(List<LearningMicroCheckState> states, int batchCount) {
        for (int b = 0; b < batchCount; b++) {
            LearningMicroCheckState s = stateForBatch(states, b);
            if (s == null || !s.isPassed()) return b;
        }
        return batchCount;
    }

    private LearningMicroCheckState stateForBatch(List<LearningMicroCheckState> states, int batch) {
        return states.stream().filter(s -> s.getBatchIndex() == batch).findFirst().orElse(null);
    }

    private boolean allBatchesPassed(List<LearningMicroCheckState> states, int batchCount) {
        return firstUnpassedBatch(states, batchCount) >= batchCount;
    }

    private boolean canAttempt(LearningMicroCheckState state, LocalDateTime now) {
        if (state.isPassed()) return false;
        if (state.getAttemptsUsed() < MAX_ATTEMPTS) return true;
        return !state.isReviewRequired() && state.getCooldownUntil() != null && !now.isBefore(state.getCooldownUntil());
    }

    private int attemptsRemaining(int attemptsUsed) {
        return Math.max(0, MAX_ATTEMPTS - attemptsUsed);
    }

    private LearningActivityState refreshActivityState(UserLearningPathItem activity, LearningMicroCheckState state,
            boolean allPassed, LocalDateTime now) {
        LearningActivityState effective;
        if (allPassed) effective = LearningActivityState.PASSED;
        else if (state == null || canAttempt(state, now)) effective = LearningActivityState.CHECK_READY;
        else effective = LearningActivityState.COOLDOWN;
        activity.setActivityState(effective);
        return effective;
    }

    private MicroCheckStateResponse buildStateResponse(UserLearningPathItem activity) {
        List<LearningActivityMember> membership = loadMembership(activity);
        int batchCount = batchCount(membership.size());
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activity.getId());
        int currentBatch = firstUnpassedBatch(states, batchCount);
        LearningMicroCheckState state = stateForBatch(states, currentBatch);
        boolean allPassed = currentBatch >= batchCount;
        LocalDateTime now = LocalDateTime.now();
        LearningActivityState activityState = refreshActivityState(activity, state, allPassed, now);
        int attemptsUsed = state == null ? 0 : state.getAttemptsUsed();
        boolean checkEligible = !allPassed && (state == null || canAttempt(state, now));
        return new MicroCheckStateResponse(activity.getId(), activityState, batchCount, currentBatch,
                attemptsUsed, attemptsRemaining(attemptsUsed), state == null ? null : state.getCooldownUntil(),
                state != null && state.isReviewRequired(), checkEligible, allPassed,
                state == null ? null : state.getFirstScore(), state == null ? null : state.getLatestScore(),
                state == null ? null : state.getBestScore());
    }

    private MicroCheckResult reconstructResult(LearningQuizSession session, UserLearningPathItem activity) {
        LessonQuizResult stored = mapper.readQuizResult(session.getResultJson());
        LearningMicroCheckState state = session.getBatchIndex() == null ? null
                : checkStates.findByActivityIdAndBatchIndex(activity.getId(), session.getBatchIndex()).orElse(null);
        int attemptsUsed = state == null ? 0 : state.getAttemptsUsed();
        return new MicroCheckResult(stored.totalQuestions(), stored.correctAnswers(), stored.scorePercent(),
                stored.passed(), stored.passThresholdPercent(), attemptsUsed, attemptsRemaining(attemptsUsed),
                state == null ? null : state.getCooldownUntil(), state != null && state.isReviewRequired(),
                activity.getActivityState(), stored.review());
    }

    private Grade grade(LearningQuizSession session, MicroCheckSubmitRequest request) {
        Map<Long, QuizAnswerKey> key = mapper.readQuizAnswerKey(session.getAnswerKeyJson());
        Map<Long, Integer> selected = request.answers().stream()
                .collect(Collectors.toMap(MicroCheckSubmitRequest.MicroCheckAnswer::questionId,
                        MicroCheckSubmitRequest.MicroCheckAnswer::selectedOption, (a, b) -> a));
        int correct = 0;
        List<LessonQuizResult.AnswerReview> review = new ArrayList<>();
        for (Map.Entry<Long, QuizAnswerKey> entry : key.entrySet()) {
            Long qid = entry.getKey();
            QuizAnswerKey k = entry.getValue();
            Integer sel = selected.get(qid);
            boolean isCorrect = sel != null && sel.equals(k.correctOption());
            if (isCorrect) correct++;
            review.add(new LessonQuizResult.AnswerReview(qid, k.correctOption(), sel, isCorrect, k.explanation()));
        }
        int total = key.size();
        int score = total == 0 ? 0 : Math.round(correct * 100f / total);
        return new Grade(total, correct, score, score >= PASS_THRESHOLD, review);
    }

    private record Grade(int total, int correct, int score, boolean passed, List<LessonQuizResult.AnswerReview> review) { }

    // ---- Question generation (deterministic; no AI, no runtime scoring) ----

    private List<QuestionSpec> buildQuestions(List<VocabularyEntry> batch, int batchIndex) {
        List<QuestionSpec> specs = new ArrayList<>();
        long qid = 1;
        for (int i = 0; i < batch.size(); i++) {
            VocabularyEntry m = batch.get(i);
            String type = SELECT_TYPES[i % SELECT_TYPES.length];
            specs.add(optionQuestion(qid++, i, type, selectPrompt(type, m), selectCorrect(type, m),
                    selectDistractors(type, m, batch)));
        }
        for (int i = 0; i < batch.size(); i++) {
            VocabularyEntry m = batch.get(i);
            specs.add(optionQuestion(qid++, i, "MATCHING",
                    "Ghép từ “" + m.getExpression() + "” với nghĩa đúng.", m.getMeaning(),
                    batch.stream().map(VocabularyEntry::getMeaning).toList()));
        }
        return specs;
    }

    private static String selectPrompt(String type, VocabularyEntry m) {
        return switch (type) {
            case "WORD_TO_MEANING" -> "Từ “" + m.getExpression() + "” có nghĩa là gì?";
            case "READING_TO_WORD" -> "Từ nào có cách đọc “" + m.getReading() + "”?";
            case "MEANING_TO_WORD" -> "Từ nào có nghĩa “" + m.getMeaning() + "”?";
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static String selectCorrect(String type, VocabularyEntry m) {
        return switch (type) {
            case "WORD_TO_MEANING" -> m.getMeaning();
            case "READING_TO_WORD", "MEANING_TO_WORD" -> m.getExpression();
            default -> throw new IllegalArgumentException(type);
        };
    }

    private static List<String> selectDistractors(String type, VocabularyEntry m, List<VocabularyEntry> batch) {
        return switch (type) {
            case "WORD_TO_MEANING" -> batch.stream().map(VocabularyEntry::getMeaning).toList();
            case "READING_TO_WORD", "MEANING_TO_WORD" -> batch.stream().map(VocabularyEntry::getExpression).toList();
            default -> throw new IllegalArgumentException(type);
        };
    }

    private QuestionSpec optionQuestion(long questionId, int rotateSeed, String type, String prompt,
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
        // 1-based option index, matching the rest of the quiz system (@Min(1) submit + LessonQuiz grading).
        int correctOption = (options.indexOf(correctValue) - rotate + size) % size + 1;
        return new QuestionSpec(questionId, type, prompt, rotated, correctOption, correctValue);
    }

    private record QuestionSpec(long questionId, String type, String prompt, List<String> options,
            int correctOption, String explanation) { }

    private List<LessonQuizResponse.LessonQuizQuestion> toLessonQuizQuestions(List<QuestionSpec> specs) {
        return specs.stream().map(s -> new LessonQuizResponse.LessonQuizQuestion(s.questionId(), "VOCABULARY",
                null, null, null, null, null, s.prompt(), s.options(), s.type())).toList();
    }

    private Map<Long, QuizAnswerKey> toAnswerKey(List<QuestionSpec> specs) {
        return specs.stream().collect(Collectors.toMap(QuestionSpec::questionId,
                s -> new QuizAnswerKey(s.correctOption(), s.explanation(), null, null)));
    }

    private List<MicroCheckResponse.MicroCheckQuestion> toMicroCheckQuestions(List<QuestionSpec> specs) {
        return specs.stream().map(s -> new MicroCheckResponse.MicroCheckQuestion(s.questionId(), s.type(),
                s.prompt(), s.options())).toList();
    }
}
