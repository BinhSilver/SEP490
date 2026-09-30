package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.SubtitleDto;
import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.youtube.SubtitleAligner;
import com.jlas.shadowing.util.youtube.SubtitleParser;
import com.jlas.shadowing.util.youtube.TranslationTrack;
import com.jlas.shadowing.util.JapaneseLanguageDetector;
import com.jlas.shadowing.util.NullSafe;
import com.jlas.shadowing.util.SubtitleSegmenter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class YoutubeImportJobService {

    @Value("${processing.ai.batch.size:18}")
    private int aiBatchSize;

    @Value("${youtube.max.duration.no-subtitle.seconds:300}")
    private int maxDurationNoSubtitleSeconds;

    @Value("${processing.ai.max.duration.seconds:1200}")
    private int aiMaxDurationSeconds;

    @Autowired private VideoRepository videoRepository;
    @Autowired private SubtitleRepository subtitleRepository;
    @Autowired private YoutubeService youtubeService;
    @Autowired private YoutubeAnalyzeService youtubeAnalyzeService;
    @Autowired private DeepgramService deepgramService;
    @Autowired private OpenRouterService openRouterService;
    @Autowired private SubtitleQualityReviewService subtitleQualityReviewService;
    @Autowired private ShadowingAiBatchProcessor aiBatchProcessor;

    private final ConcurrentHashMap<Long, AtomicLong> runGeneration = new ConcurrentHashMap<>();

    @Async
    public void processImport(Long videoId, String url, YoutubeAnalyzeResultDto meta) {
        long runId = nextRunId(videoId);
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null) return;

        System.out.println("[YouTube import] start videoId=" + videoId + " runId=" + runId
                + " duration=" + meta.getDuration() + "s title=" + meta.getTitle());

        try {
            subtitleRepository.deleteByVideoId(videoId);
            if (!isActiveRun(videoId, runId)) return;

            ResolvedSubtitles resolved = resolveSubtitles(url, meta, videoId, runId);
            List<SubtitleDto> dtos = resolved.subtitles();
            if (dtos == null || dtos.isEmpty()) {
                throw new IllegalArgumentException(
                        "Không tạo được phụ đề từ video này. "
                                + "Hãy chọn video có phụ đề tiếng Nhật (CC), hoặc video ≤ 5 phút để dùng AI STT.");
            }
            int beforeSplit = dtos.size();
            dtos = SubtitleSegmenter.splitLongCues(dtos);
            if (dtos.size() != beforeSplit) {
                System.out.println("[YouTube import] split long subtitles "
                        + beforeSplit + " -> " + dtos.size());
            }
            if (!isActiveRun(videoId, runId)) return;

            List<Subtitle> savedSubs = saveSubtitles(video, dtos);
            if (subtitleQualityReviewService.shouldCompare(meta.getDuration(), resolved.source())) {
                List<SubtitleDto> audioTranscript = transcribeWithDeepgram(url, video, videoId, runId);
                if (!isActiveRun(videoId, runId)) return;
                subtitleQualityReviewService.recordYoutubeComparison(savedSubs, audioTranscript, resolved.source());
            }
            markSubtitlesReady(video);
            processAiBatches(video, savedSubs, lang(video), 40, 20, videoId, runId);
            if (!isActiveRun(videoId, runId)) return;

            downloadVideoWithProgress(video, url, meta, videoId, runId);
            if (!isActiveRun(videoId, runId)) return;

            finishImport(video);
            System.out.println("[YouTube import] done videoId=" + videoId);

        } catch (Exception e) {
            if (isActiveRun(videoId, runId)) {
                video.setStatus(VideoStatus.FAILED);
                video.setErrorMessage(e.getMessage());
                videoRepository.save(video);
                System.err.println("[YouTube import] failed videoId=" + videoId + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /** Continues from a saved subtitle, AI, or downloaded-video checkpoint. */
    @Async
    public void resumeImport(Long videoId, String url, YoutubeAnalyzeResultDto meta) {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null) return;

        ProcessingStep step = video.getProcessingStep();
        List<Subtitle> savedSubs = subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId);
        if (step != ProcessingStep.AI_PROCESSING && step != ProcessingStep.YOUTUBE_DOWNLOAD) {
            // There is no complete subtitle checkpoint before this stage.
            processImport(videoId, url, meta);
            return;
        }

        long runId = nextRunId(videoId);
        try {
            video.setStatus(VideoStatus.PROCESSING);
            video.setErrorMessage(null);
            videoRepository.save(video);
            if (step == ProcessingStep.AI_PROCESSING) {
                if (savedSubs.isEmpty()) {
                    processImport(videoId, url, meta);
                    return;
                }
                processAiBatches(video, savedSubs, lang(video), 40, 20, videoId, runId, true);
                if (!isActiveRun(videoId, runId)) return;
            }
            if (video.getVideoPath() == null || video.getVideoPath().isBlank()) {
                downloadVideoWithProgress(video, url, meta, videoId, runId);
                if (!isActiveRun(videoId, runId)) return;
            }
            finishImport(video);
            System.out.println("[YouTube import] resumed videoId=" + videoId + " from " + step);
        } catch (Exception e) {
            failImport(videoId, runId, e);
        }
    }

    private void downloadVideoWithProgress(Video video, String url, YoutubeAnalyzeResultDto meta,
                                           Long videoId, long runId) throws Exception {
        updateProgress(video, ProcessingStep.YOUTUBE_DOWNLOAD, 65, null);
        int minutes = Math.max(15, meta.getDuration() / 60 + 10);
        Duration timeout = Duration.ofMinutes(minutes);
        AtomicBoolean done = new AtomicBoolean(false);

        Thread pulse = new Thread(() -> {
            int pct = 65;
            while (!done.get() && isActiveRun(videoId, runId) && pct < 94) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (done.get()) return;
                pct = Math.min(94, pct + 2);
                Video v = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
                if (v != null && isActiveRun(videoId, runId)) {
                    v.setProgressPercent(pct);
                    videoRepository.save(v);
                }
            }
        }, "yt-download-pulse-" + videoId);
        pulse.setDaemon(true);
        pulse.start();

        try {
            String videoPath = youtubeService.downloadVideo(url, timeout);
            if (!isActiveRun(videoId, runId)) return;

            video.setVideoPath(videoPath);
            video.setDuration((double) meta.getDuration());
            if (video.getThumbnailPath() == null) {
                try {
                    String thumb = meta.getThumbnail() != null && !meta.getThumbnail().isBlank()
                            ? youtubeService.downloadThumbnailFromUrl(meta.getThumbnail())
                            : youtubeService.downloadThumbnailWithYtdlp(url);
                    video.setThumbnailPath(thumb);
                } catch (Exception e) {
                    System.err.println("Thumbnail download failed: " + e.getMessage());
                }
            }
            updateProgress(video, ProcessingStep.YOUTUBE_DOWNLOAD, 95, null);
        } finally {
            done.set(true);
        }
    }

    private ResolvedSubtitles resolveSubtitles(String url, YoutubeAnalyzeResultDto meta,
                                               Long videoId, long runId) throws Exception {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();

        Optional<TranslationTrack> jaTrack = youtubeAnalyzeService.resolveJapaneseTrack(meta);
        if (jaTrack.isPresent()) {
            updateProgress(video, ProcessingStep.SUBTITLE_DOWNLOAD, 15, null);
            List<SubtitleDto> fromYt = loadSubtitlesWithTranslation(url, meta, jaTrack.get(), lang(video));
            if (fromYt != null && !fromYt.isEmpty()) {
                System.out.println("[YouTube import] loaded " + fromYt.size()
                        + " JP cues from YouTube (lang=" + jaTrack.get().langCode() + ")");
                return new ResolvedSubtitles(fromYt, sourceOf(jaTrack.get()));
            }
            System.err.println("[YouTube import] JP track detected but parse empty; trying alternate download");
            // Retry JA only, force both manual + auto flags
            List<SubtitleDto> retry = loadJapaneseOnly(url, jaTrack.get());
            if (retry != null && !retry.isEmpty()) {
                return new ResolvedSubtitles(retry, sourceOf(jaTrack.get()));
            }
            // Last resort for short videos: STT even if analyze thought there were JP subs
            if (meta.getDuration() <= maxDurationNoSubtitleSeconds) {
                System.err.println("[YouTube import] falling back to Deepgram STT");
                return new ResolvedSubtitles(transcribeWithDeepgram(url, video, videoId, runId),
                        SubtitleQualitySource.DEEPGRAM);
            }
            throw new IllegalArgumentException(
                    "Có phụ đề tiếng Nhật trên YouTube nhưng không tải/đọc được (yt-dlp). "
                            + "Thử cập nhật yt-dlp: pip install -U yt-dlp");
        }

        if (meta.getDuration() > maxDurationNoSubtitleSeconds) {
            throw new IllegalArgumentException(
                    "Video quá dài vì không có phụ đề. Vui lòng chọn video dưới 5 phút.");
        }
        return new ResolvedSubtitles(transcribeWithDeepgram(url, video, videoId, runId),
                SubtitleQualitySource.DEEPGRAM);
    }

    private List<SubtitleDto> transcribeWithDeepgram(String url, Video video, Long videoId, long runId)
            throws Exception {
        updateProgress(video, ProcessingStep.EXTRACTING_AUDIO, 20, null);
        String audioPath = youtubeService.downloadAudio(url);
        try {
            if (!isActiveRun(videoId, runId)) return List.of();
            updateProgress(video, ProcessingStep.TRANSCRIBING, 30, null);
            List<SubtitleDto> transcript = deepgramService.transcribe(audioPath);
            requireJapaneseTranscript(transcript);
            return transcript;
        } finally {
            Files.deleteIfExists(Path.of(audioPath));
        }
    }

    private static SubtitleQualitySource sourceOf(TranslationTrack track) {
        return track.autoOnly() ? SubtitleQualitySource.YOUTUBE_AUTO : SubtitleQualitySource.YOUTUBE_CREATOR;
    }

    private void requireJapaneseTranscript(List<SubtitleDto> subtitles) {
        List<String> texts = subtitles == null ? List.of() : subtitles.stream()
                .map(SubtitleDto::getText)
                .filter(text -> text != null && !text.isBlank())
                .toList();
        JapaneseLanguageDetector.Result result = JapaneseLanguageDetector.inspect(texts);
        if (!result.japanese()) {
            throw new IllegalArgumentException(
                    "Video không được nhận diện là tiếng Nhật. " + result.reason()
                            + " JLAS đã dừng tạo bài để tránh tạo phụ đề sai ngôn ngữ.");
        }
    }

    private List<SubtitleDto> loadJapaneseOnly(String url, TranslationTrack jaTrack) throws Exception {
        // Try auto + manual variants of the same lang
        List<TranslationTrack> variants = List.of(
                new TranslationTrack(jaTrack.langCode(), false),
                new TranslationTrack(jaTrack.langCode(), true),
                new TranslationTrack("ja", false),
                new TranslationTrack("ja", true)
        );
        for (TranslationTrack t : variants) {
            try {
                Map<String, Path> files = youtubeService.downloadSubtitleTracks(url, List.of(t));
                Path jaFile = files.get(t.langCode());
                if (jaFile == null) continue;
                long size = Files.exists(jaFile) ? Files.size(jaFile) : -1;
                List<SubtitleDto> japanese = SubtitleParser.parseVtt(jaFile);
                if (!japanese.isEmpty()) {
                    System.out.println("[YouTube import] JA-only OK lang=" + t.langCode()
                            + " auto=" + t.autoOnly() + " cues=" + japanese.size());
                    cleanupSubtitleTemp(jaFile);
                    return japanese;
                }
                System.err.println("[YouTube import] empty VTT for lang=" + t.langCode()
                        + " size=" + size + " path=" + jaFile);
                cleanupSubtitleTemp(jaFile);
            } catch (Exception e) {
                System.err.println("[YouTube import] JA-only attempt failed: " + e.getMessage());
            }
        }
        return List.of();
    }

    private List<SubtitleDto> loadSubtitlesWithTranslation(String url, YoutubeAnalyzeResultDto meta,
                                                           TranslationTrack jaTrack, String targetLang) throws Exception {
        List<TranslationTrack> tracks = new ArrayList<>();
        tracks.add(jaTrack);
        Optional<TranslationTrack> transTrack = youtubeAnalyzeService.resolveTranslationTrack(meta, targetLang);
        // Translation is optional — never block import if VI/EN missing
        transTrack.ifPresent(tracks::add);

        Map<String, Path> files = youtubeService.downloadSubtitleTracks(url, tracks);
        Path jaFile = files.get(jaTrack.langCode());
        if (jaFile == null) {
            // Retry JA alone without translation lang (yt-dlp sometimes fails multi-lang)
            files = youtubeService.downloadSubtitleTracks(url, List.of(jaTrack));
            jaFile = files.get(jaTrack.langCode());
        }
        if (jaFile == null) {
            System.err.println("[YouTube import] no JA subtitle file for lang=" + jaTrack.langCode()
                    + " auto=" + jaTrack.autoOnly());
            return List.of();
        }

        try {
            List<SubtitleDto> japanese = SubtitleParser.parseVtt(jaFile);
            if (japanese.isEmpty()) {
                long size = Files.size(jaFile);
                String head = Files.readString(jaFile).substring(0, (int) Math.min(size, 400));
                System.err.println("[YouTube import] empty parse JA VTT size=" + size + " head=\n" + head);
                return List.of();
            }
            if (transTrack.isPresent()) {
                Path transFile = files.get(transTrack.get().langCode());
                // Retry translation track alone (batch multi-lang sometimes drops VI/EN)
                if (transFile == null) {
                    try {
                        Map<String, Path> onlyTrans = youtubeService.downloadSubtitleTracks(
                                url, List.of(transTrack.get()));
                        transFile = onlyTrans.get(transTrack.get().langCode());
                    } catch (Exception e) {
                        System.err.println("[YouTube import] translation-only download failed: " + e.getMessage());
                    }
                }
                if (transFile != null) {
                    List<SubtitleDto> translations = SubtitleParser.parseVtt(transFile);
                    translations = SubtitleParser.collapseRollingCaptions(translations);
                    SubtitleAligner.attachTranslations(japanese, translations);
                    long attached = japanese.stream()
                            .filter(s -> s.getTranslation() != null && !s.getTranslation().isBlank())
                            .count();
                    System.out.println("[YouTube import] attached YT translation lang="
                            + transTrack.get().langCode()
                            + " cues=" + translations.size()
                            + " matched=" + attached + "/" + japanese.size());
                } else {
                    System.err.println("[YouTube import] translation track missing (optional): "
                            + transTrack.get().langCode()
                            + " — if furigana is off, no AI will run (JP only)");
                }
            }
            return japanese;
        } finally {
            cleanupSubtitleTemp(jaFile);
        }
    }

    private static void cleanupSubtitleTemp(Path jaFile) {
        if (jaFile == null) return;
        Path tempRoot = jaFile.getParent();
        // walk up to yt_sub_* root if nested
        while (tempRoot != null && tempRoot.getFileName() != null
                && !tempRoot.getFileName().toString().startsWith("yt_sub_")) {
            Path parent = tempRoot.getParent();
            if (parent == null) break;
            if (parent.getFileName() != null && parent.getFileName().toString().startsWith("yt_sub_")) {
                tempRoot = parent;
                break;
            }
            tempRoot = parent;
        }
        if (tempRoot == null) tempRoot = jaFile.getParent();
        if (tempRoot == null) return;
        try (var walk = Files.walk(tempRoot)) {
            walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                    });
        } catch (Exception ignored) {
        }
    }

    private long nextRunId(Long videoId) {
        return runGeneration.computeIfAbsent(videoId, id -> new AtomicLong(0)).incrementAndGet();
    }

    /** Invalidates the active run so it cannot persist any later pipeline result. */
    public void cancel(Long videoId) {
        nextRunId(videoId);
    }

    private boolean isActiveRun(Long videoId, long runId) {
        AtomicLong gen = runGeneration.get(videoId);
        return gen != null && gen.get() == runId;
    }

    private record ResolvedSubtitles(List<SubtitleDto> subtitles, SubtitleQualitySource source) {
    }

    private List<Subtitle> saveSubtitles(Video video, List<SubtitleDto> dtos) {
        List<Subtitle> savedSubs = new ArrayList<>();
        int order = 0;
        for (SubtitleDto dto : dtos) {
            if (dto.getText() == null || dto.getText().isBlank()) continue;
            Subtitle sub = new Subtitle();
            sub.setVideo(video);
            sub.setText(dto.getText().replace(" ", ""));
            sub.setStartTime(dto.getStartTime());
            sub.setEndTime(dto.getEndTime());
            sub.setSortOrder(order++);
            if (dto.getTranslation() != null && !dto.getTranslation().isBlank()) {
                sub.setTranslation(dto.getTranslation().trim());
            }
            sub.setAiStatus(AiStatus.PENDING);
            savedSubs.add(subtitleRepository.save(sub));
        }
        return savedSubs;
    }

    private void markSubtitlesReady(Video video) {
        video.setProcessingStep(ProcessingStep.AI_PROCESSING);
        video.setProgressPercent(38);
        video.setErrorMessage(null);
        videoRepository.save(video);
    }

    private void processAiBatches(Video video, List<Subtitle> savedSubs, String lang,
                                  int progressStart, int progressRange,
                                  Long videoId, long runId) {
        processAiBatches(video, savedSubs, lang, progressStart, progressRange, videoId, runId, false);
    }

    private void processAiBatches(Video video, List<Subtitle> savedSubs, String lang,
                                  int progressStart, int progressRange,
                                  Long videoId, long runId, boolean onlyUnfinished) {
        List<Subtitle> workItems = onlyUnfinished
                ? savedSubs.stream().filter(sub -> sub.getAiStatus() != AiStatus.COMPLETED).toList()
                : savedSubs;
        if (workItems.isEmpty()) {
            updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart + progressRange, null);
            return;
        }

        boolean wantFurigana = Boolean.TRUE.equals(video.getGenerateFurigana());
        long withYtTrans = workItems.stream()
                .filter(s -> s.getTranslation() != null && !s.getTranslation().isBlank())
                .count();

        System.out.println("[YouTube import] AI phase generateFurigana=" + wantFurigana
                + " subtitles=" + workItems.size()
                + " withYtTranslation=" + withYtTrans);

        // Safety net (startImport should already reject)
        double dur = video.getDuration() != null ? video.getDuration() : 0;
        if (wantFurigana && dur > aiMaxDurationSeconds) {
            int maxMin = Math.max(1, aiMaxDurationSeconds / 60);
            throw new IllegalArgumentException(
                    "Khi bật furigana/dịch AI, video tối đa " + maxMin + " phút.");
        }

        // Furigana OFF  never call OpenRouter. Keep YouTube translation if present; furigana = plain JP.
        if (!wantFurigana) {
            updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart, null);
            for (Subtitle sub : workItems) {
                sub.setFurigana(sub.getText());
                if (sub.getTranslation() == null) sub.setTranslation("");
                sub.setAiStatus(AiStatus.COMPLETED);
                subtitleRepository.save(sub);
            }
            System.out.println("[YouTube import] skipped AI (furigana off) — using YT JP"
                    + (withYtTrans > 0 ? " + YT translation" : " only (no YT translation track)"));
            updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart + progressRange, null);
            return;
        }

        updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart, null);

        // Resume sends only unfinished lines; each chunk keeps an existing YouTube translation (furigana-only call).
        List<Subtitle> needsAi = workItems;

        if (needsAi.isEmpty()) {
            updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart + progressRange, null);
            return;
        }

        int batchSize = resolveAiBatchSize(needsAi.size());
        int totalBatches = (needsAi.size() + batchSize - 1) / batchSize;
        System.out.println("[YouTube import] AI batches: " + needsAi.size() + " lines  "
                + totalBatches + " batch(es) × ~" + batchSize);

        aiBatchProcessor.process(needsAi, batchSize, lang, true, true,
                () -> isActiveRun(videoId, runId), (batch, results, end) -> {
            if (!isActiveRun(videoId, runId)) return;
            for (int j = 0; j < batch.size(); j++) {
                Subtitle sub = batch.get(j);
                String furi = results.get(j).get("furigana");
                String trans = results.get(j).get("translation");
                sub.setFurigana(furi != null && !furi.isBlank() ? furi : sub.getText());
                if ((sub.getTranslation() == null || sub.getTranslation().isBlank())
                        && trans != null && !trans.isBlank()) {
                    sub.setTranslation(trans);
                } else if (sub.getTranslation() == null || sub.getTranslation().isBlank()) {
                    sub.setTranslation("");
                }
                sub.setAiStatus(AiStatus.COMPLETED);
                subtitleRepository.save(sub);
            }
            int progress = progressStart + (int) ((end / (double) needsAi.size()) * progressRange);
            updateProgress(video, ProcessingStep.AI_PROCESSING, progress, null);
        });
    }

    private void finishImport(Video video) {
        video.setStatus(VideoStatus.DRAFT);
        video.setProcessingStep(ProcessingStep.DONE);
        video.setProgressPercent(100);
        video.setErrorMessage(null);
        videoRepository.save(video);
    }

    private void failImport(Long videoId, long runId, Exception e) {
        if (!isActiveRun(videoId, runId)) return;
        Video failed = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (failed != null) {
            failed.setStatus(VideoStatus.FAILED);
            failed.setErrorMessage(e.getMessage());
            videoRepository.save(failed);
        }
    }

    /** Bound each AI request so batches can run concurrently without oversized responses. */
    private int resolveAiBatchSize(int total) {
        if (total <= 0) {
            return Math.max(1, aiBatchSize);
        }
        return Math.min(Math.max(4, aiBatchSize), 16);
    }

    private String lang(Video video) {
        return video.getTargetLanguage() != null ? video.getTargetLanguage() : "vi";
    }

    private void updateProgress(Video video, ProcessingStep step, int percent, String error) {
        video.setProcessingStep(step);
        video.setProgressPercent(percent);
        if (error != null) video.setErrorMessage(error);
        videoRepository.save(video);
    }
}
