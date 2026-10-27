package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.dto.practice.*;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoStatus;
import com.jlas.shadowing.model.VideoVisibility;
import com.jlas.shadowing.model.practice.*;
import com.jlas.shadowing.repository.ShadowingPracticeRepository;
import com.jlas.shadowing.repository.ShadowingQuestionBankRepository;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShadowingPracticeService {
    private static final int DEFAULT_QUESTION_COUNT = 10;
    private static final int MAX_CREATE_REQUESTS_PER_MINUTE = 5;
    private final VideoRepository videoRepository;
    private final SubtitleRepository subtitleRepository;
    private final ShadowingExerciseAnalysisService analysisService;
    private final ShadowingPracticeRepository practiceRepository;
    private final SubtitleQualityReviewService subtitleQualityReviewService;
    private final ShadowingQuestionBankRepository questionBank;
    private final ShadowingQuestionPreparationService preparationService;
    private final Map<Long, Deque<Instant>> createRequests = new ConcurrentHashMap<>();

    public ShadowingPracticeService(VideoRepository videoRepository,
                                    SubtitleRepository subtitleRepository,
                                    ShadowingExerciseAnalysisService analysisService,
                                    ShadowingPracticeRepository practiceRepository,
                                    SubtitleQualityReviewService subtitleQualityReviewService,
                                    ShadowingQuestionBankRepository questionBank,
                                    ShadowingQuestionPreparationService preparationService) {
        this.videoRepository = videoRepository;
        this.subtitleRepository = subtitleRepository;
        this.analysisService = analysisService;
        this.practiceRepository = practiceRepository;
        this.subtitleQualityReviewService = subtitleQualityReviewService;
        this.questionBank = questionBank;
        this.preparationService = preparationService;
    }

    public CreatePracticeSessionResponse create(long userId, CreatePracticeSessionRequest request) {
        rateLimit(userId);
        Video video = accessibleVideo(userId, request.videoId());
        Set<Long> blockedSubtitleIds = subtitleQualityReviewService.blockedSubtitleIds(video.getId());
        List<Subtitle> subtitles = subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(video.getId())
                .stream().filter(subtitle -> subtitle.getText() != null && !subtitle.getText().isBlank())
                .filter(subtitle -> !blockedSubtitleIds.contains(subtitle.getId())).toList();
        if (subtitles.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Bài học chưa có phụ đề hợp lệ");
        int requested = request.allQuestions() ? subtitles.size()
                : Math.min(subtitles.size(), request.questionCount() == null
                ? DEFAULT_QUESTION_COUNT : request.questionCount());
        List<QuestionSeed> available;
        Map<String, Integer> selectionCounts = null;
        if (request.mode() == PracticeMode.LISTENING) {
            boolean pairSelection = request.selections() != null;
            if (pairSelection ? request.selections().isEmpty() || request.selections().stream().anyMatch(pair -> {
                    String[] parts = pair == null ? new String[0] : pair.split(":", -1);
                    return parts.length != 2
                            || !ShadowingQuestionPreparationService.LEVELS.contains(parts[0])
                            || !ShadowingQuestionPreparationService.KINDS.contains(parts[1]);
                }) : request.levels() == null || request.levels().isEmpty()
                    || !ShadowingQuestionPreparationService.LEVELS.containsAll(request.levels())
                    || request.kinds() == null || request.kinds().isEmpty()
                    || !ShadowingQuestionPreparationService.KINDS.containsAll(request.kinds())) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy chọn mức và dạng bài tập");
            }
            selectionCounts = selectionCounts(request);
            requested = request.questionCount() == null ? DEFAULT_QUESTION_COUNT : request.questionCount();
            if (!request.allQuestions() && (requested < 1 || requested > 500)) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Số câu phải từ 1 đến 500");
            }
            Map<Long, Subtitle> byId = new HashMap<>();
            subtitles.forEach(subtitle -> byId.put(subtitle.getId(), subtitle));
            List<PreparedQuestion> preparedPool = new ArrayList<>(preparationService
                    .validQuestions(video.getId(), subtitles).stream()
                    .filter(question -> pairSelection
                            ? request.selections().contains(question.level() + ":" + question.kind())
                            : request.levels().contains(question.level())
                                    && request.kinds().contains(question.kind()))
                    .toList());
            java.util.Collections.shuffle(preparedPool);
            List<QuestionSeed> byEnd = new ArrayList<>();
            for (PreparedQuestion question : preparedPool) {
                Subtitle subtitle = byId.get(question.subtitleId());
                if (subtitle == null) continue;
                byEnd.add(new QuestionSeed(subtitle, null,
                        question, switch (question.level()) {
                            case "EASY" -> 25; case "MEDIUM" -> 55; default -> 85;
                        }));
            }
            byEnd.sort(Comparator.comparingDouble(seedItem -> seedItem.prepared().endTime()));
            if (selectionCounts != null) {
                // Mỗi "MỨC:DẠNG" có tối đa riêng như màn chọn hiển thị; câu khác dạng trùng đoạn không lấy mất suất của nhau.
                Map<String, List<QuestionSeed>> byPair = new LinkedHashMap<>();
                byEnd.forEach(seedItem -> byPair.computeIfAbsent(pairOf(seedItem), ignored -> new ArrayList<>()).add(seedItem));
                available = new ArrayList<>();
                for (List<QuestionSeed> group : byPair.values()) available.addAll(nonOverlapping(group));
            } else {
                available = nonOverlapping(byEnd);
            }
            if (selectionCounts != null) {
                for (Map.Entry<String, Integer> entry : selectionCounts.entrySet()) {
                    long ofPair = available.stream().filter(seedItem -> entry.getKey().equals(pairOf(seedItem))).count();
                    if (entry.getValue() > ofPair) {
                        throw new ApiException(ErrorCode.BAD_REQUEST, "Mức này chỉ còn " + ofPair
                                + " câu phù hợp; hãy chọn ít câu hơn");
                    }
                }
                requested = selectionCounts.values().stream().mapToInt(Integer::intValue).sum();
            }
            if (!request.allQuestions() && available.size() < requested) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "Bộ câu hỏi đã chuẩn bị chỉ có " + available.size() + " câu phù hợp; hãy chọn ít câu hơn hoặc mức khác");
            }
        } else {
            Map<Long, ShadowingExerciseAnalysis> analyses = analysisService.cachedAnalyses(subtitles);
            available = buildSeeds(subtitles, analyses, request.mode());
        }
        if (available.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    request.mode() == PracticeMode.CLOZE
                            ? "Chưa tạo được vị trí điền phù hợp cho bài này"
                            : "Bài học chưa có câu phù hợp để luyện nghe");
        }
        requested = request.allQuestions() ? available.size() : Math.min(requested, available.size());
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        PracticeOrder order = request.mode() == PracticeMode.LISTENING ? PracticeOrder.RANDOM
                : request.order() == null ? PracticeOrder.PROGRESSIVE : request.order();
        List<QuestionSeed> selected = request.mode() != PracticeMode.LISTENING
                ? selectQuestions(available, requested, order, seed)
                : selectionCounts != null ? stopsPerSelection(available, selectionCounts, seed)
                : balancedStops(available, requested, seed);

        ShadowingPracticeSession session = new ShadowingPracticeSession();
        session.setUserId(userId);
        session.setVideoId(video.getId());
        session.setMode(request.mode());
        session.setOrder(order);
        session.setRequestedCount(requested);
        session.setTotalQuestions(selected.size());
        session.setRandomSeed(seed);
        List<ShadowingPracticeItem> items = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            QuestionSeed seedItem = selected.get(index);
            ShadowingPracticeItem item = new ShadowingPracticeItem();
            item.setSubtitleId(seedItem.subtitle().getId());
            item.setClozeCandidateId(seedItem.candidate() == null ? null : seedItem.candidate().getId());
            item.setPreparedQuestionId(seedItem.prepared() == null ? null : seedItem.prepared().id());
            item.setPosition(index);
            item.setDifficultyScore(seedItem.difficulty());
            items.add(item);
        }
        long sessionId = practiceRepository.createSession(session, items);
        return new CreatePracticeSessionResponse(sessionId, selected.size());
    }

    @Transactional(readOnly = true)
    public PracticeSessionView get(long userId, long sessionId) {
        ShadowingPracticeSession session = ownedSession(userId, sessionId);
        Video video = videoRepository.findById(session.getVideoId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy video"));
        List<PracticeStopView> stops = practiceRepository.findStops(sessionId);
        if (session.getStatus() == PracticeSessionStatus.COMPLETED
                || session.getCurrentPosition() >= session.getTotalQuestions()) {
            return sessionView(session, video, stops, null);
        }
        ShadowingPracticeItem item = practiceRepository.findItem(sessionId, session.getCurrentPosition());
        if (item == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hiện tại");
        Subtitle subtitle = subtitleRepository.findById(item.getSubtitleId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phụ đề"));
        ShadowingClozeCandidate candidate = item.getClozeCandidateId() == null
                ? null : practiceRepository.findCandidate(item.getClozeCandidateId());
        PreparedQuestion prepared = item.getPreparedQuestionId() == null
                ? null : questionBank.findById(item.getPreparedQuestionId());
        requirePreparedQuestion(session, prepared);
        ShadowingPracticeAnswer previous = practiceRepository.findAnswer(item.getId());
        PracticeAnswerResponse previousResponse = previous == null ? null
                : answerResponse(session.getMode(), subtitle, candidate, prepared, previous);
        String prompt = prepared != null ? prepared.prompt()
                : session.getMode() == PracticeMode.CLOZE && candidate != null
                ? mask(subtitle.getText(), candidate) : "";
        boolean settled = previous != null && previous.getResult() != AnswerResult.INCORRECT;
        PracticeQuestionView question = new PracticeQuestionView(
                item.getId(), subtitle.getId(), item.getPosition() + 1, session.getTotalQuestions(),
                item.getDifficultyScore(), prepared == null ? difficultyLabel(item.getDifficultyScore())
                        : levelLabel(prepared.level()),
                prepared == null ? subtitle.getStartTime() : prepared.startTime(),
                prepared == null ? subtitle.getEndTime() : prepared.endTime(), prompt,
                settled ? subtitle.getTranslation() : null, settled, previousResponse,
                prepared == null ? null : prepared.kind(), prepared == null ? null : prepared.level(),
                prepared == null ? List.of() : shuffledChoices(prepared.choices(), sessionId, item.getId()),
                settled && prepared != null ? prepared.explanation() : null);
        return sessionView(session, video, stops, question);
    }

    static List<String> shuffledChoices(List<String> choices, long sessionId, long itemId) {
        if (choices.size() < 2) return choices;
        List<String> shuffled = new ArrayList<>(choices);
        long seed = Long.rotateLeft(sessionId, 21) ^ Long.rotateLeft(itemId, 7);
        Collections.shuffle(shuffled, new Random(seed));
        return shuffled;
    }

    @Transactional
    public PracticeAnswerResponse submit(long userId, long sessionId, SubmitPracticeAnswerRequest request) {
        ShadowingPracticeSession session = lockedSession(userId, sessionId);
        if (session.getStatus() == PracticeSessionStatus.COMPLETED) {
            throw new ApiException(ErrorCode.CONFLICT, "Phiên học đã hoàn thành");
        }
        ShadowingPracticeItem item = practiceRepository.findItem(sessionId, session.getCurrentPosition());
        if (item == null || !item.getId().equals(request.itemId())) {
            throw new ApiException(ErrorCode.CONFLICT, "Câu trả lời không thuộc câu đang học");
        }
        ShadowingPracticeAnswer existing = practiceRepository.findAnswer(item.getId());
        if (existing != null && existing.getResult() != AnswerResult.INCORRECT) {
            throw new ApiException(ErrorCode.CONFLICT, "Câu này đã hoàn thành");
        }
        Subtitle subtitle = subtitleRepository.findById(item.getSubtitleId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phụ đề"));
        ShadowingClozeCandidate candidate = item.getClozeCandidateId() == null
                ? null : practiceRepository.findCandidate(item.getClozeCandidateId());
        PreparedQuestion prepared = item.getPreparedQuestionId() == null
                ? null : questionBank.findById(item.getPreparedQuestionId());
        requirePreparedQuestion(session, prepared);
        String expected = prepared != null ? prepared.answer()
                : session.getMode() == PracticeMode.CLOZE && candidate != null
                ? candidate.getAnswerText() : subtitle.getText();
        if (prepared != null && !"GAP".equals(prepared.kind())
                && !prepared.choices().contains(request.answer().strip())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy chọn một đáp án trong các lựa chọn");
        }
        JapaneseAnswerMatcher.MatchResult match = matchAnswer(session.getMode(), subtitle, candidate,
                prepared, request.answer());
        String expectedNormalized = match == null ? normalizeAnswer(expected) : match.normalizedExpected();
        String userNormalized = match == null ? normalizeAnswer(request.answer()) : match.normalizedActual();
        boolean correct = match == null ? expected.equals(request.answer().strip()) : match.isCorrect();

        AnswerResult result;
        int score;
        String feedback;
        if (correct) {
            result = AnswerResult.CORRECT;
            score = 100;
            feedback = prepared != null && !"GAP".equals(prepared.kind())
                    ? "Chính xác. Bạn đã hiểu đúng nội dung đoạn nghe."
                    : match != null && match.usesEquivalentWriting()
                    ? "Chính xác. Đáp án của bạn có cùng cách đọc với câu mẫu."
                    : "Chính xác. Bạn đã nghe và viết đúng nội dung câu.";
        } else {
            result = AnswerResult.INCORRECT;
            score = match == null ? 0 : match.score();
            feedback = prepared != null && !"GAP".equals(prepared.kind())
                    ? "Lựa chọn này chưa đúng. Nghe lại đoạn hội thoại rồi thử tiếp."
                    : "Bạn đã nghe đúng một phần. Xem chữ xanh, nghe lại phần chữ đỏ rồi thử tiếp.";
        }
        ShadowingPracticeAnswer answer = new ShadowingPracticeAnswer();
        answer.setPracticeItemId(item.getId());
        answer.setAttemptNumber(existing == null ? 1 : existing.getAttemptNumber() + 1);
        answer.setUserAnswer(request.answer().strip());
        answer.setNormalizedAnswer(userNormalized);
        answer.setResult(result);
        answer.setScore(score);
        answer.setFeedback(feedback);
        practiceRepository.insertAnswer(answer);
        if (result == AnswerResult.CORRECT) practiceRepository.addSessionScore(sessionId, score, true);
        return new PracticeAnswerResponse(result, score, feedback,
                result == AnswerResult.CORRECT ? expected : null, request.answer().strip(),
                result == AnswerResult.CORRECT ? subtitle.getTranslation() : null,
                result == AnswerResult.CORRECT
                        ? answerDifferences(result, expectedNormalized, userNormalized)
                        : concealedDifferences(match),
                result == AnswerResult.CORRECT && prepared != null ? prepared.explanation() : null);
    }

    private static JapaneseAnswerMatcher.MatchResult matchAnswer(PracticeMode mode, Subtitle subtitle,
            ShadowingClozeCandidate candidate, PreparedQuestion prepared, String actual) {
        if (prepared != null) {
            return "GAP".equals(prepared.kind()) ? matchPreparedGap(prepared, subtitle, actual) : null;
        }
        return mode == PracticeMode.CLOZE && candidate != null
                ? JapaneseAnswerMatcher.matchCloze(subtitle.getText(), subtitle.getFurigana(), candidate.getAnswerText(),
                candidate.getReadingText(), candidate.getOccurrenceIndex(), actual)
                : JapaneseAnswerMatcher.matchSentence(subtitle.getText(), subtitle.getFurigana(), actual);
    }

    static JapaneseAnswerMatcher.MatchResult matchPreparedGap(PreparedQuestion question, Subtitle subtitle,
                                                               String actual) {
        String spoken = QuestionTranscript.spoken(subtitle.getText());
        String reading = QuestionTranscript.spoken(subtitle.getFurigana());
        if ("HARD".equals(question.level()) || QuestionTranscript.sentenceKey(question.answer())
                .equals(QuestionTranscript.sentenceKey(spoken))) {
            return JapaneseAnswerMatcher.matchSentence(question.answer(), reading, actual);
        }
        return JapaneseAnswerMatcher.matchCloze(spoken, reading, question.answer(), "", question.occurrence(), actual);
    }

    /** What a learner may type in kana for this GAP question; null when its kanji could only be typed as kanji. */
    static String preparedGapReading(String level, String answer, int occurrence, Subtitle subtitle) {
        String spoken = QuestionTranscript.spoken(subtitle.getText());
        String reading = QuestionTranscript.spoken(subtitle.getFurigana());
        if ("HARD".equals(level) || QuestionTranscript.sentenceKey(answer)
                .equals(QuestionTranscript.sentenceKey(spoken))) {
            return JapaneseAnswerMatcher.sentenceReading(answer, reading);
        }
        return JapaneseAnswerMatcher.clozeReading(spoken, reading, answer, occurrence);
    }

    @Transactional
    public PracticeAnswerResponse reveal(long userId, long sessionId) {
        ShadowingPracticeSession session = lockedSession(userId, sessionId);
        if (session.getStatus() == PracticeSessionStatus.COMPLETED) {
            throw new ApiException(ErrorCode.CONFLICT, "Phiên học đã hoàn thành");
        }
        ShadowingPracticeItem item = practiceRepository.findItem(sessionId, session.getCurrentPosition());
        if (item == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy câu hiện tại");
        ShadowingPracticeAnswer previous = practiceRepository.findAnswer(item.getId());
        if (previous == null || previous.getResult() != AnswerResult.INCORRECT) {
            throw new ApiException(ErrorCode.CONFLICT, "Hãy thử trả lời trước khi hiện đáp án");
        }
        Subtitle subtitle = subtitleRepository.findById(item.getSubtitleId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phụ đề"));
        ShadowingClozeCandidate candidate = item.getClozeCandidateId() == null
                ? null : practiceRepository.findCandidate(item.getClozeCandidateId());
        PreparedQuestion prepared = item.getPreparedQuestionId() == null
                ? null : questionBank.findById(item.getPreparedQuestionId());
        requirePreparedQuestion(session, prepared);
        String expected = prepared != null ? prepared.answer()
                : session.getMode() == PracticeMode.CLOZE && candidate != null
                ? candidate.getAnswerText() : subtitle.getText();
        ShadowingPracticeAnswer revealed = new ShadowingPracticeAnswer();
        revealed.setPracticeItemId(item.getId());
        revealed.setAttemptNumber(previous.getAttemptNumber() + 1);
        revealed.setUserAnswer(previous.getUserAnswer());
        revealed.setNormalizedAnswer(previous.getNormalizedAnswer());
        revealed.setResult(AnswerResult.REVEALED);
        revealed.setScore(previous.getScore());
        revealed.setFeedback("Đã hiện đáp án. Hãy nghe lại câu trước khi tiếp tục.");
        practiceRepository.insertAnswer(revealed);
        practiceRepository.addSessionScore(sessionId, previous.getScore(), false);
        return answerResponse(session.getMode(), subtitle, candidate, prepared, revealed);
    }

    @Transactional
    public PracticeSessionView next(long userId, long sessionId) {
        ShadowingPracticeSession session = lockedSession(userId, sessionId);
        if (session.getStatus() == PracticeSessionStatus.COMPLETED) return get(userId, sessionId);
        ShadowingPracticeItem current = practiceRepository.findItem(sessionId, session.getCurrentPosition());
        ShadowingPracticeAnswer answer = current == null ? null : practiceRepository.findAnswer(current.getId());
        if (answer == null || answer.getResult() == AnswerResult.INCORRECT) {
            throw new ApiException(ErrorCode.CONFLICT, "Hãy trả lời đúng hoặc chọn hiện đáp án trước khi tiếp tục");
        }
        int nextPosition = session.getCurrentPosition() + 1;
        practiceRepository.moveTo(sessionId, nextPosition, nextPosition >= session.getTotalQuestions());
        return get(userId, sessionId);
    }

    private Video accessibleVideo(long userId, long videoId) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy video"));
        boolean owner = video.getOwnerId() != null && video.getOwnerId() == userId;
        boolean publicReady = video.getStatus() == VideoStatus.READY
                && (video.getVisibility() == null || video.getVisibility() == VideoVisibility.PUBLIC);
        if (!owner && !publicReady) throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền học video này");
        return video;
    }

    private ShadowingPracticeSession ownedSession(long userId, long sessionId) {
        ShadowingPracticeSession session = practiceRepository.findOwnedSession(sessionId, userId);
        if (session == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên học");
        return session;
    }

    private static List<QuestionSeed> buildSeeds(List<Subtitle> subtitles,
            Map<Long, ShadowingExerciseAnalysis> analyses, PracticeMode mode) {
        List<QuestionSeed> result = new ArrayList<>();
        for (Subtitle subtitle : subtitles) {
            ShadowingExerciseAnalysis analysis = analyses.get(subtitle.getId());
            if (analysis == null) continue;
            ShadowingClozeCandidate candidate = null;
            int difficulty = analysis.getSentenceDifficulty();
            if (mode == PracticeMode.CLOZE) {
                candidate = analysis.getCandidates().stream()
                        .max(Comparator.comparingInt(ShadowingPracticeService::candidateQuality)).orElse(null);
                if (candidate == null || candidate.getId() == null) continue;
                difficulty = clampScore((analysis.getSentenceDifficulty() + candidate.getDifficultyScore()) / 2);
            }
            result.add(new QuestionSeed(subtitle, candidate, null, difficulty));
        }
        return result;
    }

    private static int candidateQuality(ShadowingClozeCandidate candidate) {
        return candidate.getImportanceFactor() * 12 + candidate.getConfidenceFactor() * 7
                + Math.min(12, candidate.getAnswerText().codePointCount(0, candidate.getAnswerText().length()) * 2);
    }

    private static List<QuestionSeed> selectQuestions(
            List<QuestionSeed> available, int count, PracticeOrder order, long seed) {
        List<QuestionSeed> chronological = new ArrayList<>(available);
        chronological.sort(Comparator.comparingDouble(value -> value.subtitle().getStartTime()));
        if (order == PracticeOrder.PROGRESSIVE) {
            return progressiveStops(chronological, count);
        }
        List<QuestionSeed> selected = weightedRandom(chronological, count, new Random(seed));
        selected.sort(Comparator.comparingDouble(value -> value.subtitle().getStartTime()));
        return selected;
    }

    private static List<QuestionSeed> progressiveStops(List<QuestionSeed> chronological, int count) {
        if (count >= chronological.size()) return new ArrayList<>(chronological);
        int minimum = chronological.stream().mapToInt(QuestionSeed::difficulty).min().orElse(0);
        int maximum = chronological.stream().mapToInt(QuestionSeed::difficulty).max().orElse(100);
        List<QuestionSeed> selected = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            int from = index * chronological.size() / count;
            int to = Math.max(from + 1, (index + 1) * chronological.size() / count);
            int target = count == 1 ? (minimum + maximum) / 2
                    : (int) Math.round(minimum + index * (maximum - minimum) / (double) (count - 1));
            QuestionSeed best = chronological.subList(from, Math.min(to, chronological.size())).stream()
                    .min(Comparator.comparingInt(value -> Math.abs(value.difficulty() - target)))
                    .orElse(chronological.get(from));
            selected.add(best);
        }
        return selected;
    }

    private static List<QuestionSeed> weightedRandom(List<QuestionSeed> available, int count, Random random) {
        List<QuestionSeed> pool = new ArrayList<>(available);
        List<QuestionSeed> selected = new ArrayList<>();
        while (!pool.isEmpty() && selected.size() < count) {
            double total = pool.stream().mapToDouble(seed -> randomWeight(seed.difficulty())).sum();
            double target = random.nextDouble() * total;
            int chosen = 0;
            for (int index = 0; index < pool.size(); index++) {
                target -= randomWeight(pool.get(index).difficulty());
                if (target <= 0) { chosen = index; break; }
            }
            selected.add(pool.remove(chosen));
        }
        return selected;
    }

    private static double randomWeight(int difficulty) {
        if (difficulty < 25) return .35;
        if (difficulty < 45) return .65;
        if (difficulty < 65) return 1.4;
        if (difficulty < 80) return 1.2;
        return .55;
    }

    private static String mask(String text, ShadowingClozeCandidate candidate) {
        int start = nthIndexOf(text, candidate.getAnswerText(), candidate.getOccurrenceIndex());
        if (start < 0) return text;
        int codePoints = candidate.getAnswerText().codePointCount(0, candidate.getAnswerText().length());
        String blank = "＿".repeat(Math.max(3, Math.min(12, codePoints)));
        return text.substring(0, start) + blank + text.substring(start + candidate.getAnswerText().length());
    }

    private static int nthIndexOf(String text, String needle, int occurrence) {
        int offset = 0;
        for (int current = 1; current <= Math.max(1, occurrence); current++) {
            offset = text.indexOf(needle, offset);
            if (offset < 0) return -1;
            if (current == occurrence) return offset;
            offset += needle.length();
        }
        return -1;
    }

    static String normalizeAnswer(String value) {
        return JapaneseAnswerMatcher.normalize(value);
    }

    static int similarityScore(String expected, String actual) {
        int[] left = expected.codePoints().toArray();
        int[] right = actual.codePoints().toArray();
        int distance = levenshtein(left, right);
        int length = Math.max(1, Math.max(left.length, right.length));
        return clampScore((int) Math.round((1.0 - distance / (double) length) * 100));
    }

    private static int levenshtein(int[] left, int[] right) {
        int[] previous = new int[right.length + 1];
        for (int index = 0; index <= right.length; index++) previous[index] = index;
        for (int i = 1; i <= left.length; i++) {
            int[] current = new int[right.length + 1];
            current[0] = i;
            for (int j = 1; j <= right.length; j++) {
                int cost = left[i - 1] == right[j - 1] ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length];
    }

    private static String firstDifferenceFeedback(String expected, String actual) {
        int[] left = expected.codePoints().toArray();
        int[] right = actual.codePoints().toArray();
        int index = 0;
        while (index < left.length && index < right.length && left[index] == right[index]) index++;
        if (index >= left.length && right.length > left.length) {
            return "Bạn nhập thừa nội dung từ vị trí " + (index + 1) + ".";
        }
        if (index >= right.length && left.length > right.length) {
            return "Bạn còn thiếu nội dung từ vị trí " + (index + 1) + ".";
        }
        if (index < left.length && index < right.length) {
            return "Vị trí " + (index + 1) + " cần “" + new String(left, index, 1)
                    + "”, nhưng bạn nhập “" + new String(right, index, 1) + "”.";
        }
        return "Đáp án chưa khớp câu mẫu.";
    }

    static List<PracticeDiffPart> differences(String expected, String actual) {
        int[] left = expected.codePoints().toArray();
        int[] right = actual.codePoints().toArray();
        int[][] distance = new int[left.length + 1][right.length + 1];
        for (int i = 0; i <= left.length; i++) distance[i][0] = i;
        for (int j = 0; j <= right.length; j++) distance[0][j] = j;
        for (int i = 1; i <= left.length; i++) {
            for (int j = 1; j <= right.length; j++) {
                int replaceCost = left[i - 1] == right[j - 1] ? 0 : 1;
                distance[i][j] = Math.min(distance[i - 1][j - 1] + replaceCost,
                        Math.min(distance[i - 1][j] + 1, distance[i][j - 1] + 1));
            }
        }

        List<PracticeDiffPart> reversed = new ArrayList<>();
        int i = left.length;
        int j = right.length;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && left[i - 1] == right[j - 1]
                    && distance[i][j] == distance[i - 1][j - 1]) {
                reversed.add(new PracticeDiffPart("MATCH", codePoint(left[i - 1]), codePoint(right[j - 1])));
                i--;
                j--;
            } else if (i > 0 && j > 0 && distance[i][j] == distance[i - 1][j - 1] + 1) {
                reversed.add(new PracticeDiffPart("REPLACE", codePoint(left[i - 1]), codePoint(right[j - 1])));
                i--;
                j--;
            } else if (i > 0 && distance[i][j] == distance[i - 1][j] + 1) {
                reversed.add(new PracticeDiffPart("MISSING", codePoint(left[i - 1]), ""));
                i--;
            } else {
                reversed.add(new PracticeDiffPart("EXTRA", "", codePoint(right[j - 1])));
                j--;
            }
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private static List<PracticeDiffPart> answerDifferences(
            AnswerResult result, String expected, String actual) {
        if (result == AnswerResult.CORRECT && !expected.equals(actual)) {
            return List.of(new PracticeDiffPart("MATCH", expected, actual));
        }
        return differences(expected, actual);
    }

    /**
     * Số câu theo từng mức của từng dạng ("MỨC:DẠNG" → số câu; bỏ lựa chọn 0 câu); null khi không gửi để giữ
     * cách chọn trải đều cũ.
     */
    private static Map<String, Integer> selectionCounts(CreatePracticeSessionRequest request) {
        if (request.selectionCounts() == null || request.selectionCounts().isEmpty()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        request.selectionCounts().forEach((pair, count) -> {
            String[] parts = pair == null ? new String[0] : pair.split(":", -1);
            if (parts.length != 2 || !ShadowingQuestionPreparationService.LEVELS.contains(parts[0])
                    || !ShadowingQuestionPreparationService.KINDS.contains(parts[1])
                    || count == null || count < 0 || count > 500) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Số câu của từng mức không hợp lệ");
            }
            if (count > 0) counts.put(pair, count);
        });
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        if (total < 1 || total > 500) throw new ApiException(ErrorCode.BAD_REQUEST, "Tổng số câu phải từ 1 đến 500");
        return counts;
    }

    private static String pairOf(QuestionSeed question) {
        return question.prepared().level() + ":" + question.prepared().kind();
    }

    /** Giữ các câu không chồng thời gian, ưu tiên câu kết thúc sớm (đầu vào đã xếp theo endTime). */
    private static List<QuestionSeed> nonOverlapping(List<QuestionSeed> byEnd) {
        List<QuestionSeed> result = new ArrayList<>();
        double lastEnd = Double.NEGATIVE_INFINITY;
        for (QuestionSeed seedItem : byEnd) {
            if (seedItem.prepared().startTime() >= lastEnd) {
                result.add(seedItem);
                lastEnd = seedItem.prepared().endTime();
            }
        }
        return result;
    }

    private static boolean overlaps(QuestionSeed left, QuestionSeed right) {
        return left.prepared().startTime() < right.prepared().endTime()
                && right.prepared().startTime() < left.prepared().endTime();
    }

    /**
     * Lấy ngẫu nhiên đúng số câu của từng mức trong từng dạng, ưu tiên câu không trùng đoạn với dạng khác đã lấy
     * (vẫn đủ số câu khi buộc phải trùng), rồi xếp theo thời gian trong video.
     */
    private static List<QuestionSeed> stopsPerSelection(List<QuestionSeed> available, Map<String, Integer> counts, long seed) {
        Random random = new Random(seed);
        List<QuestionSeed> selected = new ArrayList<>();
        counts.forEach((pair, count) -> {
            List<QuestionSeed> pool = new ArrayList<>(available.stream()
                    .filter(question -> pair.equals(pairOf(question))).toList());
            java.util.Collections.shuffle(pool, random);
            pool.sort(Comparator.comparing(question -> selected.stream().anyMatch(taken -> overlaps(taken, question))));
            selected.addAll(pool.subList(0, Math.min(count, pool.size())));
        });
        selected.sort(Comparator.comparingDouble(question -> question.subtitle().getStartTime()));
        return selected;
    }

    private static List<QuestionSeed> balancedStops(List<QuestionSeed> available, int count, long seed) {
        Map<String, List<QuestionSeed>> buckets = new LinkedHashMap<>();
        for (QuestionSeed question : available) {
            String key = question.prepared().level() + ":"
                    + ("GAP".equals(question.prepared().kind()) ? "GAP" : "COMPREHENSION");
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>()).add(question);
        }
        Random random = new Random(seed);
        buckets.values().forEach(bucket -> java.util.Collections.shuffle(bucket, random));
        List<QuestionSeed> selected = new ArrayList<>();
        while (selected.size() < count) {
            boolean added = false;
            for (List<QuestionSeed> bucket : buckets.values()) {
                if (bucket.isEmpty() || selected.size() >= count) continue;
                selected.add(bucket.remove(bucket.size() - 1));
                added = true;
            }
            if (!added) break;
        }
        selected.sort(Comparator.comparingDouble(question -> question.subtitle().getStartTime()));
        return selected;
    }

    private ShadowingPracticeSession lockedSession(long userId, long sessionId) {
        ShadowingPracticeSession session = practiceRepository.lockOwnedSession(sessionId, userId);
        if (session == null) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phiên học");
        return session;
    }

    private static void requirePreparedQuestion(ShadowingPracticeSession session,
                                                PreparedQuestion prepared) {
        if (session.getMode() == PracticeMode.LISTENING && prepared == null) {
            throw new ApiException(ErrorCode.NOT_FOUND,
                    "Câu hỏi đã chuẩn bị không còn tồn tại. Vui lòng tạo phiên học mới.");
        }
    }

    private static List<PracticeDiffPart> concealedDifferences(JapaneseAnswerMatcher.MatchResult match) {
        if (match == null) return List.of();
        return match.feedback().stream().map(part ->
                new PracticeDiffPart(part.matched() ? "MATCH" : "MASK", "", part.text())).toList();
    }

    private static String codePoint(int value) {
        return new String(Character.toChars(value));
    }

    private PracticeAnswerResponse answerResponse(PracticeMode mode, Subtitle subtitle,
            ShadowingClozeCandidate candidate, PreparedQuestion prepared, ShadowingPracticeAnswer answer) {
        String expected = prepared != null ? prepared.answer()
                : mode == PracticeMode.CLOZE && candidate != null
                ? candidate.getAnswerText() : subtitle.getText();
        String expectedNormalized = normalizeAnswer(expected);
        boolean settled = answer.getResult() != AnswerResult.INCORRECT;
        return new PracticeAnswerResponse(answer.getResult(), answer.getScore(), answer.getFeedback(),
                settled ? expected : null, answer.getUserAnswer(),
                settled ? subtitle.getTranslation() : null,
                settled ? answerDifferences(answer.getResult(), expectedNormalized, answer.getNormalizedAnswer())
                        : concealedDifferences(matchAnswer(mode, subtitle, candidate, prepared, answer.getUserAnswer())),
                settled && prepared != null ? prepared.explanation() : null);
    }

    private static PracticeSessionView sessionView(
            ShadowingPracticeSession session, Video video, List<PracticeStopView> stops,
            PracticeQuestionView question) {
        int average = session.getTotalQuestions() == 0 ? 0
                : session.getScoreTotal() / Math.max(1, session.getCurrentPosition()
                + (question != null && question.answered() ? 1 : 0));
        return new PracticeSessionView(session.getId(), video.getId(), video.getTitle(), video.isAudioOnly(),
                session.getMode(), session.getOrder(), session.getCurrentPosition(), session.getTotalQuestions(),
                session.getCorrectCount(), average, session.getStatus() == PracticeSessionStatus.COMPLETED,
                stops, question);
    }

    private void rateLimit(long userId) {
        Instant now = Instant.now();
        Deque<Instant> requests = createRequests.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (requests) {
            while (!requests.isEmpty() && Duration.between(requests.peekFirst(), now).toSeconds() >= 60) {
                requests.removeFirst();
            }
            if (requests.size() >= MAX_CREATE_REQUESTS_PER_MINUTE) {
                throw new ApiException(ErrorCode.TOO_MANY_REQUESTS,
                        "Bạn tạo phiên học quá nhanh. Vui lòng đợi một phút rồi thử lại.");
            }
            requests.addLast(now);
        }
    }

    private static String difficultyLabel(int score) {
        if (score < 25) return "Rất dễ";
        if (score < 45) return "Dễ";
        if (score < 65) return "Trung bình";
        if (score < 80) return "Khó";
        return "Rất khó";
    }

    private static String levelLabel(String level) {
        return switch (level) {
            case "EASY" -> "Dễ";
            case "MEDIUM" -> "Vừa";
            default -> "Khó";
        };
    }

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record QuestionSeed(Subtitle subtitle, ShadowingClozeCandidate candidate,
                                PreparedQuestion prepared, int difficulty) {
    }
}
