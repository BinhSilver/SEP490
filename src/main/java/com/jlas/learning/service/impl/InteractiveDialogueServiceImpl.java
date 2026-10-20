package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.ContentAudio;
import com.jlas.dictionary.entity.DialogueLineDetail;
import com.jlas.common.learning.DraftStudySourcePolicy;
import com.jlas.dictionary.repository.ContentAudioRepository;
import com.jlas.dictionary.repository.DialogueLineDetailRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.learning.dto.DialogueHeartbeatRequest;
import com.jlas.learning.dto.DialogueProgressResult;
import com.jlas.learning.dto.DialogueStudyState;
import com.jlas.learning.dto.DialogueStudyState.DialogueAids;
import com.jlas.learning.dto.DialogueStudyState.DialogueLineView;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningDialogueAid;
import com.jlas.learning.entity.LearningDialogueProgress;
import com.jlas.learning.entity.LearningStudyCard;
import com.jlas.learning.entity.LearningStudySession;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningDialogueAidRepository;
import com.jlas.learning.repository.LearningDialogueProgressRepository;
import com.jlas.learning.repository.LearningStudyCardRepository;
import com.jlas.learning.repository.LearningStudySessionRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.service.GoalPathPolicy;
import com.jlas.learning.service.InteractiveDialogueService;
import com.jlas.learning.service.impl.engine.InteractiveStudyEngine;
import com.jlas.learning.service.impl.InteractiveActivityStatePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** [JLAS-W0193] Task 7 — Dialogue: read-only canonical script + server-timed, evidence-based completion.
 * Deliberately NOT built on {@link InteractiveStudyEngine} / {@code InteractiveContentAdapter}: Dialogue has
 * no micro-check, no score and no attempt cycle, so the engine's flip/remember-or-practice-gate ->
 * issue/submit/grade/cooldown shape does not fit. This is a standalone, much smaller state machine: a single
 * evidence row per activity, mutated only by two server-computed rules —
 * <ol>
 *   <li>a heartbeat's time contribution is {@code min(serverMeasuredGapSeconds, 20)}, or {@code 0} if the gap
 *   since the last heartbeat exceeds 30s (streak reset) or there was no previous heartbeat (baseline only);
 *   the client never supplies a duration, elapsed time or timestamp — only an idempotency token;</li>
 *   <li>bottom-reached evidence is a dedicated, separately persisted event that requires at least one prior
 *   heartbeat to exist (some engagement evidence) and, once set, is never cleared.</li>
 * </ol>
 * Completion requires both {@code accumulatedSeconds >= REQUIRED_SECONDS} and reached-bottom evidence, and is
 * recorded exactly once. */
