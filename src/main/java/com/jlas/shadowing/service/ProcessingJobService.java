package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.SubtitleDto;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.SubtitleText;
import com.jlas.shadowing.util.SubtitleSegmenter;
import com.jlas.shadowing.util.NullSafe;
import com.jlas.shadowing.util.JapaneseLanguageDetector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ProcessingJobService {

    @Value("${processing.ai.batch.size:18}")
    private int aiBatchSize;

    @Autowired private VideoRepository videoRepository;
    @Autowired private SubtitleRepository subtitleRepository;
    @Autowired private FfmpegService ffmpegService;
    @Autowired private YoutubeService youtubeService;
    @Autowired private DeepgramService deepgramService;
    @Autowired private OpenRouterService openRouterService;
    @Autowired private ShadowingAiBatchProcessor aiBatchProcessor;
    @Autowired private VideoModerationService videoModerationService;
    @Autowired private ShadowingProcessingNotifier notifier;

    private final ConcurrentHashMap<Long, AtomicLong> runGeneration = new ConcurrentHashMap<>();

    @Async
    public void processVideo(Long videoId) {
        long runId = nextRunId(videoId);
        runPipeline(videoId, runId, true);
    }

    @Async
    public void regenerateTranscript(Long videoId) {
        long runId = nextRunId(videoId);
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null || !isActiveRun(videoId, runId)) return;

        try {
            video.setStatus(VideoStatus.PROCESSING);
            video.setProcessingStep(ProcessingStep.TRANSCRIBING);
            video.setProgressPercent(10);
            video.setErrorMessage(null);
            videoRepository.save(video);

            clearSubtitles(videoId);
            if (!isActiveRun(videoId, runId)) return;

            String audioPath = audioForStt(video);
            updateProgress(video, ProcessingStep.TRANSCRIBING, 30, null);
            if (!isActiveRun(videoId, runId)) return;

            List<SubtitleDto> dtos = SubtitleSegmenter.splitLongCues(transcribe(video, audioPath));
            requireJapaneseTranscript(dtos);
            if (!isActiveRun(videoId, runId)) return;

            List<Subtitle> savedSubs = saveSubtitles(video, dtos);
            markAfterTranscription(video);
            processAiBatches(video, savedSubs, lang(video), 55, 40, videoId, runId);

            if (!isActiveRun(videoId, runId)) return;
            video.setStatus(VideoStatus.DRAFT);
            video.setProcessingStep(ProcessingStep.DONE);
            video.setProgressPercent(100);
            video.setErrorMessage(null);
            videoRepository.save(video);
            try {
                videoModerationService.moderateVideo(videoId);
            } catch (Exception modEx) {
                System.err.println("regenerateTranscript: moderation failed id=" + videoId + " " + modEx.getMessage());
            }
            notifier.ready(video);
        } catch (Exception e) {
            if (isActiveRun(videoId, runId)) {
                Video failed = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
                if (failed != null) {
                    failed.setStatus(VideoStatus.FAILED);
                    failed.setErrorMessage(e.getMessage());
                    videoRepository.save(failed);
                    notifier.failed(failed, e.getMessage());
                }
            }
        }
    }

    @Async
    public void retryProcessing(Long videoId) {
        long runId = nextRunId(videoId);
        System.out.println("retryProcessing: video " + videoId + " runId=" + runId);
        clearSubtitles(videoId);

        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null) return;
        video.setStatus(VideoStatus.PROCESSING);
        video.setErrorMessage(null);
        video.setProgressPercent(5);
        video.setProcessingStep(ProcessingStep.UPLOADED);
        videoRepository.save(video);

        runPipeline(videoId, runId, false);
    }

    /** Continues from the last durable checkpoint without discarding completed AI batches. */
    @Async
    public void resumeProcessing(Long videoId) {
        long runId = nextRunId(videoId);
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null) return;

        List<Subtitle> savedSubs = subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId);
        if (video.getProcessingStep() == ProcessingStep.AI_PROCESSING && !savedSubs.isEmpty()) {
            try {
                video.setStatus(VideoStatus.PROCESSING);
                video.setErrorMessage(null);
                video.setProgressPercent(Math.max(55, safeProgress(video.getProgressPercent())));
                videoRepository.save(video);
                processAiBatches(video, savedSubs, lang(video), 55, 40, videoId, runId, true);
                if (!isActiveRun(videoId, runId)) return;
                finishProcessing(video, videoId);
            } catch (Exception e) {
                failProcessing(videoId, runId, e);
            }
            return;
        }

        // A stop can land between saving subtitles and recording the AI checkpoint.
        // Those incomplete rows must not be combined with a new transcription.
        if (!savedSubs.isEmpty()) {
            clearSubtitles(videoId);
        }
        video.setStatus(VideoStatus.PROCESSING);
        video.setErrorMessage(null);
        videoRepository.save(video);
        runPipeline(videoId, runId, false);
    }

    private void runPipeline(Long videoId, long runId, boolean clearSubsAtStart) {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null) {
            System.err.println("processVideo: video " + videoId + " not found — job skipped");
            return;
        }

        try {
            System.out.println("processVideo: starting id=" + videoId + " runId=" + runId);

            if (clearSubsAtStart) {
                clearSubtitles(videoId);
            }
            if (!isActiveRun(videoId, runId)) {
                System.out.println("processVideo: cancelled id=" + videoId + " runId=" + runId);
                return;
            }

            updateProgress(video, ProcessingStep.EXTRACTING_AUDIO, 10, null);
            String audioPath = audioForStt(video);
            if (!isActiveRun(videoId, runId)) return;

            updateProgress(video, ProcessingStep.TRANSCRIBING, 25, null);
            List<SubtitleDto> dtos = SubtitleSegmenter.splitLongCues(transcribe(video, audioPath));
            requireJapaneseTranscript(dtos);
            if (!isActiveRun(videoId, runId)) return;

            List<Subtitle> savedSubs = saveSubtitles(video, dtos);
            markAfterTranscription(video);
            processAiBatches(video, savedSubs, lang(video), 55, 40, videoId, runId);

            if (!isActiveRun(videoId, runId)) return;
            finishProcessing(video, videoId);

        } catch (Exception e) {
            if (isActiveRun(videoId, runId)) {
                video.setStatus(VideoStatus.FAILED);
                video.setErrorMessage(e.getMessage());
                videoRepository.save(video);
                notifier.failed(video, e.getMessage());
                e.printStackTrace();
            } else {
                System.out.println("processVideo: error ignored (superseded) id=" + videoId + " runId=" + runId);
            }
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

    private void clearSubtitles(Long videoId) {
        subtitleRepository.deleteByVideoId(videoId);
    }

    private List<Subtitle> saveSubtitles(Video video, List<SubtitleDto> dtos) {
        List<Subtitle> savedSubs = new ArrayList<>();
        int order = 0;
        for (SubtitleDto dto : dtos) {
            String text = SubtitleText.normalizeJapanese(dto.getText());
            // Orphan punctuation-only cues (e.g. lone "。") — skip
            if (text.isBlank()) {
                continue;
            }
            Subtitle sub = new Subtitle();
            sub.setVideo(video);
            sub.setText(text);
            sub.setStartTime(dto.getStartTime());
            sub.setEndTime(dto.getEndTime());
            sub.setSortOrder(order++);
            sub.setAiStatus(AiStatus.PENDING);
            savedSubs.add(subtitleRepository.save(sub));
        }
        return savedSubs;
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

    /** Transcribes {@code audioPath}; a temporary YouTube audio file is deleted whatever the outcome. */
    private List<SubtitleDto> transcribe(Video video, String audioPath) throws Exception {
        try {
            return deepgramService.transcribe(audioPath);
        } finally {
            if (video.getVideoPath() == null || video.getVideoPath().isBlank()) {
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(audioPath)); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Audio to transcribe. Uploaded files are prepared from their stored media; YouTube videos keep no file on
     * the server, so their audio is fetched temporarily from the link.
     */
    private String audioForStt(Video video) throws Exception {
        String path = video.getVideoPath();
        if (path != null && !path.isBlank()) return ffmpegService.prepareAudioForStt(path);
        if (ShadowingTakedownService.sourceKey(video.getSourceUrl()).isPresent()) {
            return youtubeService.downloadAudio(video.getSourceUrl());
        }
        throw new IllegalStateException("Video không có tệp âm thanh để tạo phụ đề.");
    }

    /**
     * After STT: stay PROCESSING (not DRAFT). DRAFT only when AI finishes at 100%,
     * otherwise UI shows "Hoàn thành" while bar is still 55%.
     */
    private void markAfterTranscription(Video video) {
        try {
            video.setDuration(ffmpegService.getDuration(video.getVideoPath()));
        } catch (Exception ignored) {}

        video.setStatus(VideoStatus.PROCESSING);
        video.setProcessingStep(ProcessingStep.AI_PROCESSING);
        video.setProgressPercent(55);
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

        updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart, null);

        // Resume only sends unfinished lines (workItems); batches run through the shared parallel AI pool.
        int batchSize = resolveAiBatchSize(workItems.size());
        int totalBatches = (workItems.size() + batchSize - 1) / batchSize;
        System.out.println("[Upload AI] " + workItems.size() + " lines  "
                + totalBatches + " batch(es) × ~" + batchSize);

        aiBatchProcessor.process(workItems, batchSize, lang,
                Boolean.TRUE.equals(video.getGenerateFurigana()), false,
                () -> isActiveRun(videoId, runId), (batch, results, end) -> {
            if (!isActiveRun(videoId, runId)) return;

            for (int j = 0; j < batch.size(); j++) {
                Subtitle sub = batch.get(j);
                // Re-normalize in case STT left a leading "。" on stored text
                String jp = SubtitleText.normalizeJapanese(sub.getText());
                if (!jp.equals(sub.getText())) {
                    sub.setText(jp);
                }
                String furi = results.get(j).get("furigana");
                String cleanFuri = SubtitleText.sanitizeAiField(furi);
                sub.setFurigana(cleanFuri != null && !cleanFuri.isBlank() ? cleanFuri : jp);
                sub.setTranslation(SubtitleText.sanitizeAiField(results.get(j).get("translation")));
                sub.setAiStatus(AiStatus.COMPLETED);
                subtitleRepository.save(sub);
            }

            int progress = progressStart + (int) ((end / (double) workItems.size()) * progressRange);
            updateProgress(video, ProcessingStep.AI_PROCESSING, progress, null);
        });
    }

    private void finishProcessing(Video video, Long videoId) {
        video.setStatus(VideoStatus.DRAFT);
        video.setProcessingStep(ProcessingStep.DONE);
        video.setProgressPercent(100);
        video.setErrorMessage(null);
        videoRepository.save(video);
        try {
            videoModerationService.moderateVideo(videoId);
        } catch (Exception modEx) {
            System.err.println("processVideo: moderation failed id=" + videoId + " " + modEx.getMessage());
        }
        notifier.ready(video);
    }

    private void failProcessing(Long videoId, long runId, Exception e) {
        if (!isActiveRun(videoId, runId)) return;
        Video failed = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (failed != null) {
            failed.setStatus(VideoStatus.FAILED);
            failed.setErrorMessage(e.getMessage());
            videoRepository.save(failed);
            notifier.failed(failed, e.getMessage());
        }
    }

    private int safeProgress(Integer progress) {
        return progress == null ? 0 : progress;
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
