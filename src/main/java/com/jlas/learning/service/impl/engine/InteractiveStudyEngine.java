package com.jlas.learning.service.impl.engine;

import com.jlas.learning.service.impl.InteractiveActivityStatePolicy;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.AttemptFeedbackResponse;
import com.jlas.learning.dto.LessonQuizResponse;
import com.jlas.learning.dto.LessonQuizResult;
import com.jlas.learning.dto.MicroCheckResponse;
import com.jlas.learning.dto.MicroCheckResult;
import com.jlas.learning.dto.MicroCheckStateResponse;
import com.jlas.learning.dto.MicroCheckSubmitRequest;
import com.jlas.learning.dto.QuizAnswerKey;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningMicroCheckState;
import com.jlas.learning.entity.LearningQuizSession;
import com.jlas.learning.entity.LearningStudyCard;
import com.jlas.learning.entity.LearningStudySession;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningMicroCheckStateRepository;
import com.jlas.learning.repository.LearningQuizSessionRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** [JLAS-W0193] Content-agnostic study + micro-check state machine, extracted from the original
 * {@code InteractiveVocabularyServiceImpl} (Task 4) so Kanji (Task 5) and later content types can reuse the
 * exact same contract — flip-before-remember with resume, immutable snapshot, server-owned eligibility/score/
 * attempt-cycle/cooldown/review, 70% pass, 3 valid submits per cycle, idempotent submit — without copying the
 * state machine. A concrete {@link InteractiveContentAdapter} supplies only what varies: which activity/member
 * type this content owns, how to bulk-load canonical entities, and how to build questions from a batch.
 *
 * <p>Not a Spring bean: each concrete service (e.g. {@code InteractiveVocabularyServiceImpl}) owns one instance,
 * built from its own injected repositories, and exposes its own {@code @Transactional} methods that delegate
 * here — this keeps the transaction boundary and constructor-injection wiring identical to before the extract,
 * so existing Vocabulary tests and controllers need no change. */
public class InteractiveStudyEngine<T> {
    private static final int PASS_THRESHOLD = 70;
    private static final int MAX_ATTEMPTS = 3;
    private static final int COOLDOWN_MINUTES = 60;

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningStudySessionRepository studySessions;
    private final LearningStudyCardRepository studyCards;
    private final LearningMicroCheckStateRepository checkStates;
    private final LearningQuizSessionRepository quizSessions;
    private final LearningMapper mapper;
    private final InteractiveContentAdapter<T> adapter;

    public InteractiveStudyEngine(UserLearningPathItemRepository items, LearningActivityMemberRepository members,
            LearningStudySessionRepository studySessions, LearningStudyCardRepository studyCards,
            LearningMicroCheckStateRepository checkStates, LearningQuizSessionRepository quizSessions,
            LearningMapper mapper, InteractiveContentAdapter<T> adapter) {
        this.items = items;
        this.members = members;
        this.studySessions = studySessions;
        this.studyCards = studyCards;
        this.checkStates = checkStates;
        this.quizSessions = quizSessions;
        this.mapper = mapper;
        this.adapter = adapter;
    }

    // ---- Study ----