@Service
@RequiredArgsConstructor
public class InteractiveDialogueServiceImpl implements InteractiveDialogueService {
    static final int REQUIRED_SECONDS = 180;
    static final int MAX_HEARTBEAT_SECONDS = 20;
    static final int GAP_RESET_SECONDS = 30;

    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final LearningDialogueProgressRepository progressRepo;
    private final DialogueLineDetailRepository dialogueLines;
    private final ContentAudioRepository audio;
    // Communication dialogues record line-by-line practice in the generic per-member study tables.
    private final LearningStudySessionRepository studySessions;
    private final LearningStudyCardRepository studyCards;
    // Owner 2026-10-19 (C2/C4): J-LAS supplement furigana + "Từ & cụm từ" + the lesson's patterns for the summary.
    private final LearningDialogueAidRepository dialogueAids;
    private final GrammarPointRepository grammarPoints;
    // Owner 2026-10-19 (G1–G3): optional reply challenge; its first clear pays 2 CP once.
    private final com.jlas.learning.repository.LearningDialogueChallengeClearRepository challengeClears;
    /** Optional so slice tests need not wire the credit stack (same pattern as the chapter reward). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.jlas.moderation.credit.service.UserCreditService creditService;

    static final int CHALLENGE_CREDITS = 2;
    static final String CHALLENGE_REFERENCE = "COMM_CHALLENGE";

    static final String AID_LABEL = "Nội dung bổ sung J-LAS · cần rà soát";
    private static final Pattern RUBY = Pattern.compile("\\{([^{}|]+)\\|([^{}|]+)\\}");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    @Transactional
    public DialogueStudyState studyState(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningDialogueProgress progress = progressRepo.findByActivityId(activityId).orElse(null);
        boolean sameRevision = progress != null && progress.getSourceRevision().equals(revisionOf(membership));
        int accumulated = sameRevision ? progress.getAccumulatedSeconds() : 0;
        boolean reachedBottom = sameRevision && progress.getReachedBottomAt() != null;
        boolean completed = sameRevision && progress.getCompletedAt() != null;
        boolean lineByLine = isCommunication(activity);
        Aided aided = withAids(activity, buildLineViews(activity, membership), membership);

        return new DialogueStudyState(activity.getId(), activity.getActivityType(), activity.getActivityState(),
                revisionOf(membership), requiredSeconds(activity), accumulated,
                lineByLine ? 0 : remaining(accumulated), reachedBottom, completed, aided.lines(),
                lineByLine, lineByLine ? practicedLineIds(activity, membership) : List.of(), aided.aids());
    }

    private record Aided(List<DialogueLineView> lines, DialogueAids aids) { }

    private record ParsedAid(String reviewStatus, JsonNode root, List<JsonNode> phrases) { }

    /** The lesson's aid row of a communication dialogue, or null (JLPT, no lesson/row, unreadable JSON). */
    private ParsedAid parsedAid(UserLearningPathItem activity) {
        if (!isCommunication(activity) || activity.getLesson() == null) return null;
        LearningDialogueAid aid = dialogueAids.findById(activity.getLesson().getId()).orElse(null);
        if (aid == null) return null;
        JsonNode root;
        try {
            root = JSON.readTree(aid.getAidJson());
        } catch (Exception malformed) {
            return null;
        }
        if (root == null || !root.isObject()) return null;
        List<JsonNode> phrases = new ArrayList<>();
        for (JsonNode node : root.path("phrases")) {
            if (!node.path("ja").asText("").isBlank()) phrases.add(node);
        }
        return new ParsedAid(aid.getReviewStatus(), root, List.copyOf(phrases));
    }

    /** Display aids for communication dialogues. A missing or unreadable aid row never breaks the script. */
    private Aided withAids(UserLearningPathItem activity, List<DialogueLineView> views,
            List<LearningActivityMember> membership) {
        ParsedAid parsed = parsedAid(activity);
        if (parsed == null) return new Aided(views, null);
        Long lessonId = activity.getLesson().getId();
        JsonNode root = parsed.root();
        java.util.Set<Integer> flipped = flippedPhraseIndexes(activity, membership, parsed.phrases().size());
        Map<String, String> furiganaByText = new HashMap<>();
        for (JsonNode node : root.path("furigana")) {
            String markup = node.asText("");
            if (!markup.isBlank()) furiganaByText.putIfAbsent(RUBY.matcher(markup).replaceAll("$1"), markup);
        }
        List<DialogueLineView> lines = views.stream().map(view -> new DialogueLineView(view.lineId(), view.orderIndex(),
                view.roleCode(), view.roleName(), view.sceneCode(), view.bubbleSide(), view.japanese(), view.meaningVi(),
                view.audioUrl(), view.japanese() == null ? null : furiganaByText.get(view.japanese()))).toList();
        List<DialogueAids.Phrase> phrases = new ArrayList<>();
        for (int index = 0; index < parsed.phrases().size(); index++) {
            JsonNode node = parsed.phrases().get(index);
            String japanese = node.path("ja").asText("");
            Long lineId = views.stream().filter(view -> view.japanese() != null && view.japanese().contains(japanese))
                    .map(DialogueLineView::lineId).findFirst().orElse(null);
            phrases.add(new DialogueAids.Phrase(index, japanese, node.path("reading").asText(null),
                    node.path("vi").asText(null), lineId, flipped.contains(index)));
        }
        List<DialogueAids.KeyPattern> patterns = grammarPoints.findByLessonIdOrderByOrderIndexAsc(lessonId).stream()
                .map(point -> new DialogueAids.KeyPattern(point.getPattern(), point.getMeaning(), point.getNote())).toList();
        return new Aided(lines, new DialogueAids(AID_LABEL, parsed.reviewStatus(), phrases, patterns));
    }

    /** A challenge turn resolved against the current script; {@code order} holds the answer (0) and the two distractors
     * (1, 2) in display order, so option ids a/b/c are stable per lesson and turn but never reveal the answer's place. */
    private record ChallengeTurn(int index, DialogueLineView line, List<JsonNode> choices, int answerPosition, String why) { }

