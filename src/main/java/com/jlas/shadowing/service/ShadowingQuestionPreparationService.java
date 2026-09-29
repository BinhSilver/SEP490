package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoStatus;
import com.jlas.shadowing.model.practice.PreparedQuestion;
import com.jlas.shadowing.repository.ShadowingQuestionBankRepository;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ShadowingQuestionPreparationService {
    private static final Logger log = LoggerFactory.getLogger(ShadowingQuestionPreparationService.class);
    public static final Set<String> LEVELS = Set.of("EASY", "MEDIUM", "HARD");
    public static final Set<String> COMPREHENSION_KINDS = Set.of("ACTION_SEQUENCE", "DETAIL",
            "REASON_PURPOSE", "MAIN_IDEA", "PROBLEM_SOLUTION", "ATTITUDE_DECISION");
    public static final Set<String> KINDS = Set.of("GAP", "ACTION_SEQUENCE", "DETAIL", "REASON_PURPOSE",
            "MAIN_IDEA", "PROBLEM_SOLUTION", "ATTITUDE_DECISION");
    private static final String SOURCE_VERSION = "listening-v1";
    private static final String QUESTION_VERSION = "listening-v2-ja";
    private static final int BATCH_SIZE = 24;
    private static final int MAX_SUBTITLES = 72;
    private static final int MAX_QUESTIONS_PER_JOB = 21;

    private final VideoRepository videos;
    private final SubtitleRepository subtitles;
    private final ShadowingQuestionBankRepository bank;
    private final ShadowingQuestionAiClient ai;
    private final SubtitleQualityReviewService qualityReview;
    private final ExecutorService jobs = Executors.newFixedThreadPool(2);
    private final ThreadPoolExecutor batches = new ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(9), new ThreadPoolExecutor.CallerRunsPolicy());
    private final AtomicInteger activeJobs = new AtomicInteger();

    public ShadowingQuestionPreparationService(VideoRepository videos, SubtitleRepository subtitles,
                                               ShadowingQuestionBankRepository bank,
                                               ShadowingQuestionAiClient ai,
                                               SubtitleQualityReviewService qualityReview) {
        this.videos = videos;
        this.subtitles = subtitles;
        this.bank = bank;
        this.ai = ai;
        this.qualityReview = qualityReview;
    }

    public Map<String, Object> status(long userId, long videoId) {
        Video video = requireCanPrepare(userId, videoId);
        boolean owner = video.getOwnerId() != null && video.getOwnerId() == userId;
        List<Subtitle> source = source(videoId);
        List<PreparedQuestion> allQuestions = validQuestions(videoId, source, true);
        List<PreparedQuestion> questions = owner ? allQuestions : validQuestions(videoId, source);
        long draftCount = questions.stream().filter(question -> "DRAFT".equals(question.status())).count();
        ShadowingQuestionBankRepository.PreparationJob job = bank.findJob(videoId);
        Map<String, Integer> counts = counts(questions);
        Map<String, Integer> missing = new LinkedHashMap<>();
        if (job != null) job.targets().forEach((kind, target) -> {
            long present = allQuestions.stream().filter(q -> kind.equals(q.kind())).count();
            if (target > present) missing.put(kind, target - (int) present);
        });
        int missingTotal = missing.values().stream().mapToInt(Integer::intValue).sum();
        String effectiveStatus = job == null ? "IDLE"
                : "RUNNING".equals(job.status()) || "QUEUED".equals(job.status())
                ? job.status()
                : missingTotal > 0 && "READY".equals(job.status()) ? "PARTIAL"
                : "READY".equals(job.status()) && draftCount > 0 ? "REVIEW" : job.status();
        String message = job == null || job.errorMessage() == null ? "" : job.errorMessage();
        if (missingTotal > 0 && !"RUNNING".equals(effectiveStatus)
                && !"QUEUED".equals(effectiveStatus))
            message = "Hiện còn thiếu " + missingTotal + " câu. Xem các dạng còn thiếu bên dưới.";
        Map<String, Object> status = new LinkedHashMap<>(Map.of("status", effectiveStatus,
                "progress", job == null ? 0 : job.progress(),
                "message", message,
                "levels", job == null ? List.of() : job.levels(),
                "kinds", job == null ? List.of() : job.kinds(),
                "counts", counts, "questions", questions,
                "draftCount", draftCount,
                "aiAvailable", ai.available()));
        status.put("selectedJlptLevel", job == null || job.selectedJlptLevel() == null
                ? "" : job.selectedJlptLevel());
        status.put("suggestedJlptLevel", job == null || job.suggestedJlptLevel() == null
                ? "" : job.suggestedJlptLevel());
        status.put("levelOutdated", job != null && job.suggestionHash() != null
                && !job.suggestionHash().equals(hashSources(source)));
        status.put("capacities", capacities(job, source));
        status.put("remaining", remaining(job, source, allQuestions));
        Map<String, Integer> preparedCounts = new LinkedHashMap<>();
        allQuestions.forEach(question -> preparedCounts.merge(question.kind(), 1, Integer::sum));
        status.put("preparedCounts", preparedCounts);
        status.put("excludedQuestionCount", owner
                ? Math.max(0, bank.findByVideo(videoId, true).size() - allQuestions.size()) : 0);
        status.put("targets", job == null ? Map.of() : job.targets());
        status.put("missing", missing);
        boolean missingAfterCompletion = job != null && missingTotal > 0
                && "READY".equals(job.status());
        status.put("missingAfterCompletion", missingAfterCompletion);
        status.put("generationIssues", owner && job != null && !missingAfterCompletion
                ? bank.generationIssues(videoId) : List.of());
        status.put("owner", owner);
        status.put("contributedToday", !owner && bank.contributedToday(videoId, userId));
        return status;
    }

    public Map<String, Object> availability(long userId, long videoId) {
        Video video = videos.findById(videoId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy video"));
        boolean owner = video.getOwnerId() != null && video.getOwnerId() == userId;
        if (!owner && (video.getStatus() != VideoStatus.READY
                || video.getVisibility() != com.jlas.shadowing.model.VideoVisibility.PUBLIC)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền học video này");
        }
        List<Subtitle> source = source(videoId);
        List<PreparedQuestion> valid = validQuestions(videoId, source);
        List<PreparedQuestion> allPrepared = validQuestions(videoId, source, true);
        ShadowingQuestionBankRepository.PreparationJob job = bank.findJob(videoId);
        String currentStatus = job == null ? "IDLE" : job.status();
        if (job != null && "READY".equals(job.status())) {
            boolean belowTarget = missingCounts(job, allPrepared).values().stream()
                    .anyMatch(count -> count > 0);
            currentStatus = belowTarget ? "PARTIAL"
                    : allPrepared.stream().anyMatch(question -> "DRAFT".equals(question.status()))
                    ? "REVIEW" : "READY";
        }
        return Map.of("counts", counts(valid), "total", valid.size(),
                "questions", valid.stream().map(question -> Map.of("subtitleId", question.subtitleId(),
                        "kind", question.kind(), "level", question.level(),
                        "startTime", question.startTime(), "endTime", question.endTime())).toList(),
                "status", currentStatus,
                "videoJlptLevel", job == null || job.selectedJlptLevel() == null
                        ? "" : job.selectedJlptLevel());
    }

    public Map<String, Object> suggestions(long userId, long videoId) {
        requireCanPrepare(userId, videoId);
        List<Subtitle> source = source(videoId);
        if (source.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Video chưa có phụ đề");
        List<Subtitle> sample = spread(source, 28);
        String hash = hashSources(source);
        ShadowingQuestionBankRepository.PreparationJob job = bank.findJob(videoId);
        if (job != null && hash.equals(job.suggestionHash()) && job.suggestionsCsv() != null
                && job.suggestedJlptLevel() != null && job.capacitiesCsv() != null) {
            return Map.of("suggestions", job.suggestionsCsv().isBlank()
                            ? List.of() : List.of(job.suggestionsCsv().split(",")),
                    "suggestedJlptLevel", job.suggestedJlptLevel() == null
                            ? "" : job.suggestedJlptLevel(),
                    "capacities", capacities(job, source));
        }
        if (!ai.available()) throw new ApiException(ErrorCode.BAD_REQUEST, "Chưa cấu hình AI tạo câu hỏi");
        ShadowingQuestionAiClient.Suggestion result = ai.suggest(sample);
        if (result == null || result.jlptLevel() == null) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Chưa lấy được gợi ý cấp độ hợp lệ từ AI. Vui lòng thử lại.");
        }
        bank.saveSuggestions(videoId, String.join(",", result.kinds()), hash, result.jlptLevel(),
                ShadowingQuestionBankRepository.PreparationJob.serialize(result.capacities()));
        ShadowingQuestionBankRepository.PreparationJob saved = bank.findJob(videoId);
        return Map.of("suggestions", result.kinds(),
                "suggestedJlptLevel", result.jlptLevel() == null ? "" : result.jlptLevel(),
                "capacities", capacities(saved, source));
    }

    public Map<String, Object> selectVideoLevel(long userId, long videoId, String level) {
        requireOwner(userId, videoId);
        if (level == null || !Set.of("N5", "N4", "N3", "N2", "N1")
                .contains(level.strip().toUpperCase(java.util.Locale.ROOT))) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy chọn cấp độ video từ N5 đến N1");
        }
        String selected = level.strip().toUpperCase(java.util.Locale.ROOT);
        bank.selectVideoLevel(videoId, selected);
        return Map.of("selectedJlptLevel", selected);
    }

    @Transactional
    public Map<String, Object> queue(long userId, long videoId, Set<String> levels,
                                     Map<String, Integer> requested) {
        Video video = requireCanPrepare(userId, videoId);
        boolean owner = video.getOwnerId() != null && video.getOwnerId() == userId;
        if (video.getStatus() != VideoStatus.READY && video.getStatus() != VideoStatus.DRAFT) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy đợi xử lý phụ đề hoàn tất");
        }
        if (levels == null || levels.isEmpty() || !LEVELS.containsAll(levels)
                || requested == null || requested.isEmpty() || !KINDS.containsAll(requested.keySet())
                || requested.values().stream().anyMatch(value -> value == null || value < 1 || value > 20)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy chọn mức và dạng bài tập hợp lệ");
        }
        if (!owner && requested.values().stream().mapToInt(Integer::intValue).sum() > 10) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mỗi lượt đóng góp tối đa 10 câu hỏi");
        }
        if (requested.values().stream().mapToInt(Integer::intValue).sum() > MAX_QUESTIONS_PER_JOB) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mỗi lượt tạo tối đa 21 câu hỏi");
        }
        if (!ai.available()) throw new ApiException(ErrorCode.BAD_REQUEST, "Chưa cấu hình AI tạo câu hỏi");
        List<Subtitle> source = source(videoId);
        if (source.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Video chưa có phụ đề");
        ShadowingQuestionBankRepository.PreparationJob existing = bank.findJob(videoId);
        if (existing != null && ("QUEUED".equals(existing.status()) || "RUNNING".equals(existing.status()))) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài tập đang được chuẩn bị");
        }
        if (existing == null || !hashSources(source).equals(existing.suggestionHash())) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy lấy gợi ý cho phụ đề hiện tại trước khi tạo câu hỏi");
        }
        List<PreparedQuestion> current = validQuestions(videoId, source, true);
        Map<String, Integer> remaining = remaining(existing, source, current);
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : requested.entrySet()) {
            if (entry.getValue() > remaining.getOrDefault(entry.getKey(), 0)) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "Dạng câu hỏi này chỉ còn tạo được " + remaining.getOrDefault(entry.getKey(), 0)
                        + " câu khác nhau: " + entry.getKey());
            }
            int currentCount = (int) current.stream().filter(q -> entry.getKey().equals(q.kind())).count();
            totals.put(entry.getKey(), currentCount + entry.getValue());
        }
        if (!owner && !bank.claimContribution(videoId, userId)) {
            throw new ApiException(ErrorCode.CONFLICT, "Bạn đã đóng góp cho video này hôm nay");
        }
        if (!bank.queue(videoId, String.join(",", levels), String.join(",", requested.keySet()),
                ShadowingQuestionBankRepository.PreparationJob.serialize(totals))) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài tập đang được chuẩn bị");
        }
        bank.clearGenerationIssues(videoId);
        return Map.of("status", "QUEUED");
    }

    public void reject(long userId, long videoId, long questionId) {
        requireOwner(userId, videoId);
        bank.reject(videoId, questionId);
    }

    public Map<String, Object> approve(long userId, long videoId) {
        requireOwner(userId, videoId);
        List<Long> draftIds = validQuestions(videoId, source(videoId), true).stream()
                .filter(question -> "DRAFT".equals(question.status()))
                .map(PreparedQuestion::id).toList();
        int approved = bank.approve(videoId, draftIds);
        return Map.of("approved", approved);
    }

    @Transactional
    public Map<String, Object> approveSelected(long userId, long videoId, Set<Long> ids) {
        List<Long> selected = validDraftIds(userId, videoId, ids);
        return Map.of("approved", bank.approve(videoId, selected));
    }

    @Transactional
    public Map<String, Object> rejectSelected(long userId, long videoId, Set<Long> ids) {
        List<Long> selected = validPreparedIds(userId, videoId, ids, false);
        return Map.of("rejected", bank.rejectQuestions(videoId, selected));
    }

    private List<Long> validDraftIds(long userId, long videoId, Set<Long> ids) {
        return validPreparedIds(userId, videoId, ids, true);
    }

    private List<Long> validPreparedIds(long userId, long videoId, Set<Long> ids,
                                        boolean draftsOnly) {
        requireOwner(userId, videoId);
        if (ids == null || ids.isEmpty() || ids.size() > 500
                || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy chọn từ 1 đến 500 câu hỏi hợp lệ");
        }
        Set<Long> valid = new HashSet<>(validQuestions(videoId, source(videoId), true).stream()
                .filter(question -> !draftsOnly || "DRAFT".equals(question.status()))
                .map(PreparedQuestion::id).toList());
        if (!valid.containsAll(ids)) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Danh sách câu hỏi đã thay đổi. Hãy tải lại trang");
        }
        return new ArrayList<>(ids);
    }

    public List<PreparedQuestion> validQuestions(long videoId, List<Subtitle> source) {
        return validQuestions(videoId, source, false);
    }

    private List<PreparedQuestion> validQuestions(long videoId, List<Subtitle> source,
                                                  boolean includeDraft) {
        Map<Long, Subtitle> byId = new HashMap<>();
        source.forEach(subtitle -> byId.put(subtitle.getId(), subtitle));
        List<PreparedQuestion> unique = new ArrayList<>();
        for (PreparedQuestion question : bank.findByVideo(videoId, includeDraft)) {
            List<Subtitle> evidence = new ArrayList<>();
            for (Long id : question.sourceIds()) {
                Subtitle subtitle = byId.get(id);
                if (subtitle != null) evidence.add(subtitle);
            }
            if (evidence.size() != question.sourceIds().size()
                    || !hashSources(evidence).equals(question.sourceHash())) continue;
            if (!validStoredQuestion(question, evidence)) continue;
            if (!isDuplicate(question, unique, List.of())) unique.add(question);
        }
        return unique;
    }

    @Scheduled(initialDelay = 7_000, fixedDelay = 5_000)
    public void dispatch() {
        try {
            bank.recoverExpired();
            while (activeJobs.get() < 2) {
                ShadowingQuestionBankRepository.PreparationJob job = bank.claim();
                if (job == null) return;
                activeJobs.incrementAndGet();
                jobs.execute(() -> {
                    try { process(job); }
                    finally { activeJobs.decrementAndGet(); }
                });
            }
        } catch (Exception exception) {
            log.warn("Shadowing question dispatcher failed: {}", exception.getClass().getSimpleName());
        }
    }

    void process(ShadowingQuestionBankRepository.PreparationJob job) {
        long started = System.nanoTime();
        int saved = 0;
        try {
            List<Subtitle> all = source(job.videoId());
            if (all.isEmpty()) {
                bank.finish(job.videoId(), "FAILED", "Video không còn phụ đề để tạo câu hỏi.");
                return;
            }
            Map<Long, Integer> originalPositions = new HashMap<>();
            for (int index = 0; index < all.size(); index++) {
                originalPositions.put(all.get(index).getId(), index);
            }
            List<ShadowingQuestionBankRepository.RejectedQuestion> rejected = bank.rejected(job.videoId());
            List<PreparedQuestion> accepted = new ArrayList<>(validQuestions(job.videoId(), all, true));
            int initialDesired = missingCounts(job, accepted).values().stream()
                    .mapToInt(Integer::intValue).sum();
            List<List<Subtitle>> chunks = evidenceWindows(all,
                    Math.min(MAX_SUBTITLES, Math.max(36, initialDesired * 4)));
            List<String> failedCandidates = new ArrayList<>();
            int issueCount = 0;
            int failedBatches = 0;
            for (int attempt = 1; attempt <= 2; attempt++) {
                Map<String, Integer> needed = missingCounts(job, accepted);
                int desired = needed.values().stream().mapToInt(Integer::intValue).sum();
                if (desired == 0) break;
                List<String> pairs = missingPairs(job, accepted);
                Map<String, Integer> perBatch = new LinkedHashMap<>();
                needed.forEach((kind, count) -> {
                    if (count > 0) perBatch.put(kind,
                            Math.min(6, (int) Math.ceil((double) count / chunks.size()) + 1));
                });
                int maxPerBatch = Math.min(7, (int) Math.ceil((double) desired / chunks.size()) + 1);
                List<CompletableFuture<List<ShadowingQuestionAiClient.AiQuestion>>> futures = new ArrayList<>();
                for (List<Subtitle> chunk : chunks) {
                    Set<Long> ids = new HashSet<>(chunk.stream().map(Subtitle::getId).toList());
                    List<String> avoid = new ArrayList<>(rejected.stream()
                            .filter(question -> ids.contains(question.subtitleId()) && relevantRejected(question))
                            .limit(8).map(question -> "REJECTED " + question.kind() + ":" + question.prompt()
                                    + "→" + question.answer()).toList());
                    accepted.stream().filter(question -> ids.contains(question.subtitleId()))
                            .limit(8).forEach(question -> avoid.add("EXISTING " + question.kind() + ":"
                                    + question.prompt() + "→" + question.answer()));
                    failedCandidates.stream().filter(entry -> avoid.size() < 20)
                            .forEach(avoid::add);
                    futures.add(CompletableFuture.supplyAsync(() -> ai.generate(chunk,
                            job.levels(), new ArrayList<>(perBatch.keySet()), pairs, avoid,
                            perBatch, maxPerBatch), batches));
                }
                for (int index = 0; index < chunks.size(); index++) {
                    List<Subtitle> chunk = chunks.get(index);
                    Map<Long, Subtitle> byId = new HashMap<>();
                    chunk.forEach(subtitle -> byId.put(subtitle.getId(), subtitle));
                    List<ShadowingQuestionAiClient.AiQuestion> generated = futures.get(index).join();
                    if (generated == null) {
                        failedBatches++;
                        if (issueCount++ < 40) bank.recordGenerationIssue(job.videoId(), null, null,
                                null, null, List.of(), "AI không trả về dữ liệu hợp lệ cho đoạn phụ đề này.",
                                chunk.get(0).getStartTime(), attempt);
                    } else {
                        if (generated.isEmpty() && issueCount++ < 40)
                            bank.recordGenerationIssue(job.videoId(), null, null, null, null, List.of(),
                                    "AI chưa tìm được câu hỏi có dẫn chứng trong đoạn này.",
                                    chunk.get(0).getStartTime(), attempt);
                        for (ShadowingQuestionAiClient.AiQuestion candidate : generated) {
                            if (job.targets().containsKey(candidate.kind())
                                    && missingCounts(job, accepted).getOrDefault(candidate.kind(), 0) == 0) continue;
                            PreparedQuestion question = validated(job.videoId(), candidate, byId,
                                    originalPositions, job.levels(), job.kinds(), pairs);
                            String reason = question == null
                                    ? invalidReason(candidate, byId, originalPositions, job.levels(),
                                            job.kinds(), pairs)
                                    : isDuplicate(question, accepted, rejected)
                                    ? "Trùng ý, đáp án hoặc đoạn dẫn chứng với câu đã có." : null;
                            if (reason != null) {
                                failedCandidates.add("INVALID " + candidate.kind() + ":"
                                        + candidate.question() + "→" + candidate.answer()
                                        + " (" + reason + ")");
                                if (issueCount++ < 40) bank.recordGenerationIssue(job.videoId(),
                                        candidate.kind(), candidate.level(), candidate.question(),
                                        candidate.answer(), candidate.choices(), reason, candidate.subtitleIds().stream()
                                                .map(byId::get).filter(java.util.Objects::nonNull)
                                                .map(Subtitle::getStartTime).findFirst().orElse(null), attempt);
                            } else if (bank.save(question)) {
                                accepted.add(question);
                                saved++;
                            } else if (issueCount++ < 40) {
                                bank.recordGenerationIssue(job.videoId(), candidate.kind(),
                                        candidate.level(), candidate.question(), candidate.answer(),
                                        candidate.choices(), "Câu này đã được lưu ở lượt tạo trước.",
                                        question.startTime(), attempt);
                            }
                        }
                    }
                    bank.progress(job.videoId(), (attempt - 1) * 45
                            + (index + 1) * 45 / Math.max(1, chunks.size()));
                }
            }
            Map<String, Integer> stillMissing = missingCounts(job,
                    validQuestions(job.videoId(), all, true));
            int missingTotal = stillMissing.values().stream().mapToInt(Integer::intValue).sum();
            for (Map.Entry<String, Integer> entry : stillMissing.entrySet()) {
                if (entry.getValue() > 0 && issueCount++ < 40)
                    bank.recordGenerationIssue(job.videoId(), entry.getKey(), null, null, null, List.of(),
                            "Sau hai lượt vẫn thiếu " + entry.getValue()
                            + " câu khác nhau có dẫn chứng. AI có thể đã trả ít câu hoặc các câu bị loại như trên.",
                            null, 2);
            }
            bank.finish(job.videoId(), missingTotal == 0 ? "READY" : saved > 0 ? "PARTIAL" : "FAILED",
                    missingTotal == 0 ? "Đã tạo đủ câu hỏi để xem lại."
                            : "Còn thiếu " + missingTotal + " câu sau khi đã thử tạo bù. "
                            + (failedBatches > 0 ? "Một số lần gọi AI không có kết quả. " : "")
                            + "Xem dạng còn thiếu và lý do loại bên dưới.");
            log.info("Shadowing questions prepared: videoId={}, saved={}, durationMs={}",
                    job.videoId(), saved, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        } catch (Exception exception) {
            log.warn("Shadowing question preparation failed for video {}: {}",
                    job.videoId(), exception.getClass().getSimpleName());
            bank.finish(job.videoId(), saved > 0 ? "PARTIAL" : "FAILED",
                    saved > 0 ? "Một số câu đã lưu; thử tạo lại phần còn thiếu."
                            : "Không chuẩn bị được bài tập. Hãy thử lại.");
        }
    }

    PreparedQuestion validated(long videoId, ShadowingQuestionAiClient.AiQuestion aiQuestion,
                                       Map<Long, Subtitle> byId, Map<Long, Integer> originalPositions,
                                       List<String> levels,
                                       List<String> kinds, List<String> pairs) {
        if (!levels.contains(aiQuestion.level()) || !kinds.contains(aiQuestion.kind())
                || !pairs.contains(aiQuestion.level() + ":" + aiQuestion.kind())
                || aiQuestion.subtitleIds().isEmpty() || aiQuestion.subtitleIds().size() > 3
                || new HashSet<>(aiQuestion.subtitleIds()).size() != aiQuestion.subtitleIds().size()) return null;
        List<Subtitle> evidence = new ArrayList<>();
        for (Long id : aiQuestion.subtitleIds()) {
            Subtitle subtitle = byId.get(id);
            if (subtitle == null) return null;
            evidence.add(subtitle);
        }
        evidence.sort(Comparator.comparingDouble(Subtitle::getStartTime));
        if (!evidence.stream().map(Subtitle::getId).toList().equals(aiQuestion.subtitleIds())) return null;
        if (evidence.size() > 1) {
            Integer firstIndex = originalPositions.get(evidence.get(0).getId());
            if (firstIndex == null) return null;
            for (int index = 1; index < evidence.size(); index++) {
                if (!Integer.valueOf(firstIndex + index)
                        .equals(originalPositions.get(evidence.get(index).getId()))) return null;
            }
        }
        Subtitle first = evidence.get(0);
        Subtitle last = evidence.get(evidence.size() - 1);
        String answer = aiQuestion.answer().strip();
        String prompt;
        List<String> choices;
        String explanation;
        int occurrence = 1;
        if ("GAP".equals(aiQuestion.kind())) {
            if (evidence.size() != 1 || answer.isBlank() || answer.length() > 500) return null;
            String sentence = first.getText();
            int chars = sentence.codePointCount(0, sentence.length());
            int gapChars = answer.codePointCount(0, answer.length());
            if (chars < 5 || !sentence.contains(answer)
                    || answer.codePoints().noneMatch(Character::isLetter)) return null;
            if ("EASY".equals(aiQuestion.level())
                    && (gapChars >= chars || gapChars > Math.max(4, (int) Math.ceil(chars * .45))))
                return null;
            if ("MEDIUM".equals(aiQuestion.level())
                    && (gapChars < 5 || gapChars >= chars || gapChars < chars * .3
                    || gapChars > chars * .65)) return null;
            if ("HARD".equals(aiQuestion.level())
                    && (chars < 12 || chars > 90 || last.getEndTime() - first.getStartTime() > 18
                    || !sentence.strip().equals(answer))) return null;
            if ("HARD".equals(aiQuestion.level())) {
                prompt = "音声の一文を日本語で書いてください。";
            } else {
                int start = sentence.indexOf(answer);
                String visible = sentence.substring(0, start) + sentence.substring(start + answer.length());
                if (japaneseCount(visible) < 4) return null;
                prompt = "音声を聞いて、空欄に入る言葉は何ですか。\n"
                        + sentence.substring(0, start)
                        + "＿".repeat(Math.max(3, Math.min(12, gapChars)))
                        + sentence.substring(start + answer.length());
            }
            choices = List.of();
            explanation = "Nghe lại đoạn này và đối chiếu với phụ đề gốc.";
        } else {
            if (!COMPREHENSION_KINDS.contains(aiQuestion.kind()) || answer.isBlank()
                    || aiQuestion.question().isBlank() || aiQuestion.question().contains(answer)
                    || aiQuestion.choices().size() != 4
                    || new HashSet<>(aiQuestion.choices()).size() != 4
                    || aiQuestion.choices().stream().filter(answer::equals).count() != 1
                    || japaneseCount(aiQuestion.question()) < 4 || japaneseCount(answer) < 1
                    || aiQuestion.choices().stream().anyMatch(choice -> japaneseCount(choice) < 1)
                    || !isJapaneseQuestion(aiQuestion.question())
                    || !categoryEvidenceSupported(aiQuestion.kind(), answer, evidence)
                    || evidence.stream().noneMatch(item -> item.getText().contains(answer))
                    || evidence.stream().anyMatch(item -> normalize(item.getText())
                    .equals(normalize(aiQuestion.question())))) return null;
            prompt = aiQuestion.question().strip();
            choices = aiQuestion.choices();
            explanation = aiQuestion.explanation().strip();
        }
        String sourceHash = hashSources(evidence);
        String contentHash = hash(QUESTION_VERSION + "|" + aiQuestion.kind() + "|" + aiQuestion.level()
                + "|" + prompt + "|" + answer + "|" + sourceHash);
        return new PreparedQuestion(null, videoId, first.getId(),
                evidence.stream().map(Subtitle::getId).toList(), aiQuestion.kind(), aiQuestion.level(),
                prompt, answer, choices, explanation, first.getStartTime(), last.getEndTime(),
                sourceHash, occurrence, contentHash, "DRAFT");
    }

    private Video requireOwner(long userId, long videoId) {
        Video video = videos.findById(videoId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy video"));
        if (video.getOwnerId() == null || video.getOwnerId() != userId) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền sửa video này");
        }
        return video;
    }

    private Video requireCanPrepare(long userId, long videoId) {
        Video video = videos.findById(videoId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy video"));
        if (video.getOwnerId() != null && video.getOwnerId() == userId) return video;
        if (video.getStatus() == VideoStatus.READY
                && video.getVisibility() == com.jlas.shadowing.model.VideoVisibility.PUBLIC) return video;
        throw new ApiException(ErrorCode.FORBIDDEN, "Bạn không có quyền đóng góp cho video này");
    }

    /** Same lines practice sessions can use: skips blank text and lines blocked by subtitle quality review. */
    private List<Subtitle> source(long videoId) {
        Set<Long> blocked = qualityReview.blockedSubtitleIds(videoId);
        return subtitles.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId).stream()
                .filter(subtitle -> subtitle.getText() != null && !subtitle.getText().isBlank())
                .filter(subtitle -> !blocked.contains(subtitle.getId())).toList();
    }

    private static List<Subtitle> spread(List<Subtitle> all, int limit) {
        if (all.size() <= limit) return all;
        List<Subtitle> chosen = new ArrayList<>();
        for (int index = 0; index < limit; index++) {
            chosen.add(all.get((int) Math.round(index * (all.size() - 1.0) / (limit - 1.0))));
        }
        return chosen;
    }

    private static List<List<Subtitle>> evidenceWindows(List<Subtitle> all, int target) {
        if (all.isEmpty()) return List.of();
        int windows = Math.min(3, Math.max(1, (int) Math.ceil((double) all.size() / BATCH_SIZE)));
        int size = Math.min(BATCH_SIZE, Math.min(all.size() / windows,
                (int) Math.ceil((double) target / windows)));
        List<List<Subtitle>> result = new ArrayList<>();
        for (int index = 0; index < windows; index++) {
            int start = windows == 1 ? 0
                    : (int) Math.round(index * (all.size() - size) / (double) (windows - 1));
            result.add(all.subList(start, start + size));
        }
        return result;
    }

    private static int japaneseCount(String value) {
        if (value == null) return 0;
        return (int) value.codePoints().filter(codePoint -> {
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            return script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA;
        }).count();
    }

    private static boolean isJapaneseQuestion(String text) {
        String value = text == null ? "" : text.strip();
        return value.endsWith("か。") || value.endsWith("か？")
                || value.endsWith("か?") || value.endsWith("？") || value.endsWith("?");
    }

    private static boolean categoryEvidenceSupported(String kind, String answer, List<Subtitle> evidence) {
        String complete = answer.replaceAll("[。！!？?\\s]+$", "");
        if (complete.endsWith("し") || complete.endsWith("けど")
                || complete.endsWith("けれど")) return false;
        if ("ACTION_SEQUENCE".equals(kind)) {
            String source = evidence.stream().map(Subtitle::getText)
                    .reduce("", (left, right) -> left + " " + right);
            for (String cue : List.of("まず", "最初", "次に", "その後", "それから", "続いて",
                    "先に", "後で", "あとで", "前に", "てから", "た後")) {
                if (source.contains(cue)) return true;
            }
            return source.split("て", -1).length >= 3
                    && (source.contains("ました") || source.contains("した"));
        }
        if ("REASON_PURPOSE".equals(kind)) {
            for (String cue : List.of("から", "ので", "ため", "せい", "おかげ", "理由")) {
                if (answer.contains(cue)) return true;
            }
            for (Subtitle subtitle : evidence) {
                int start = subtitle.getText().indexOf(answer);
                if (start < 0) continue;
                String leadIn = subtitle.getText().substring(0, start);
                if (leadIn.contains("というと") || leadIn.contains("理由は")
                        || leadIn.contains("原因は") || leadIn.contains("なぜなら")) return true;
            }
            return false;
        }
        return true;
    }

    static boolean validStoredQuestion(PreparedQuestion question, List<Subtitle> evidence) {
        if ("GAP".equals(question.kind())) {
            if (evidence.size() != 1 || !evidence.get(0).getText().contains(question.answer())) return false;
            if ("HARD".equals(question.level())) return japaneseCount(question.prompt()) >= 4
                    && !question.prompt().contains("＿") && !question.prompt().contains("_");
            String line = question.prompt().substring(question.prompt().lastIndexOf('\n') + 1);
            String visible = line.replaceAll("[＿_]+", "");
            return japaneseCount(visible) >= 4 && line.matches(".*[＿_]{3,}.*");
        }
        return COMPREHENSION_KINDS.contains(question.kind())
                && japaneseCount(question.prompt()) >= 4
                && isJapaneseQuestion(question.prompt())
                && japaneseCount(question.answer()) >= 1
                && evidence.stream().anyMatch(item -> item.getText().contains(question.answer()))
                && categoryEvidenceSupported(question.kind(), question.answer(), evidence)
                && question.choices().size() == 4
                && question.choices().stream().allMatch(choice -> japaneseCount(choice) >= 1)
                && question.choices().stream().filter(question.answer()::equals).count() == 1;
    }

    private static Map<String, Integer> counts(List<PreparedQuestion> questions) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PreparedQuestion question : questions) {
            counts.merge(question.level() + ":" + question.kind(), 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> missingPairs(ShadowingQuestionBankRepository.PreparationJob job,
                                             List<PreparedQuestion> questions) {
        List<String> missing = new ArrayList<>();
        for (String kind : job.kinds()) {
            long present = questions.stream().filter(question -> kind.equals(question.kind())).count();
            if (present < job.targets().getOrDefault(kind, 5)) {
                for (String level : job.levels()) missing.add(level + ":" + kind);
            }
        }
        return missing;
    }

    private static Map<String, Integer> missingCounts(
            ShadowingQuestionBankRepository.PreparationJob job, List<PreparedQuestion> questions) {
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (String kind : job.kinds()) {
            long present = questions.stream().filter(question -> kind.equals(question.kind())).count();
            missing.put(kind, Math.max(0, job.targets().getOrDefault(kind, 5) - (int) present));
        }
        return missing;
    }

    private static String invalidReason(ShadowingQuestionAiClient.AiQuestion candidate,
                                        Map<Long, Subtitle> byId, Map<Long, Integer> positions,
                                        List<String> levels, List<String> kinds, List<String> pairs) {
        if (!levels.contains(candidate.level()) || !kinds.contains(candidate.kind())
                || !pairs.contains(candidate.level() + ":" + candidate.kind()))
            return "Sai mức nghe hoặc dạng câu hỏi đã chọn.";
        List<Long> ids = candidate.subtitleIds();
        if (ids.isEmpty() || ids.size() > 3 || new HashSet<>(ids).size() != ids.size()
                || ids.stream().anyMatch(id -> !byId.containsKey(id)))
            return "Dẫn chứng không nằm trong đoạn phụ đề được cung cấp.";
        for (int index = 1; index < ids.size(); index++) {
            Integer previous = positions.get(ids.get(index - 1));
            Integer next = positions.get(ids.get(index));
            if (previous == null || next == null || next != previous + 1)
                return "Các dòng phụ đề dẫn chứng không liền nhau hoặc sai thứ tự.";
        }
        List<Subtitle> evidence = ids.stream().map(byId::get).toList();
        String answer = candidate.answer().strip();
        if ("GAP".equals(candidate.kind())) {
            if (ids.size() != 1 || answer.isBlank() || answer.length() > 500)
                return "Câu điền cần đúng một dòng phụ đề và đáp án có độ dài hợp lệ.";
            String line = evidence.get(0).getText();
            int chars = line.codePointCount(0, line.length());
            int gapChars = answer.codePointCount(0, answer.length());
            if (!line.contains(answer) || answer.codePoints().noneMatch(Character::isLetter))
                return "Đáp án điền không xuất hiện nguyên văn trong phụ đề.";
            if ("EASY".equals(candidate.level())
                    && (gapChars >= chars || gapChars > Math.max(4, (int) Math.ceil(chars * .45))))
                return "Mức Dễ che quá nhiều nội dung của câu.";
            if ("MEDIUM".equals(candidate.level())
                    && (gapChars < 5 || gapChars >= chars || gapChars < chars * .3
                    || gapChars > chars * .65))
                return "Mức Vừa cần một cụm đủ dài nhưng vẫn để lại ngữ cảnh nghe.";
            if ("HARD".equals(candidate.level()) && !line.strip().equals(answer))
                return "Mức Khó cần chép chính xác cả câu phụ đề.";
            return "Câu phụ đề quá ngắn, quá dài hoặc thiếu ngữ cảnh để tạo chỗ trống.";
        }
        if (!COMPREHENSION_KINDS.contains(candidate.kind()) || answer.isBlank()
                || candidate.question().isBlank()) return "Thiếu câu hỏi hoặc đáp án nghe hiểu.";
        if (japaneseCount(candidate.question()) < 4 || !isJapaneseQuestion(candidate.question()))
            return "Câu hỏi cần viết bằng tiếng Nhật và kết thúc bằng dấu hỏi.";
        if (candidate.question().contains(answer)) return "Câu hỏi đã làm lộ đáp án.";
        if (candidate.choices().size() != 4 || new HashSet<>(candidate.choices()).size() != 4
                || candidate.choices().stream().filter(answer::equals).count() != 1)
            return "Cần đúng bốn lựa chọn khác nhau, trong đó chỉ một lựa chọn khớp đáp án.";
        if (japaneseCount(answer) < 1
                || candidate.choices().stream().anyMatch(choice -> japaneseCount(choice) < 1))
            return "Đáp án và bốn lựa chọn phải bằng tiếng Nhật.";
        if (evidence.stream().noneMatch(item -> item.getText().contains(answer)))
            return "Đáp án không xuất hiện nguyên văn trong phụ đề dẫn chứng.";
        if (!categoryEvidenceSupported(candidate.kind(), answer, evidence))
            return "Dẫn chứng chưa chứng minh đúng dạng câu hỏi này, hoặc đáp án bị cụt ý.";
        if (evidence.stream().anyMatch(item -> normalize(item.getText())
                .equals(normalize(candidate.question()))))
            return "Câu hỏi chép lại nguyên văn phụ đề thay vì hỏi về nội dung.";
        return "Câu chưa qua kiểm tra độ dài hoặc ngữ cảnh của phụ đề.";
    }

    private static boolean isDuplicate(PreparedQuestion candidate, List<PreparedQuestion> accepted,
                                       List<ShadowingQuestionBankRepository.RejectedQuestion> rejected) {
        String answer = normalize(candidate.answer());
        for (PreparedQuestion other : accepted) {
            if (!other.kind().equals(candidate.kind())) continue;
            if (other.sourceIds().stream().anyMatch(candidate.sourceIds()::contains)
                    || nearDuplicate(normalize(other.answer()), answer)) return true;
        }
        for (ShadowingQuestionBankRepository.RejectedQuestion other : rejected) {
            if (other.kind().equals(candidate.kind()) && relevantRejected(other)
                    && normalize(other.prompt()).equals(normalize(candidate.prompt()))
                    && normalize(other.answer()).equals(answer)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\p{S}]+", "").strip();
    }

    private static boolean nearDuplicate(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        if (left.equals(right)) return true;
        if (Math.min(left.length(), right.length()) < 6) return false;
        return left.contains(right) || right.contains(left);
    }

    private static boolean relevantRejected(ShadowingQuestionBankRepository.RejectedQuestion question) {
        return japaneseCount(question.prompt()) >= 4
                && ("GAP".equals(question.kind()) || isJapaneseQuestion(question.prompt()));
    }

    private static Map<String, Integer> capacities(ShadowingQuestionBankRepository.PreparationJob job,
                                                    List<Subtitle> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int eligible = (int) source.stream().filter(subtitle -> subtitle.getText() != null
                && subtitle.getText().codePointCount(0, subtitle.getText().length()) >= 5).count();
        result.put("GAP", Math.min(20, eligible));
        for (String kind : COMPREHENSION_KINDS) {
            int estimated = job == null ? 0 : job.capacities().getOrDefault(kind, 0);
            int typeLimit = "MAIN_IDEA".equals(kind) ? 2 : 20;
            result.put(kind, Math.min(Math.min(typeLimit, eligible), Math.max(0, estimated)));
        }
        return result;
    }

    private static Map<String, Integer> remaining(ShadowingQuestionBankRepository.PreparationJob job,
                                                   List<Subtitle> source, List<PreparedQuestion> questions) {
        Map<String, Integer> result = capacities(job, source);
        for (String kind : KINDS) {
            long used = questions.stream().filter(question -> kind.equals(question.kind())).count();
            result.put(kind, Math.max(0, result.getOrDefault(kind, 0) - (int) used));
        }
        return result;
    }

    private static String hashSources(List<Subtitle> sources) {
        StringBuilder data = new StringBuilder(SOURCE_VERSION);
        for (Subtitle subtitle : sources) {
            data.append('\u001f').append(subtitle.getId()).append('\u001f')
                    .append(subtitle.getText()).append('\u001f').append(subtitle.getFurigana())
                    .append('\u001f').append(subtitle.getTranslation()).append('\u001f')
                    .append(subtitle.getStartTime()).append('\u001f').append(subtitle.getEndTime());
        }
        return hash(data.toString());
    }

    private static String hash(String data) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Không tạo được hash câu hỏi", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        jobs.shutdown();
        batches.shutdown();
    }
}
