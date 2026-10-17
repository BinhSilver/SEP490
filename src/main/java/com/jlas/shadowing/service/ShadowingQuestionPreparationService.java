package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.dto.ManualQuestionRequest;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoStatus;
import com.jlas.shadowing.model.practice.PreparedQuestion;
import com.jlas.shadowing.model.practice.QuestionEvidencePlan;
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
    private static final String QUESTION_VERSION = "listening-v3-evidence";
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
        String effectiveStatus = job == null || "IDLE".equals(job.status())
                ? draftCount > 0 ? "REVIEW" : questions.isEmpty() ? "IDLE" : "READY"
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
        status.put("levelOutdated", job != null && !analysisCurrent(job, source));
        status.put("capacities", capacities(job, source));
        status.put("remaining", remaining(job, source, allQuestions));
        status.put("evidencePlans", analysisCurrent(job, source)
                ? availablePlans(bank.evidencePlans(job), allQuestions) : List.of());
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
        status.put("manualSources", owner ? source.stream().map(line -> Map.of(
                "id", line.getId(), "text", QuestionTranscript.spoken(line.getText()),
                "startTime", line.getStartTime(), "endTime", line.getEndTime(),
                "hash", hashSources(List.of(line)))).toList() : List.of());
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
        String currentStatus = job == null || "IDLE".equals(job.status())
                ? allPrepared.stream().anyMatch(q -> "DRAFT".equals(q.status())) ? "REVIEW"
                : valid.isEmpty() ? "IDLE" : "READY" : job.status();
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
        String hash = hashSources(source);
        ShadowingQuestionBankRepository.PreparationJob job = bank.findJob(videoId);
        if (analysisCurrent(job, source)) {
            return suggestionResponse(job, source);
        }
        if (job != null && Set.of("RUNNING", "QUEUED").contains(job.status()))
            throw new ApiException(ErrorCode.CONFLICT, "Hãy đợi lượt tạo hiện tại hoàn tất trước khi phân tích lại.");
        if (!ai.available()) throw new ApiException(ErrorCode.BAD_REQUEST, "Chưa cấu hình AI tạo câu hỏi");
        Map<Long, Integer> positions = positions(source);
        List<QuestionEvidencePlan> plans = new ArrayList<>();
        Map<String, Integer> levels = new LinkedHashMap<>();
        List<List<Subtitle>> windows = QuestionTranscript.windows(source);
        // Work in bounded waves; do not enqueue every passage of a long video at once.
        for (int start = 0; start < windows.size(); start += 3) {
            List<List<Subtitle>> wave = windows.subList(start, Math.min(start + 3, windows.size()));
            List<CompletableFuture<ShadowingQuestionAiClient.Analysis>> futures = wave.stream()
                    .map(window -> CompletableFuture.supplyAsync(() -> ai.analyze(window), batches)).toList();
            for (int index = 0; index < wave.size(); index++) {
                ShadowingQuestionAiClient.Analysis result = futures.get(index).join();
                if (result == null || result.jlptLevel() == null) {
                    throw new ApiException(ErrorCode.BAD_REQUEST,
                            "Chưa phân tích đủ các đoạn phụ đề. Vui lòng thử lại; ước tính cũ chưa bị thay thế.");
                }
                levels.merge(result.jlptLevel(), 1, Integer::sum);
                Set<Long> supplied = new HashSet<>(wave.get(index).stream().map(Subtitle::getId).toList());
                for (QuestionEvidencePlan proposed : result.plans()) {
                    QuestionEvidencePlan plan = validatePlan(proposed, source, positions, supplied);
                    if (plan != null && plans.stream().noneMatch(other -> QuestionTranscript.sameFact(other, plan)))
                        plans.add(plan);
                }
            }
        }
        for (Subtitle line : source) {
            String text = QuestionTranscript.spoken(line.getText());
            if (text.length() < 5 || japaneseCount(text) < 4) continue;
            QuestionEvidencePlan gap = new QuestionEvidencePlan("GAP", List.of(line.getId()),
                    "Nghe và điền nội dung của câu phụ đề", text,
                    QuestionTranscript.neighbors(List.of(line.getId()), source), "");
            if (plans.stream().noneMatch(other -> QuestionTranscript.sameFact(other, gap))) plans.add(gap);
        }
        // Never publish analysis of a transcript that changed while the AI was reading it.
        if (!hash.equals(hashSources(source(videoId))))
            throw new ApiException(ErrorCode.CONFLICT, "Phụ đề đã thay đổi trong lúc phân tích. Hãy thử lại.");
        String level = levels.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        Map<String, Integer> capacities = planCounts(plans);
        List<String> kinds = COMPREHENSION_KINDS.stream().sorted()
                .filter(kind -> capacities.getOrDefault(kind, 0) > 0).toList();
        bank.saveSuggestions(videoId, String.join(",", kinds), hash, level,
                ShadowingQuestionBankRepository.PreparationJob.serialize(capacities),
                QuestionTranscript.ANALYSIS_VERSION, plans);
        return suggestionResponse(bank.findJob(videoId), source);
    }

    private Map<String, Object> suggestionResponse(ShadowingQuestionBankRepository.PreparationJob job,
                                                    List<Subtitle> source) {
        return Map.of("suggestions", job.suggestionsCsv().isBlank() ? List.of()
                        : List.of(job.suggestionsCsv().split(",")),
                "suggestedJlptLevel", job.suggestedJlptLevel(), "capacities", capacities(job, source),
                "evidencePlans", bank.evidencePlans(job));
    }

    static QuestionEvidencePlan validatePlan(QuestionEvidencePlan plan, List<Subtitle> source,
                                             Map<Long, Integer> positions, Set<Long> supplied) {
        if (plan == null || !COMPREHENSION_KINDS.contains(plan.kind()) || plan.focus() == null
                || plan.focus().isBlank() || plan.answerQuote() == null || plan.answerQuote().isBlank()
                || !QuestionTranscript.contiguous(plan.sourceIds(), positions)
                || !supplied.containsAll(plan.sourceIds())) return null;
        List<Subtitle> evidence = QuestionTranscript.evidence(plan, source);
        if (evidence.stream().noneMatch(s -> QuestionTranscript.spoken(s.getText()).contains(plan.answerQuote()))
                || !categoryEvidenceSupported(plan.kind(), plan.answerQuote(), evidence)) return null;
        return new QuestionEvidencePlan(plan.kind(), List.copyOf(plan.sourceIds()), plan.focus(),
                plan.answerQuote(), QuestionTranscript.neighbors(plan.sourceIds(), source), plan.contextSummary());
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
        // The same lock as manual authoring: a queued job must see the newly committed owner questions.
        bank.lockVideo(videoId);
        List<Subtitle> source = source(videoId);
        if (source.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Video chưa có phụ đề");
        ShadowingQuestionBankRepository.PreparationJob existing = bank.findJob(videoId);
        if (existing != null && ("QUEUED".equals(existing.status()) || "RUNNING".equals(existing.status()))) {
            throw new ApiException(ErrorCode.CONFLICT, "Bài tập đang được chuẩn bị");
        }
        if (!analysisCurrent(existing, source)) {
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

    @Transactional
    public PreparedQuestion createManual(long userId, long videoId, ManualQuestionRequest request) {
        Video video = requireOwner(userId, videoId);
        if (video.getStatus() != VideoStatus.READY && video.getStatus() != VideoStatus.DRAFT)
            throw new ApiException(ErrorCode.CONFLICT, "Hãy đợi xử lý phụ đề hoàn tất trước khi nhập câu hỏi.");
        if (request == null || request.kind() == null || !KINDS.contains(request.kind())
                || request.level() == null || !LEVELS.contains(request.level())
                || request.sourceIds() == null || request.sourceHashes() == null
                || request.sourceIds().isEmpty() || request.sourceIds().size() > 6
                || request.sourceIds().size() != request.sourceHashes().size()
                || request.sourceIds().stream().anyMatch(java.util.Objects::isNull)
                || request.answer() == null || request.answer().isBlank() || request.answer().length() > 500
                || request.explanation() == null || request.explanation().isBlank() || request.explanation().length() > 4000
                || request.prompt() != null && request.prompt().length() > 2000
                || request.choices() == null || request.choices().size() > 4
                || request.choices().stream().anyMatch(c -> c == null || c.isBlank() || c.length() > 500))
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy nhập đầy đủ dạng câu, mức nghe, dẫn chứng, đáp án và giải thích.");
        bank.lockVideo(videoId);
        var job = bank.findJob(videoId);
        if (job != null && Set.of("RUNNING", "QUEUED").contains(job.status()))
            throw new ApiException(ErrorCode.CONFLICT, "Hãy đợi lượt tạo câu hỏi hiện tại hoàn tất rồi lưu câu nhập tay.");
        List<Subtitle> source = source(videoId);
        Map<Long, Subtitle> byId = new HashMap<>();
        source.forEach(line -> byId.put(line.getId(), line));
        for (int i = 0; i < request.sourceIds().size(); i++) {
            Subtitle line = byId.get(request.sourceIds().get(i));
            if (line == null || !hashSources(List.of(line)).equals(request.sourceHashes().get(i)))
                throw new ApiException(ErrorCode.CONFLICT,
                        "Phụ đề dẫn chứng đã thay đổi hoặc không còn dùng được. Hãy tải lại trang và chọn lại.");
        }
        if ("GAP".equals(request.kind()) && !request.choices().isEmpty())
            throw new ApiException(ErrorCode.BAD_REQUEST, "Câu điền nội dung không dùng lựa chọn trắc nghiệm.");
        var candidate = new ShadowingQuestionAiClient.AiQuestion(request.sourceIds(), request.kind(), request.level(),
                request.prompt() == null ? "" : request.prompt().strip(), request.answer().strip(),
                request.choices().stream().map(String::strip).toList(), request.explanation().strip());
        List<String> levels = List.of(request.level()), kinds = List.of(request.kind());
        List<String> pairs = List.of(request.level() + ":" + request.kind());
        PreparedQuestion validated = validated(videoId, candidate, byId, positions(source), levels, kinds, pairs);
        if (validated == null)
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    invalidReason(candidate, byId, positions(source), levels, kinds, pairs)
                            .replace("AI ", "Bạn ").replace("AI", "Câu hỏi"));
        if (isDuplicate(validated, validQuestions(videoId, source, true), List.of()))
            throw new ApiException(ErrorCode.CONFLICT,
                    "Đã có câu hỏi cùng dạng dùng dẫn chứng hoặc đáp án này. Hãy chọn nội dung khác.");
        PreparedQuestion question = new PreparedQuestion(null, videoId, validated.subtitleId(), validated.sourceIds(),
                validated.kind(), validated.level(), validated.prompt(), validated.answer(), validated.choices(),
                request.explanation().strip(), validated.startTime(), validated.endTime(), validated.sourceHash(),
                validated.occurrence(), validated.contentHash(), "DRAFT");
        if (!bank.save(question))
            throw new ApiException(ErrorCode.CONFLICT, "Câu hỏi này đã được lưu hoặc bị xóa trước đó. Hãy đổi nội dung.");
        return bank.findByContentHash(videoId, question.contentHash());
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
            if (!analysisCurrent(job, all)) {
                bank.finish(job.videoId(), "FAILED", "Phụ đề hoặc cách phân tích đã thay đổi. Hãy lấy lại gợi ý.");
                return;
            }
            Map<Long, Integer> originalPositions = positions(all);
            List<ShadowingQuestionBankRepository.RejectedQuestion> rejected = bank.rejected(job.videoId());
            List<PreparedQuestion> accepted = new ArrayList<>(validQuestions(job.videoId(), all, true));
            List<QuestionEvidencePlan> plans = bank.evidencePlans(job);
            Map<QuestionEvidencePlan, List<String>> repairs = new LinkedHashMap<>();
            int issueCount = 0, failedBatches = 0;
            for (int attempt = 1; attempt <= 2; attempt++) {
                Map<String, Integer> needed = missingCounts(job, accepted);
                if (needed.values().stream().mapToInt(Integer::intValue).sum() == 0) break;
                List<QuestionEvidencePlan> selected = new ArrayList<>();
                for (String kind : job.kinds()) {
                    availablePlans(plans, accepted).stream().filter(plan -> kind.equals(plan.kind()))
                            .sorted(Comparator.comparingInt(plan -> repairs.containsKey(plan) ? 0 : 1))
                            .limit(needed.getOrDefault(kind, 0)).forEach(selected::add);
                }
                List<String> pairs = missingPairs(job, accepted);
                for (int waveStart = 0; waveStart < selected.size(); waveStart += 3) {
                    List<QuestionEvidencePlan> wave = selected.subList(waveStart, Math.min(waveStart + 3, selected.size()));
                    List<CompletableFuture<List<ShadowingQuestionAiClient.AiQuestion>>> futures = new ArrayList<>();
                    for (QuestionEvidencePlan plan : wave) {
                        List<Subtitle> neighborhood = all.stream().filter(s -> plan.contextIds().contains(s.getId())).toList();
                        List<String> avoid = new ArrayList<>(repairs.getOrDefault(plan, List.of()));
                        accepted.stream().filter(q -> plan.kind().equals(q.kind())).limit(20)
                                .forEach(q -> avoid.add("EXISTING " + q.prompt() + "→" + q.answer()));
                        rejected.stream().filter(q -> plan.kind().equals(q.kind()) && relevantRejected(q)).limit(8)
                                .forEach(q -> avoid.add("REJECTED " + q.prompt() + "→" + q.answer()));
                        futures.add(CompletableFuture.supplyAsync(() -> ai.generate(neighborhood, job.levels(),
                                List.of(plan.kind()), pairs, avoid, Map.of(plan.kind(), 1), 1, plan), batches));
                    }
                    for (int index = 0; index < wave.size(); index++) {
                        QuestionEvidencePlan plan = wave.get(index);
                        List<Subtitle> evidence = QuestionTranscript.evidence(plan, all);
                        List<Subtitle> supplied = all.stream().filter(s -> plan.contextIds().contains(s.getId())).toList();
                        Map<Long, Subtitle> marked = new HashMap<>();
                        evidence.forEach(s -> marked.put(s.getId(), s));
                        List<ShadowingQuestionAiClient.AiQuestion> generated = futures.get(index).join();
                        if (!job.suggestionHash().equals(hashSources(source(job.videoId())))) {
                            bank.finish(job.videoId(), "PARTIAL", "Phụ đề đã thay đổi. Hãy phân tích lại trước khi tạo tiếp.");
                            return;
                        }
                        if (generated == null || generated.isEmpty()) {
                            failedBatches++;
                            String reason = generated == null ? "AI không trả về dữ liệu hợp lệ cho dẫn chứng đã chọn."
                                    : "AI chưa tạo được câu hỏi cho dẫn chứng đã chọn.";
                            repairs.put(plan, List.of("INVALID: " + reason + " Use exactly marked sourceIds " + plan.sourceIds()));
                            if (issueCount++ < 40) bank.recordGenerationIssue(job.videoId(), plan.kind(), null,
                                    null, plan.answerQuote(), List.of(), reason, evidence.get(0).getStartTime(), attempt,
                                    plan.sourceIds(), supplied.stream().map(Subtitle::getId).toList(), QuestionTranscript.diagnosticView(supplied, plan.sourceIds()));
                        } else {
                            // Each request has one evidence plan and may save at most one question.
                            ShadowingQuestionAiClient.AiQuestion candidate = generated.get(0);
                            String reason = !plan.kind().equals(candidate.kind())
                                    || !plan.sourceIds().equals(candidate.subtitleIds())
                                    ? "Câu hỏi không dùng đúng dạng và các dòng dẫn chứng đã đánh dấu."
                                    : !"GAP".equals(plan.kind()) && !plan.answerQuote().equals(candidate.answer().strip())
                                    ? "Đáp án không khớp nội dung đã chọn khi ước tính." : null;
                            PreparedQuestion question = reason == null ? validated(job.videoId(), candidate, marked,
                                    originalPositions, job.levels(), job.kinds(), pairs) : null;
                            if (reason == null && question == null) reason = invalidReason(candidate, marked,
                                    originalPositions, job.levels(), job.kinds(), pairs);
                            if (reason == null && isDuplicate(question, accepted, rejected))
                                reason = "Trùng ý, đáp án hoặc đoạn dẫn chứng với câu đã có.";
                            if (reason == null && !bank.save(question)) reason = "Câu này đã được lưu ở lượt tạo trước.";
                            if (reason != null) {
                                repairs.put(plan, List.of("INVALID sourceIds=" + candidate.subtitleIds()
                                        + ": " + candidate.question() + "→" + candidate.answer() + " choices="
                                        + candidate.choices() + " (" + reason + "). Repair THIS question using the same evidencePlan."));
                                if (issueCount++ < 40) bank.recordGenerationIssue(job.videoId(), candidate.kind(),
                                        candidate.level(), candidate.question(), candidate.answer(), candidate.choices(), reason,
                                        evidence.get(0).getStartTime(), attempt, candidate.subtitleIds(),
                                        supplied.stream().map(Subtitle::getId).toList(), QuestionTranscript.diagnosticView(supplied, plan.sourceIds()));
                            } else {
                                accepted.add(question);
                                saved++;
                            }
                        }
                        bank.progress(job.videoId(), (attempt - 1) * 45
                                + (waveStart + index + 1) * 45 / Math.max(1, selected.size()));
                    }
                }
            }
            Map<String, Integer> stillMissing = missingCounts(job, validQuestions(job.videoId(), all, true));
            int missingTotal = stillMissing.values().stream().mapToInt(Integer::intValue).sum();
            for (Map.Entry<String, Integer> entry : stillMissing.entrySet()) {
                if (entry.getValue() > 0 && issueCount++ < 40)
                    bank.recordGenerationIssue(job.videoId(), entry.getKey(), null, null, null, List.of(),
                            "Sau lượt tạo và sửa vẫn thiếu " + entry.getValue()
                                    + " câu đạt yêu cầu. Ước tính có dẫn chứng chưa bảo đảm AI tạo được câu hợp lệ.", null, 2);
            }
            bank.finish(job.videoId(), missingTotal == 0 ? "READY" : saved > 0 ? "PARTIAL" : "FAILED",
                    missingTotal == 0 ? "Đã tạo đủ câu hỏi để xem lại."
                            : "Còn thiếu " + missingTotal + " câu sau khi đã thử sửa. "
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
                || aiQuestion.subtitleIds().isEmpty() || aiQuestion.subtitleIds().size() > QuestionTranscript.MAX_EVIDENCE_LINES
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
            String sentence = QuestionTranscript.spoken(first.getText());
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
                    || !QuestionTranscript.sentenceKey(sentence).equals(QuestionTranscript.sentenceKey(answer)))) return null;
            if ("HARD".equals(aiQuestion.level())) {
                answer = sentence;
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
                    || japaneseCount(aiQuestion.question()) < 4 || !japaneseChoice(answer, evidence)
                    || aiQuestion.choices().stream().anyMatch(choice -> !japaneseChoice(choice, evidence))
                    || !isJapaneseQuestion(aiQuestion.question())
                    || !categoryEvidenceSupported(aiQuestion.kind(), answer, evidence)
                    || !answerInEvidence(answer, evidence)
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

    static boolean categoryEvidenceSupported(String kind, String answer, List<Subtitle> evidence) {
        String complete = answer.replaceAll("[。！!？?\\s]+$", "");
        if (complete.matches(".*(?:ですし|ますし|だし|けど|けれど(?:も)?)$")) return false;
        if ("ACTION_SEQUENCE".equals(kind)) {
            String source = evidence.stream().map(item -> QuestionTranscript.spoken(item.getText()))
                    .reduce("", (left, right) -> left + " " + right);
            // A sequencing word followed only by topic nouns (e.g. まず夏と冬) is not an action.
            boolean hasAction = source.matches("(?s).*(?:[いきぎしちにびみり]ます|[いきぎしちにびみり]ました"
                    + "|[べえけせてねめれ]ます|[べえけせてねめれ]ました|します|しました|して|行って|食べて|飲んで|帰って|取って|買って|見て|作って|た後|てから).*");
            if (!hasAction) return false;
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
            String joined = evidence.stream().map(item -> QuestionTranscript.spoken(item.getText()))
                    .collect(java.util.stream.Collectors.joining(" "));
            int start = joined.indexOf(answer);
            if (start >= 0) {
                String leadIn = joined.substring(0, start);
                if (leadIn.contains("というと") || leadIn.contains("理由は")
                        || leadIn.contains("原因は") || leadIn.contains("なぜなら")) return true;
                String following = joined.substring(start + answer.length());
                // The cause may be one line and its causal link the next: 東京より小さいです。そうだから…
                if (following.matches("^[。.!！?？\\s]*(?:そう)?(?:だから|なので|ですから|ので|ため).*")) return true;
            }
            return false;
        }
        return true;
    }

    private static boolean answerInEvidence(String answer, List<Subtitle> evidence) {
        return evidence.stream().anyMatch(item -> QuestionTranscript.spoken(item.getText()).contains(answer));
    }

    private static boolean japaneseChoice(String value, List<Subtitle> evidence) {
        if (japaneseCount(value) > 0) return true;
        // Allow single proper-name tokens in a passage that actually mentions Latin names, not English sentences.
        return value != null && value.matches("[A-Z][A-Za-z0-9+._-]{0,79}")
                && evidence.stream().anyMatch(item -> QuestionTranscript.spoken(item.getText())
                .matches("(?s).*[A-Z][A-Za-z0-9+._-]+.*"));
    }

    static boolean validStoredQuestion(PreparedQuestion question, List<Subtitle> evidence) {
        if ("GAP".equals(question.kind())) {
            if (evidence.size() != 1 || !answerInEvidence(question.answer(), evidence)) return false;
            if ("HARD".equals(question.level())) return japaneseCount(question.prompt()) >= 4
                    && !question.prompt().contains("＿") && !question.prompt().contains("_")
                    && QuestionTranscript.sentenceKey(evidence.get(0).getText())
                    .equals(QuestionTranscript.sentenceKey(question.answer()));
            String line = question.prompt().substring(question.prompt().lastIndexOf('\n') + 1);
            String visible = line.replaceAll("[＿_]+", "");
            return japaneseCount(visible) >= 4 && line.matches(".*[＿_]{3,}.*");
        }
        return COMPREHENSION_KINDS.contains(question.kind())
                && japaneseCount(question.prompt()) >= 4
                && isJapaneseQuestion(question.prompt())
                && japaneseChoice(question.answer(), evidence)
                && answerInEvidence(question.answer(), evidence)
                && categoryEvidenceSupported(question.kind(), question.answer(), evidence)
                && question.choices().size() == 4
                && new HashSet<>(question.choices()).size() == 4
                && question.choices().stream().allMatch(choice -> japaneseChoice(choice, evidence))
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
        if (ids.isEmpty()) return "AI chưa ghi ID dòng phụ đề làm dẫn chứng.";
        if (ids.size() > QuestionTranscript.MAX_EVIDENCE_LINES) return "Dẫn chứng vượt quá sáu dòng phụ đề liên tiếp.";
        if (new HashSet<>(ids).size() != ids.size()) return "AI ghi lặp ID dòng phụ đề dẫn chứng.";
        if (ids.stream().anyMatch(id -> !byId.containsKey(id)))
            return "AI dùng ID ngoài các dòng dẫn chứng đã đánh dấu: "
                    + ids.stream().filter(id -> !byId.containsKey(id)).toList();
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
            String line = QuestionTranscript.spoken(evidence.get(0).getText());
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
            if ("HARD".equals(candidate.level()) && !QuestionTranscript.sentenceKey(line)
                    .equals(QuestionTranscript.sentenceKey(answer)))
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
        if (!japaneseChoice(answer, evidence)
                || candidate.choices().stream().anyMatch(choice -> !japaneseChoice(choice, evidence)))
            return "Đáp án và lựa chọn cần dùng tiếng Nhật hoặc tên riêng có trong ngữ cảnh.";
        if (!answerInEvidence(answer, evidence))
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

    private Map<String, Integer> capacities(ShadowingQuestionBankRepository.PreparationJob job,
                                                    List<Subtitle> source) {
        return planCounts(analysisCurrent(job, source) ? bank.evidencePlans(job) : List.of());
    }

    private Map<String, Integer> remaining(ShadowingQuestionBankRepository.PreparationJob job,
                                                   List<Subtitle> source, List<PreparedQuestion> questions) {
        Map<String, Integer> result = capacities(job, source);
        Map<String, Integer> unused = planCounts(analysisCurrent(job, source)
                ? availablePlans(bank.evidencePlans(job), questions) : List.of());
        for (String kind : KINDS) {
            long used = questions.stream().filter(question -> kind.equals(question.kind())).count();
            result.put(kind, Math.min(unused.getOrDefault(kind, 0),
                    Math.max(0, result.getOrDefault(kind, 0) - (int) used)));
        }
        return result;
    }

    static boolean analysisCurrent(ShadowingQuestionBankRepository.PreparationJob job, List<Subtitle> source) {
        return job != null && !source.isEmpty() && hashSources(source).equals(job.suggestionHash())
                && QuestionTranscript.ANALYSIS_VERSION.equals(job.analysisVersion())
                && job.evidencePlansJson() != null && job.suggestedJlptLevel() != null;
    }

    static Map<Long, Integer> positions(List<Subtitle> source) {
        Map<Long, Integer> positions = new HashMap<>();
        for (int i = 0; i < source.size(); i++) positions.put(source.get(i).getId(), i);
        return positions;
    }

    static Map<String, Integer> planCounts(List<QuestionEvidencePlan> plans) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        // 20 per kind / 21 per generation job are request budgets, not video capacity.
        for (String kind : KINDS) counts.put(kind,
                (int) plans.stream().filter(p -> kind.equals(p.kind())).count());
        return counts;
    }

    static List<QuestionEvidencePlan> availablePlans(List<QuestionEvidencePlan> plans, List<PreparedQuestion> existing) {
        return plans.stream().filter(plan -> existing.stream().noneMatch(question ->
                QuestionTranscript.sameFact(plan, new QuestionEvidencePlan(question.kind(), question.sourceIds(),
                        "", question.answer(), List.of(), "")))).toList();
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