    private record LoadedChallenge(UserLearningPathItem activity, Long lessonId, String learnerRole, List<ChallengeTurn> turns) { }

    private static final int[][] ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
    private static final String OPTION_IDS = "abc";

    /** The reply challenge of a communication dialogue, or null when absent or stale (an answer no longer in the script). */
    private LoadedChallenge loadChallenge(UserLearningPathItem activity, List<DialogueLineView> views) {
        ParsedAid parsed = parsedAid(activity);
        if (parsed == null) return null;
        JsonNode challenge = parsed.root().path("challenge");
        JsonNode turns = challenge.path("turns");
        if (!turns.isArray() || turns.isEmpty()) return null;
        Long lessonId = activity.getLesson().getId();
        List<ChallengeTurn> resolved = new ArrayList<>();
        String learnerRole = null;
        for (int index = 0; index < turns.size(); index++) {
            JsonNode turn = turns.get(index);
            String answer = turn.path("answer").path("ja").asText("");
            DialogueLineView line = views.stream().filter(view -> answer.equals(view.japanese())).findFirst().orElse(null);
            JsonNode distractors = turn.path("distractors");
            if (line == null || !distractors.isArray() || distractors.size() != 2) return null;
            if (learnerRole == null) learnerRole = line.roleName();
            int[] order = ORDERS[Math.floorMod(java.util.Objects.hash(lessonId, index), ORDERS.length)];
            List<JsonNode> choices = new ArrayList<>();
            int answerPosition = -1;
            for (int position = 0; position < 3; position++) {
                choices.add(order[position] == 0 ? turn.path("answer") : distractors.get(order[position] - 1));
                if (order[position] == 0) answerPosition = position;
            }
            resolved.add(new ChallengeTurn(index, line, choices, answerPosition, turn.path("why_vi").asText("")));
        }
        return new LoadedChallenge(activity, lessonId, learnerRole, resolved);
    }

    private LoadedChallenge challengeFor(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findByIdAndUserId(activityId, userId)
                .filter(item -> item.getActivityType() == LearningActivityType.DIALOGUE)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return loadChallenge(activity, buildLineViews(activity, loadMembership(activity)));
    }

    @Override
    @Transactional(readOnly = true)
    public com.jlas.learning.dto.DialogueChallenge.View challenge(Long userId, Long activityId) {
        LoadedChallenge loaded = challengeFor(userId, activityId);
        if (loaded == null) return new com.jlas.learning.dto.DialogueChallenge.View(false, false, CHALLENGE_CREDITS, null, AID_LABEL, List.of());
        List<com.jlas.learning.dto.DialogueChallenge.Turn> turns = loaded.turns().stream().map(turn ->
                new com.jlas.learning.dto.DialogueChallenge.Turn(turn.index(), turn.line().lineId(),
                        java.util.stream.IntStream.range(0, 3).mapToObj(position -> {
                            JsonNode choice = turn.choices().get(position);
                            return new com.jlas.learning.dto.DialogueChallenge.Option(String.valueOf(OPTION_IDS.charAt(position)),
                                    choice.path("ja").asText(""), choice.path("vi").asText(null), choice.path("furigana").asText(null));
                        }).toList())).toList();
        return new com.jlas.learning.dto.DialogueChallenge.View(true,
                challengeClears.existsByUserIdAndLessonId(userId, loaded.lessonId()), CHALLENGE_CREDITS, loaded.learnerRole(),
                AID_LABEL, turns);
    }