    public EngineStudyState<T> studyState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningStudySession session = getOrCreateSession(activity, revisionOf(membership), membership);
        return buildStudyState(activity, session, membership);
    }

    public EngineStudyState<T> flip(Long userId, Long activityId, Long memberId) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
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

    public EngineStudyState<T> remember(Long userId, Long activityId, Long memberId, boolean remembered) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
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

    public MicroCheckResponse issueMicroCheck(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        if (adapter.requiresFlipRememberGate()) {
            requireAllRemembered(activityId, membership);
        } else {
            adapter.assertPracticeGateSatisfied(activityId, membership);
        }

        List<List<T>> batches = contentBatches(membership);
        int batchCount = batches.size();
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        int currentBatch = firstUnpassedBatch(states, batchCount);
        if (currentBatch >= batchCount) {
            throw new ApiException(ErrorCode.CONFLICT, "Phần này đã hoàn thành.");
        }
        LearningMicroCheckState state = stateForBatch(states, currentBatch);
        LocalDateTime now = LocalDateTime.now();
        if (state != null && !canAttempt(state, now)) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Bạn đang trong thời gian chờ. Hãy ôn lại phần vừa sai và quay lại sau.");
        }

        List<T> batch = batches.get(currentBatch);
        // [JLAS-W0193] Task 10 audit finding — vary the deterministic question arrangement between successive
        // issues of the same still-unpassed batch (server-derived from the state's own attempt count, never
        // client input), so a revealed answer key from a failed attempt's review cannot be replayed verbatim
        // on the very next attempt. See InteractiveContentAdapter.buildQuestions' javadoc for the full finding.
        int variationSeed = state == null ? 0 : state.getAttemptsUsed();
        List<QuestionSpec> specs = adapter.buildQuestions(batch, currentBatch, variationSeed);

        LearningQuizSession snapshot = LearningQuizSession.builder()
                .user(activity.getLearningPath().getUser())
                .quizType(adapter.microCheckType())
                .activityId(activity.getId())
                .batchIndex(currentBatch)
                .questionCount(specs.size())
                .build();
        snapshot.setQuestionsJson(mapper.writeQuizQuestions(toLessonQuizQuestions(specs)));
        snapshot.setAnswerKeyJson(mapper.writeQuizAnswerKey(toAnswerKey(specs)));
        snapshot.setSourceSnapshotJson(adapter.sourceSnapshotJson(batch, currentBatch, revisionOf(membership)));
        snapshot = quizSessions.save(snapshot);

        return new MicroCheckResponse(snapshot.getId(), activity.getId(), currentBatch, batchCount,
                specs.size(), toMicroCheckQuestions(specs));
    }

    public MicroCheckResult submitMicroCheck(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
        LearningQuizSession session = quizSessions.findOwnedByActivity(
                        request.attemptId(), userId, adapter.microCheckType(), activityId)
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
        int batchCount = batchCount(membership);
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        boolean allPassed = allBatchesPassed(states, batchCount);
        int nextBatch = firstUnpassedBatch(states, batchCount);
        LearningMicroCheckState effectiveState = allPassed ? state : stateForBatch(states, nextBatch);
        LearningActivityState activityState = refreshActivityState(activity, effectiveState, allPassed, now);

        return new MicroCheckResult(grade.total(), grade.correct(), grade.score(), grade.passed(),
                PASS_THRESHOLD, state.getAttemptsUsed(), attemptsRemaining(state.getAttemptsUsed()),
                state.getCooldownUntil(), state.isReviewRequired(), activityState, grade.review());
    }

    public MicroCheckStateResponse microCheckState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
        return buildStateResponse(activity);
    }

    public MicroCheckStateResponse completeReview(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        List<List<T>> batches = adapter.requiresServerReviewEvidence() ? contentBatches(membership) : List.of();
        int batchCount = batchCount(membership);
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        int currentBatch = firstUnpassedBatch(states, batchCount);
        LearningMicroCheckState state = stateForBatch(states, currentBatch);
        if (state == null || !state.isReviewRequired()) {
            throw new ApiException(ErrorCode.CONFLICT, "Không có phần cần ôn lại.");
        }
        if (adapter.requiresServerReviewEvidence()) {
            requirePostFailureReviewEvidence(activity, batches.get(currentBatch), state);
        }
        state.setReviewRequired(false);
        checkStates.save(state);
        return buildStateResponse(activity);
    }

    /** Records a source interaction for the current comprehension exercise. The server chooses the current
     * batch and timestamp; callers cannot submit a duration, source id, or completion time. */
    public MicroCheckStateResponse recordReviewEvidence(Long userId, Long activityId) {
        if (!adapter.requiresServerReviewEvidence()) throw new ApiException(ErrorCode.NOT_FOUND);
        UserLearningPathItem activity = loadActivityForUpdate(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        List<List<T>> batches = contentBatches(membership);
        List<LearningMicroCheckState> states = checkStates.findByActivityIdOrderByBatchIndex(activityId);
        int currentBatch = firstUnpassedBatch(states, batches.size());
        if (currentBatch >= batches.size()) throw new ApiException(ErrorCode.CONFLICT, "Phần này đã hoàn thành.");
        LearningStudySession session = getOrCreateSession(activity, revisionOf(membership), membership);
        int offset = 0;
        for (int index = 0; index < currentBatch; index++) offset += batches.get(index).size();
        Set<Long> currentIds = membership.subList(offset, offset + batches.get(currentBatch).size()).stream()
                .map(LearningActivityMember::getCanonicalId).collect(Collectors.toSet());
        LocalDateTime now = LocalDateTime.now();
        for (LearningStudyCard card : studyCards.findBySessionIdOrderByOrderIndex(session.getId())) {
            if (!currentIds.contains(card.getCanonicalId())) continue;
            card.setFlipped(true);
            card.setFlippedAt(now);
            studyCards.save(card);
        }
        return buildStateResponse(activity);
    }

    // ---- Review / practice (Task 10) ----

    /** Quiz type of a non-credit replay; never matches {@link InteractiveContentAdapter#microCheckType()}, so a
     * replay is invisible to every completion, score and unlock query. */
    public static final String PRACTICE_REPLAY = "PRACTICE_REPLAY";

    /** REVIEW mode: read-only view of a finished activity. Writes nothing (no session, card, attempt or state
     * change) and takes no row lock; the content is shown as remembered because it is already passed. */
    public EngineStudyState<T> reviewState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadFinishedActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        Map<Long, T> byId = adapter.loadByIds(membership.stream()
                .map(LearningActivityMember::getCanonicalId).distinct().toList());
        List<EngineStudyState.EngineCard<T>> views = new ArrayList<>();
        for (LearningActivityMember member : membership) {
            views.add(new EngineStudyState.EngineCard<>(member.getCanonicalId(), member.getOrderIndex(),
                    byId.get(member.getCanonicalId()), true, true));
        }
        return new EngineStudyState<>(activity.getId(), activity.getActivityType(), activity.getActivityState(),
                revisionOf(membership), membership.size(), membership.size(), membership.size(), 0, false, views);
    }

    /** REVIEW feedback: the newest submitted micro-check attempt of every batch with its stored per-question
     * answers and explanations. Read-only; replays (PRACTICE_REPLAY) are a different quiz type and never appear. */
    public List<AttemptFeedbackResponse> reviewFeedback(Long userId, Long activityId) {
        return reviewEntries(userId, activityId).stream().map(FeedbackEntry::feedback).toList();
    }

    /** Feedback plus the frozen source each attempt was asked against (null for legacy sessions). */
    public record FeedbackEntry(AttemptFeedbackResponse feedback, String sourceSnapshotJson) { }

    public List<FeedbackEntry> reviewEntries(Long userId, Long activityId) {
        loadFinishedActivity(userId, activityId);
        Map<Integer, LearningQuizSession> latest = new java.util.TreeMap<>();
        for (LearningQuizSession session : quizSessions.findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                activityId, adapter.microCheckType(), "SUBMITTED")) {
            if (session.getBatchIndex() != null && session.getResultJson() != null) {
                latest.putIfAbsent(session.getBatchIndex(), session);
            }
        }
        List<FeedbackEntry> feedback = new ArrayList<>();
        for (LearningQuizSession session : latest.values()) {
            LessonQuizResult result = mapper.readQuizResult(session.getResultJson());
            Map<Long, String> prompts = new java.util.HashMap<>();
            for (LessonQuizResponse.LessonQuizQuestion question : mapper.readQuizQuestions(session.getQuestionsJson())) {
                prompts.put(question.questionId(), question.questionText());
            }
            List<AttemptFeedbackResponse.Item> items = result.review().stream()
                    .map(r -> new AttemptFeedbackResponse.Item(r.questionId(), prompts.get(r.questionId()),
                            r.correctOption(), r.selectedOption(), Boolean.TRUE.equals(r.correct()), r.explanation()))
                    .toList();
            feedback.add(new FeedbackEntry(new AttemptFeedbackResponse(session.getBatchIndex(), result.scorePercent(),
                    result.correctAnswers(), result.totalQuestions(), result.passed(), session.getSubmittedAt(), items),
                    session.getSourceSnapshotJson()));
        }
        return feedback;
    }

    /** PRACTICE mode: issues a replay quiz snapshot. It rotates through the activity's batches by how many
     * replays already exist and never reads or writes micro-check state. */
    public MicroCheckResponse issuePractice(Long userId, Long activityId) {
        return issuePracticeWithSource(userId, activityId).response();
    }

    /** A replay together with the frozen source of the replayed batch (Reading/Listening). */
    public record PracticeIssue(MicroCheckResponse response, String sourceSnapshotJson) { }

    public PracticeIssue issuePracticeWithSource(Long userId, Long activityId) {
        UserLearningPathItem activity = loadFinishedActivity(userId, activityId);
        Map<Integer, LearningQuizSession> latestByBatch = new java.util.TreeMap<>();
        for (LearningQuizSession original : quizSessions
                .findByActivityIdAndQuizTypeAndStatusOrderBySubmittedAtDesc(
                        activityId, adapter.microCheckType(), "SUBMITTED")) {
            if (original.getBatchIndex() != null) latestByBatch.putIfAbsent(original.getBatchIndex(), original);
        }
        if (latestByBatch.isEmpty()) {
            throw new ApiException(ErrorCode.SOURCE_SNAPSHOT_UNAVAILABLE);
        }
        int replays = (int) quizSessions.countByActivityIdAndQuizType(activityId, PRACTICE_REPLAY);
        List<LearningQuizSession> originals = new ArrayList<>(latestByBatch.values());
        LearningQuizSession original = originals.get(replays % originals.size());
        if (original.getQuestionsJson() == null || original.getAnswerKeyJson() == null
                || original.getSourceSnapshotJson() == null || original.getSourceSnapshotJson().isBlank()) {
            throw new ApiException(ErrorCode.SOURCE_SNAPSHOT_UNAVAILABLE);
        }
        List<LessonQuizResponse.LessonQuizQuestion> questions = mapper.readQuizQuestions(original.getQuestionsJson());

        LearningQuizSession snapshot = LearningQuizSession.builder()
                .user(activity.getLearningPath().getUser())
                .quizType(PRACTICE_REPLAY)
                .activityId(activity.getId())
                .batchIndex(original.getBatchIndex())
                .questionCount(questions.size())
                .build();
        snapshot.setQuestionsJson(original.getQuestionsJson());
        snapshot.setAnswerKeyJson(original.getAnswerKeyJson());
        snapshot.setSourceSnapshotJson(original.getSourceSnapshotJson());
        snapshot = quizSessions.save(snapshot);
        List<MicroCheckResponse.MicroCheckQuestion> responseQuestions = questions.stream()
                .map(q -> new MicroCheckResponse.MicroCheckQuestion(
                        q.questionId(), q.questionType(), q.questionText(), q.options()))
                .toList();
        return new PracticeIssue(new MicroCheckResponse(snapshot.getId(), activity.getId(), original.getBatchIndex(),
                originals.size(), questions.size(), responseQuestions), snapshot.getSourceSnapshotJson());
    }

    /** Grades a replay against its own snapshot. The score is stored on the replay row only: it does not
     * complete, reward, unlock, consume attempts or lower the activity state or any best score. */
    public MicroCheckResult submitPractice(Long userId, Long activityId, MicroCheckSubmitRequest request) {
        UserLearningPathItem activity = loadFinishedActivity(userId, activityId);
        LearningQuizSession session = quizSessions.findOwnedByActivity(
                        request.attemptId(), userId, PRACTICE_REPLAY, activityId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "Phiên luyện tập không hợp lệ."));
        if ("SUBMITTED".equals(session.getStatus())) {
            return practiceResult(mapper.readQuizResult(session.getResultJson()), activity);
        }
        Grade grade = grade(session, request);
        session.setStatus("SUBMITTED");
        session.setSubmittedAt(LocalDateTime.now());
        session.setQuestionCount(grade.total());
        session.setCorrectCount(grade.correct());
        session.setScorePercent(grade.score());
        session.setPassed(grade.passed());
        LessonQuizResult stored = new LessonQuizResult(grade.total(), grade.correct(), grade.score(),
                grade.passed(), PASS_THRESHOLD, false, grade.review(), List.of());
        session.setResultJson(mapper.writeQuizResult(stored));
        quizSessions.save(session);
        return practiceResult(stored, activity);
    }

    private MicroCheckResult practiceResult(LessonQuizResult stored, UserLearningPathItem activity) {
        return new MicroCheckResult(stored.totalQuestions(), stored.correctAnswers(), stored.scorePercent(),
                stored.passed(), stored.passThresholdPercent(), 0, MAX_ATTEMPTS, null, false,
                activity.getActivityState(), stored.review());
    }

    /** Review and practice only make sense once the activity is finished; ownership is checked without a lock. */
    private UserLearningPathItem loadFinishedActivity(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findByIdAndUserId(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != adapter.activityType()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        LearningActivityState state = activity.getActivityState();
        if (state != LearningActivityState.PASSED && state != LearningActivityState.COMPLETED) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Hãy hoàn thành phần học này trước khi ôn lại.");
        }
        return activity;
    }

    // ---- Internal helpers ----

    private UserLearningPathItem loadActivityForUpdate(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findOwnedByIdForUpdate(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != adapter.activityType()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        InteractiveActivityStatePolicy.requireUnlocked(activity);
        return activity;
    }

    private List<LearningActivityMember> loadMembership(UserLearningPathItem activity) {
        List<LearningActivityMember> list = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .filter(m -> m.getMemberType() == adapter.memberType())
                .toList();
        if (list.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có danh sách nội dung.");
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

    private EngineStudyState<T> buildStudyState(UserLearningPathItem activity, LearningStudySession session,
            List<LearningActivityMember> membership) {
        List<LearningStudyCard> cards = studyCards.findBySessionIdOrderByOrderIndex(session.getId());
        Map<Long, LearningStudyCard> cardById = cards.stream()
                .collect(Collectors.toMap(LearningStudyCard::getCanonicalId, c -> c));
        Map<Long, T> byId = adapter.loadByIds(membership.stream().map(LearningActivityMember::getCanonicalId).distinct().toList());

        List<EngineStudyState.EngineCard<T>> views = new ArrayList<>();
        int flipped = 0;
        int remembered = 0;
        for (LearningActivityMember member : membership) {
            T entity = byId.get(member.getCanonicalId());
            LearningStudyCard card = cardById.get(member.getCanonicalId());
            boolean isFlipped = card != null && card.isFlipped();
            boolean isRemembered = card != null && card.isRemembered();
            if (isFlipped) flipped++;
            if (isRemembered) remembered++;
            views.add(new EngineStudyState.EngineCard<>(member.getCanonicalId(), member.getOrderIndex(), entity,
                    isFlipped, isRemembered));
        }
        boolean checkEligible = !membership.isEmpty() && remembered == membership.size();
        if (checkEligible && activity.getActivityState() == LearningActivityState.STUDYING) {
            activity.setActivityState(LearningActivityState.CHECK_READY);
        }
        return new EngineStudyState<>(activity.getId(), activity.getActivityType(), activity.getActivityState(),
                session.getSourceRevision(), membership.size(), flipped, remembered, membership.size() - remembered,
                checkEligible, views);
    }

    private void requireAllRemembered(Long activityId, List<LearningActivityMember> membership) {
        LearningStudySession session = studySessions.findByActivityId(activityId).orElse(null);
        Set<Long> remembered = session == null ? Set.of()
                : studyCards.findBySessionIdOrderByOrderIndex(session.getId()).stream()
                        .filter(LearningStudyCard::isRemembered).map(LearningStudyCard::getCanonicalId)
                        .collect(Collectors.toSet());
        boolean all = membership.stream().allMatch(m -> remembered.contains(m.getCanonicalId()));
        if (!all) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Hãy nhớ hết thẻ trước khi kiểm tra.");
        }
    }

    private List<List<T>> contentBatches(List<LearningActivityMember> membership) {
        Map<Long, T> byId = adapter.loadByIds(membership.stream()
                .map(LearningActivityMember::getCanonicalId).distinct().toList());
        List<T> ordered = new ArrayList<>();
        for (LearningActivityMember member : membership) {
            T content = byId.get(member.getCanonicalId());
            if (content == null) {
                throw new ApiException(ErrorCode.BLOCKED_CONTENT,
                        "Nội dung canonical đã thay đổi hoặc không còn sẵn sàng.");
            }
            ordered.add(content);
        }
        String revision = revisionOf(membership);
        if (membership.stream().anyMatch(member -> !revision.equals(member.getSourceRevision()))) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Snapshot hoạt động không đồng nhất.");
        }
        adapter.validateSourceRevision(revision, ordered);
        List<List<T>> batches = adapter.partition(ordered);
        if (batches.isEmpty() || batches.stream().anyMatch(List::isEmpty)) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có batch hợp lệ.");
        }
        return batches;
    }

    private int batchCount(List<LearningActivityMember> membership) {
        if (adapter.requiresCanonicalPartitioning()) return contentBatches(membership).size();
        return (membership.size() + 9) / 10;
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
        int batchCount = batchCount(membership);
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

    private List<LessonQuizResponse.LessonQuizQuestion> toLessonQuizQuestions(List<QuestionSpec> specs) {
        String skill = adapter.activityType().name();
        return specs.stream().map(s -> new LessonQuizResponse.LessonQuizQuestion(s.questionId(), skill,
                null, null, null, null, null, s.prompt(), s.options(), s.type())).toList();
    }

    private Map<Long, QuizAnswerKey> toAnswerKey(List<QuestionSpec> specs) {
        return specs.stream().collect(Collectors.toMap(QuestionSpec::questionId,
                s -> new QuizAnswerKey(s.correctOption(), s.explanation(), null, null)));
    }

    private void requirePostFailureReviewEvidence(UserLearningPathItem activity, List<T> batch,
            LearningMicroCheckState state) {
        LearningStudySession session = studySessions.findByActivityId(activity.getId()).orElse(null);
        if (session == null || state.getCooldownUntil() == null) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET, "Hãy ôn lại nội dung vừa sai.");
        }
        LocalDateTime failedAt = state.getCooldownUntil().minusMinutes(COOLDOWN_MINUTES);
        Set<Long> batchIds = adapter.buildQuestions(batch, state.getBatchIndex(), state.getAttemptsUsed()).stream()
                .map(QuestionSpec::questionId).collect(Collectors.toSet());
        Set<Long> reviewed = studyCards.findBySessionIdOrderByOrderIndex(session.getId()).stream()
                .filter(card -> batchIds.contains(card.getCanonicalId()))
                .filter(card -> card.getFlippedAt() != null && card.getFlippedAt().isAfter(failedAt))
                .map(LearningStudyCard::getCanonicalId).collect(Collectors.toSet());
        if (!reviewed.containsAll(batchIds)) {
            throw new ApiException(ErrorCode.PREREQUISITE_NOT_MET,
                    adapter.activityType() == com.jlas.learning.enums.LearningActivityType.READING
                            ? "Hãy mở lại bài đọc và đọc đến cuối sau lần làm chưa đạt."
                            : "Hãy nghe lại audio sau lần làm chưa đạt.");
        }
    }

    private List<MicroCheckResponse.MicroCheckQuestion> toMicroCheckQuestions(List<QuestionSpec> specs) {
        return specs.stream().map(s -> new MicroCheckResponse.MicroCheckQuestion(s.questionId(), s.type(),
                s.prompt(), s.options())).toList();
    }
}
