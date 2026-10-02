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
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShadowingPracticeService {
    private static final int DEFAULT_QUESTION_COUNT = 10;
    private static final int MAX_CREATE_REQUESTS_PER_MINUTE = 5;
    private final VideoRepository videoRepository;
    private final SubtitleRepository subtitleRepository;
    private final ShadowingExerciseAnalysisService analysisService;
    private final ShadowingPracticeRepository practiceRepository;
    private final Map<Long, Deque<Instant>> createRequests = new ConcurrentHashMap<>();

    public ShadowingPracticeService(VideoRepository videoRepository,
                                    SubtitleRepository subtitleRepository,
                                    ShadowingExerciseAnalysisService analysisService,
                                    ShadowingPracticeRepository practiceRepository) {
        this.videoRepository = videoRepository;
        this.subtitleRepository = subtitleRepository;
        this.analysisService = analysisService;
        this.practiceRepository = practiceRepository;
    }

    public CreatePracticeSessionResponse create(long userId, CreatePracticeSessionRequest request) {
        rateLimit(userId);
        Video video = accessibleVideo(userId, request.videoId());
        List<Subtitle> subtitles = subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(video.getId())
                .stream().filter(subtitle -> subtitle.getText() != null && !subtitle.getText().isBlank()).toList();
        if (subtitles.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Bài học chưa có phụ đề hợp lệ");
        int requested = request.allQuestions() ? subtitles.size()
                : Math.min(subtitles.size(), request.questionCount() == null
                ? DEFAULT_QUESTION_COUNT : request.questionCount());
        Map<Long, ShadowingExerciseAnalysis> analyses = analysisService.ensureAnalyses(
                subtitles, requested, request.allQuestions());
        List<QuestionSeed> available = buildSeeds(subtitles, analyses, request.mode());
        if (available.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    request.mode() == PracticeMode.CLOZE
                            ? "Chưa tạo được vị trí điền phù hợp cho bài này"
                            : "Bài học chưa có câu phù hợp để luyện nghe");
        }
        requested = request.allQuestions() ? available.size() : Math.min(requested, available.size());
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        List<QuestionSeed> selected = selectQuestions(available, requested, request.order(), seed);

        ShadowingPracticeSession session = new ShadowingPracticeSession();
        session.setUserId(userId);
        session.setVideoId(video.getId());
        session.setMode(request.mode());
        session.setOrder(request.order());
        session.setRequestedCount(requested);
        session.setTotalQuestions(selected.size());
        session.setRandomSeed(seed);
        List<ShadowingPracticeItem> items = new ArrayList<>();
        for (int index = 0; index < selected.size(); index++) {
            QuestionSeed seedItem = selected.get(index);
            ShadowingPracticeItem item = new ShadowingPracticeItem();
            item.setSubtitleId(seedItem.subtitle().getId());
            item.setClozeCandidateId(seedItem.candidate() == null ? null : seedItem.candidate().getId());
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
        ShadowingPracticeAnswer previous = practiceRepository.findAnswer(item.getId());
        PracticeAnswerResponse previousResponse = previous == null ? null
                : answerResponse(session.getMode(), subtitle, candidate, previous);
        String prompt = session.getMode() == PracticeMode.CLOZE && candidate != null
                ? mask(subtitle.getText(), candidate) : "";
        PracticeQuestionView question = new PracticeQuestionView(
                item.getId(), subtitle.getId(), item.getPosition() + 1, session.getTotalQuestions(),
                item.getDifficultyScore(), difficultyLabel(item.getDifficultyScore()),
                subtitle.getStartTime(), subtitle.getEndTime(), prompt,
                previous == null ? null : subtitle.getTranslation(), previous != null, previousResponse);
        return sessionView(session, video, stops, question);
    }

    @Transactional
    public PracticeAnswerResponse submit(long userId, long sessionId, SubmitPracticeAnswerRequest request) {
        ShadowingPracticeSession session = ownedSession(userId, sessionId);
        if (session.getStatus() == PracticeSessionStatus.COMPLETED) {
            throw new ApiException(ErrorCode.CONFLICT, "Phiên học đã hoàn thành");
        }
        ShadowingPracticeItem item = practiceRepository.findItem(sessionId, session.getCurrentPosition());
        if (item == null || !item.getId().equals(request.itemId())) {
            throw new ApiException(ErrorCode.CONFLICT, "Câu trả lời không thuộc câu đang học");
        }
        ShadowingPracticeAnswer existing = practiceRepository.findAnswer(item.getId());
        if (existing != null) throw new ApiException(ErrorCode.CONFLICT, "Câu này đã được chấm");
        Subtitle subtitle = subtitleRepository.findById(item.getSubtitleId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy phụ đề"));
        ShadowingClozeCandidate candidate = item.getClozeCandidateId() == null
                ? null : practiceRepository.findCandidate(item.getClozeCandidateId());
        String expected = session.getMode() == PracticeMode.CLOZE && candidate != null
                ? candidate.getAnswerText() : subtitle.getText();
        JapaneseAnswerMatcher.MatchResult match = session.getMode() == PracticeMode.CLOZE && candidate != null
                ? JapaneseAnswerMatcher.matchCloze(subtitle.getText(), subtitle.getFurigana(), expected,
                candidate.getReadingText(), candidate.getOccurrenceIndex(), request.answer())
                : JapaneseAnswerMatcher.matchSentence(expected, subtitle.getFurigana(), request.answer());
        String expectedNormalized = match.normalizedExpected();
        String userNormalized = match.normalizedActual();

        AnswerResult result;
        int score;
        String feedback;
        if (match.isCorrect()) {
            result = AnswerResult.CORRECT;
            score = 100;
            feedback = match.usesEquivalentWriting()
                    ? "Chính xác. Cách viết Kanji/kana của bạn khác câu mẫu nhưng có cùng cách đọc."
                    : "Chính xác. Bạn đã nghe và viết đúng nội dung câu.";
        } else {
            result = AnswerResult.INCORRECT;
            score = match.score();
            feedback = firstDifferenceFeedback(expectedNormalized, userNormalized);
        }
        ShadowingPracticeAnswer answer = new ShadowingPracticeAnswer();
        answer.setPracticeItemId(item.getId());
        answer.setAttemptNumber(1);
        answer.setUserAnswer(request.answer().strip());
        answer.setNormalizedAnswer(userNormalized);
        answer.setResult(result);
        answer.setScore(score);
        answer.setFeedback(feedback);
        practiceRepository.insertAnswer(answer);
        practiceRepository.addSessionScore(sessionId, score, result == AnswerResult.CORRECT);
        return new PracticeAnswerResponse(result, score, feedback, expected, request.answer().strip(),
                subtitle.getTranslation(), answerDifferences(result, expectedNormalized, userNormalized));
    }

    @Transactional
    public PracticeSessionView next(long userId, long sessionId) {
        ShadowingPracticeSession session = ownedSession(userId, sessionId);
        if (session.getStatus() == PracticeSessionStatus.COMPLETED) return get(userId, sessionId);
        ShadowingPracticeItem current = practiceRepository.findItem(sessionId, session.getCurrentPosition());
        if (current == null || practiceRepository.findAnswer(current.getId()) == null) {
            throw new ApiException(ErrorCode.CONFLICT, "Hãy kiểm tra đáp án trước khi sang câu tiếp theo");
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
            result.add(new QuestionSeed(subtitle, candidate, difficulty));
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

    private static String codePoint(int value) {
        return new String(Character.toChars(value));
    }

    private PracticeAnswerResponse answerResponse(PracticeMode mode, Subtitle subtitle,
            ShadowingClozeCandidate candidate, ShadowingPracticeAnswer answer) {
        String expected = mode == PracticeMode.CLOZE && candidate != null
                ? candidate.getAnswerText() : subtitle.getText();
        String expectedNormalized = normalizeAnswer(expected);
        return new PracticeAnswerResponse(answer.getResult(), answer.getScore(), answer.getFeedback(),
                expected, answer.getUserAnswer(), subtitle.getTranslation(),
                answerDifferences(answer.getResult(), expectedNormalized, answer.getNormalizedAnswer()));
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

    private static int clampScore(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private record QuestionSeed(Subtitle subtitle, ShadowingClozeCandidate candidate, int difficulty) {
    }
}