    @Override
    @Transactional(readOnly = true)
    public com.jlas.learning.dto.DialogueChallenge.Answer answerChallenge(Long userId, Long activityId, int turn, String optionId) {
        LoadedChallenge loaded = challengeFor(userId, activityId);
        if (loaded == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tình huống này chưa có thử thách vượt ải.");
        if (turn < 0 || turn >= loaded.turns().size()) throw new ApiException(ErrorCode.NOT_FOUND);
        ChallengeTurn current = loaded.turns().get(turn);
        String correct = String.valueOf(OPTION_IDS.charAt(current.answerPosition()));
        return new com.jlas.learning.dto.DialogueChallenge.Answer(turn, correct.equals(optionId), correct, current.why());
    }

    @Override
    @Transactional
    public com.jlas.learning.dto.DialogueChallenge.Result completeChallenge(Long userId, Long activityId, List<String> answers) {
        LoadedChallenge loaded = challengeFor(userId, activityId);
        if (loaded == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tình huống này chưa có thử thách vượt ải.");
        int total = loaded.turns().size();
        if (answers == null || answers.size() != total) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cần trả lời đủ " + total + " lượt của thử thách.");
        }
        int correct = 0;
        for (ChallengeTurn turn : loaded.turns()) {
            if (String.valueOf(OPTION_IDS.charAt(turn.answerPosition())).equals(answers.get(turn.index()))) correct++;
        }
        if (correct < total) return new com.jlas.learning.dto.DialogueChallenge.Result(false, false, 0, correct, total);
        boolean firstClear = challengeClears.insertIfAbsent(userId, loaded.lessonId(), correct, total) == 1;
        int credits = 0;
        if (firstClear && creditService != null) {
            credits = creditService.earn(userId, CHALLENGE_CREDITS,
                    com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason.STUDY_REWARD,
                    CHALLENGE_REFERENCE, loaded.lessonId(), "Vượt ải thử thách hội thoại").applied() ? CHALLENGE_CREDITS : 0;
        }
        return new com.jlas.learning.dto.DialogueChallenge.Result(true, firstClear, credits, correct, total);
    }

    // "Từ & cụm từ" flips share the activity's practice cards; ids at 2^62 + index never meet content item ids.
    static final long PHRASE_CARD_BASE = 1L << 62;

    @Override
    @Transactional
    public DialogueProgressResult flipPhrase(Long userId, Long activityId, int phraseIndex) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        if (!isCommunication(activity)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thẻ Từ & cụm từ chỉ có ở tình huống giao tiếp.");
        }
        ParsedAid parsed = parsedAid(activity);
        if (parsed == null || phraseIndex < 0 || phraseIndex >= parsed.phrases().size()) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        LearningStudySession session = practiceSession(activity, membership);
        long cardId = PHRASE_CARD_BASE + phraseIndex;
        LearningStudyCard card = studyCards.findBySessionIdAndCanonicalId(session.getId(), cardId)
                .orElseGet(() -> LearningStudyCard.builder().session(session).canonicalId(cardId)
                        .orderIndex(100_000 + phraseIndex).build());
        if (!card.isFlipped()) {
            card.setFlipped(true);
            card.setFlippedAt(now);
            studyCards.save(card);
        }
        LearningDialogueProgress state = lockOrCreateProgress(activity, membership);
        applyCompletionIfEligible(activity, state, now, membership);
        progressRepo.save(state);
        return toResult(activity, state, membership);
    }

    /** Flipped phrase cards of the current revision, limited to the aid's current phrase count. */
    private java.util.Set<Integer> flippedPhraseIndexes(UserLearningPathItem activity,
            List<LearningActivityMember> membership, int phraseCount) {
        if (phraseCount == 0) return java.util.Set.of();
        LearningStudySession session = studySessions.findByActivityId(activity.getId()).orElse(null);
        if (session == null || !revisionOf(membership).equals(session.getSourceRevision())) return java.util.Set.of();
        return studyCards.findBySessionIdOrderByOrderIndex(session.getId()).stream()
                .filter(card -> card.isFlipped() && card.getCanonicalId() >= PHRASE_CARD_BASE
                        && card.getCanonicalId() < PHRASE_CARD_BASE + phraseCount)
                .map(card -> (int) (card.getCanonicalId() - PHRASE_CARD_BASE))
                .collect(Collectors.toSet());
    }

    private int phraseCount(UserLearningPathItem activity) {
        ParsedAid parsed = parsedAid(activity);
        return parsed == null ? 0 : parsed.phrases().size();
    }

    /** Owner 2026-10-19: a communication dialogue is practised turn by turn; each call is idempotent. */
    @Override
    @Transactional
    public DialogueProgressResult practiceLine(Long userId, Long activityId, Long lineId) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        if (!isCommunication(activity)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Luyện từng lượt chỉ áp dụng cho tình huống giao tiếp.");
        }
        LearningActivityMember line = membership.stream()
                .filter(member -> member.getCanonicalId().equals(lineId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        LearningStudySession session = practiceSession(activity, membership);
        LearningStudyCard card = studyCards.findBySessionIdAndCanonicalId(session.getId(), lineId)
                .orElseGet(() -> LearningStudyCard.builder().session(session).canonicalId(lineId)
                        .orderIndex(line.getOrderIndex()).build());
        if (!card.isFlipped()) {
            card.setFlipped(true);
            card.setFlippedAt(now);
            studyCards.save(card);
        }
        LearningDialogueProgress state = lockOrCreateProgress(activity, membership);
        applyCompletionIfEligible(activity, state, now, membership);
        progressRepo.save(state);
        return toResult(activity, state, membership);
    }

    @Override
    @Transactional(readOnly = true)
    public DialogueStudyState reviewState(Long userId, Long activityId) {
        return replayState(userId, activityId);
    }

    @Override
    @Transactional(readOnly = true)
    public DialogueStudyState practiceState(Long userId, Long activityId) {
        return replayState(userId, activityId);
    }

    private DialogueStudyState replayState(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findByIdAndUserId(activityId, userId)
                .filter(item -> item.getActivityType() == LearningActivityType.DIALOGUE
                        && item.getActivityState() == LearningActivityState.COMPLETED)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningDialogueProgress state = progressRepo.findByActivityId(activityId).orElse(null);
        boolean matchingRevision = state != null && state.getSourceRevision().equals(revisionOf(membership));
        int accumulated = matchingRevision ? state.getAccumulatedSeconds() : 0;
        boolean reachedBottom = matchingRevision && state.getReachedBottomAt() != null;
        boolean completed = activity.getActivityState() == LearningActivityState.COMPLETED
                || (matchingRevision && state.getCompletedAt() != null);
        Aided aided = withAids(activity, buildLineViews(activity, membership), membership);
        return new DialogueStudyState(activityId, LearningActivityType.DIALOGUE, activity.getActivityState(),
                revisionOf(membership), REQUIRED_SECONDS, accumulated, remaining(accumulated), reachedBottom,
                completed, aided.lines(), false, List.of(), aided.aids());
    }

    @Override
    @Transactional
    public DialogueProgressResult heartbeat(Long userId, Long activityId, DialogueHeartbeatRequest request) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        LearningDialogueProgress state = lockOrCreateProgress(activity, membership);

        if (request.heartbeatToken().equals(state.getLastHeartbeatToken())) {
            // Exact retry/replay of the same heartbeat: return current state, credit nothing twice.
            return toResult(activity, state, membership);
        }

        LocalDateTime now = LocalDateTime.now();
        long contribution;
        if (state.getLastHeartbeatAt() == null) {
            contribution = 0; // no prior anchor to measure a gap from — establish the baseline only.
        } else {
            long gapSeconds = Duration.between(state.getLastHeartbeatAt(), now).getSeconds();
            if (gapSeconds < 0) gapSeconds = 0; // clock-skew guard; never credit negative time.
            contribution = gapSeconds > GAP_RESET_SECONDS ? 0 : Math.min(gapSeconds, MAX_HEARTBEAT_SECONDS);
        }
        state.setAccumulatedSeconds(state.getAccumulatedSeconds() + (int) contribution);
        state.setLastHeartbeatAt(now);
        state.setLastHeartbeatToken(request.heartbeatToken());
        applyCompletionIfEligible(activity, state, now, membership);
        progressRepo.save(state);
        return toResult(activity, state, membership);
    }

    @Override
    @Transactional
    public DialogueProgressResult reachedBottom(Long userId, Long activityId) {
        UserLearningPathItem activity = loadActivity(userId, activityId);
        List<LearningActivityMember> membership = loadMembership(activity);
        // Communication dialogues are evidenced by line practice, not time, so no heartbeat row is required.
        LearningDialogueProgress state = isCommunication(activity)
                ? lockOrCreateProgress(activity, membership)
                : progressRepo.findForUpdate(activityId).orElse(null);
        if (state == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Chưa có bằng chứng hoạt động để ghi nhận đã cuộn đến cuối.");
        }
        LocalDateTime now = LocalDateTime.now();
        if (state.getReachedBottomAt() == null) {
            state.setReachedBottomAt(now);
        }
        applyCompletionIfEligible(activity, state, now, membership);
        progressRepo.save(state);
        return toResult(activity, state, membership);
    }

    // ---- Internal helpers ----

    private UserLearningPathItem loadActivity(Long userId, Long activityId) {
        UserLearningPathItem activity = items.findOwnedByIdForUpdate(activityId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (activity.getActivityType() != LearningActivityType.DIALOGUE) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        InteractiveActivityStatePolicy.requireUnlocked(activity);
        return activity;
    }

    private List<LearningActivityMember> loadMembership(UserLearningPathItem activity) {
        List<LearningActivityMember> list = members.findByActivityIdOrderByOrderIndex(activity.getId()).stream()
                .filter(m -> m.getMemberType() == LearningMemberType.DIALOGUE_LINE)
                .toList();
        if (list.isEmpty()) {
            throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Hoạt động chưa có kịch bản hội thoại.");
        }
        return list;
    }

    private String revisionOf(List<LearningActivityMember> membership) {
        return membership.getFirst().getSourceRevision();
    }

    /** Locks (or creates) the activity's single progress row. A membership revision bump (re-composition)
     * resets stale progress from a previous revision rather than silently carrying it over to different
     * dialogue content — same posture as Grammar's practice-progress gate. */
    private LearningDialogueProgress lockOrCreateProgress(UserLearningPathItem activity,
            List<LearningActivityMember> membership) {
        String revision = revisionOf(membership);
        LearningDialogueProgress state = progressRepo.findForUpdate(activity.getId()).orElse(null);
        if (state == null) {
            state = LearningDialogueProgress.builder().activity(activity).sourceRevision(revision).build();
        } else if (!state.getSourceRevision().equals(revision)) {
            state.setSourceRevision(revision);
            state.setAccumulatedSeconds(0);
            state.setLastHeartbeatAt(null);
            state.setLastHeartbeatToken(null);
            state.setReachedBottomAt(null);
            state.setCompletedAt(null);
        }
        return state;
    }

    private void applyCompletionIfEligible(UserLearningPathItem activity, LearningDialogueProgress state,
            LocalDateTime now, List<LearningActivityMember> membership) {
        // JLPT: server-measured time; communication (owner 2026-10-19, §2.24): every line practised and every
        // "Từ & cụm từ" card flipped instead of time.
        boolean satisfiesPractice;
        if (isCommunication(activity)) {
            int phrases = phraseCount(activity);
            satisfiesPractice = practicedLineIds(activity, membership).size() == membership.size()
                    && flippedPhraseIndexes(activity, membership, phrases).size() == phrases;
        } else {
            satisfiesPractice = state.getAccumulatedSeconds() >= REQUIRED_SECONDS;
        }
        boolean satisfiesBottom = state.getReachedBottomAt() != null;
        if (satisfiesPractice && satisfiesBottom && state.getCompletedAt() == null) {
            state.setCompletedAt(now);
        }
        if (state.getCompletedAt() != null) {
            activity.setActivityState(LearningActivityState.COMPLETED);
        }
    }

    private DialogueProgressResult toResult(UserLearningPathItem activity, LearningDialogueProgress state,
            List<LearningActivityMember> membership) {
        int accumulated = state.getAccumulatedSeconds();
        boolean lineByLine = isCommunication(activity);
        int phrases = lineByLine ? phraseCount(activity) : 0;
        return new DialogueProgressResult(activity.getId(), activity.getActivityState(), requiredSeconds(activity),
                accumulated, lineByLine ? 0 : remaining(accumulated), state.getReachedBottomAt() != null,
                state.getCompletedAt() != null, lineByLine,
                lineByLine ? practicedLineIds(activity, membership).size() : 0, membership.size(),
                lineByLine ? flippedPhraseIndexes(activity, membership, phrases).size() : 0, phrases);
    }

    private static boolean isCommunication(UserLearningPathItem activity) {
        return activity.getLearningPath() != null && GoalPathPolicy.isCommunicationGoal(activity.getLearningPath());
    }

    private static int requiredSeconds(UserLearningPathItem activity) {
        return isCommunication(activity) ? 0 : REQUIRED_SECONDS;
    }

    /** Lines of the current revision that were practised; a revision bump discards older practice. */
    private List<Long> practicedLineIds(UserLearningPathItem activity, List<LearningActivityMember> membership) {
        LearningStudySession session = studySessions.findByActivityId(activity.getId()).orElse(null);
        if (session == null || !revisionOf(membership).equals(session.getSourceRevision())) return List.of();
        java.util.Set<Long> lineIds = membership.stream().map(LearningActivityMember::getCanonicalId)
                .collect(Collectors.toSet());
        return studyCards.findBySessionIdOrderByOrderIndex(session.getId()).stream()
                .filter(card -> card.isFlipped() && lineIds.contains(card.getCanonicalId()))
                .map(LearningStudyCard::getCanonicalId).distinct().toList();
    }

    private LearningStudySession practiceSession(UserLearningPathItem activity, List<LearningActivityMember> membership) {
        String revision = revisionOf(membership);
        LearningStudySession session = studySessions.findByActivityId(activity.getId()).orElse(null);
        if (session == null) {
            return studySessions.save(LearningStudySession.builder().activity(activity).sourceRevision(revision).build());
        }
        if (!revision.equals(session.getSourceRevision())) {
            studyCards.deleteAll(studyCards.findBySessionIdOrderByOrderIndex(session.getId()));
            session.setSourceRevision(revision);
            return studySessions.save(session);
        }
        return session;
    }

    private int remaining(int accumulatedSeconds) {
        return Math.max(0, REQUIRED_SECONDS - accumulatedSeconds);
    }

    private List<DialogueLineView> buildLineViews(UserLearningPathItem activity, List<LearningActivityMember> membership) {
        // §2.28 / OD-44: the owner-opened DRAFT N3 textbook has no recorded dialogue audio yet; its lines use the
        // browser's labelled synthesized voice. Every other line without an approved clip keeps no audio.
        var textbook = activity.getLesson() == null || activity.getLesson().getUnit() == null ? null
                : activity.getLesson().getUnit().getTextbook();
        boolean synthesizeMissing = textbook != null && DraftStudySourcePolicy.isOpenDraftTextbook(
                textbook.getStatus(), textbook.getIsLearningPathSource(), textbook.getJlptLevel());
        List<Long> ids = membership.stream().map(LearningActivityMember::getCanonicalId).distinct().toList();
        Map<Long, DialogueLineDetail> byId = dialogueLines.findAllById(ids).stream()
                .collect(Collectors.toMap(DialogueLineDetail::getContentItemId, d -> d));
        Map<Long, ContentAudio> audioById = audio.findByOwnerTypeAndOwnerIdIn("CONTENT_ITEM", ids).stream()
                .collect(Collectors.toMap(ContentAudio::getOwnerId, a -> a, (a, b) -> a));

        List<DialogueLineView> views = new ArrayList<>();
        for (LearningActivityMember member : membership) {
            DialogueLineDetail detail = byId.get(member.getCanonicalId());
            if (detail == null || detail.getContentItem() == null) {
                throw new ApiException(ErrorCode.BLOCKED_CONTENT, "Nội dung hội thoại không còn đầy đủ. Vui lòng thử lại sau.");
            }
            var item = detail.getContentItem();
            var role = detail.getRole();
            views.add(new DialogueLineView(member.getCanonicalId(), member.getOrderIndex(),
                    role == null ? null : role.getCode(), role == null ? null : role.getNameVi(),
                    detail.getSceneCode(), detail.getBubbleSide(), item.getContent(), item.getTranslatedText(),
                    audioFor(audioById.get(member.getCanonicalId()), synthesizeMissing)));
        }
        return views;
    }

    private String audioFor(ContentAudio audio, boolean synthesizeMissing) {
        String url = publicAudioUrl(audio);
        return url == null && audio == null && synthesizeMissing ? "tts:ja-JP" : url;
    }

    private String publicAudioUrl(ContentAudio audio) {
        if (audio == null || audio.getAudioUrl() == null || audio.getAudioUrl().isBlank()) return null;
        String value = audio.getAudioUrl().strip();
        if (value.startsWith("//") || value.contains("\\")) return null;
        // A sentinel, not a URL: the browser synthesizes Japanese speech from the line's own text.
        if ("tts:ja-JP".equals(value)) return value;
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.isAbsolute()) {
                return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null && uri.getUserInfo() == null ? uri.toASCIIString() : null;
            }
            if (uri.getPath() == null || java.util.Arrays.stream(uri.getPath().split("/"))
                    .anyMatch(segment -> segment.equals("..") || segment.contains("%") || segment.contains("\\"))) return null;
            return value.startsWith("/") ? uri.toASCIIString() : "/uploads/content-audio/" + uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
