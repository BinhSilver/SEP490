package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.SubtitleDto;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.SubtitleText;
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
    @Autowired private DeepgramService deepgramService;
    @Autowired private OpenRouterService openRouterService;

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

            String audioPath = ffmpegService.prepareAudioForStt(video.getVideoPath());
            updateProgress(video, ProcessingStep.TRANSCRIBING, 30, null);
            if (!isActiveRun(videoId, runId)) return;

            List<SubtitleDto> dtos = deepgramService.transcribe(audioPath);
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
        } catch (Exception e) {
            if (isActiveRun(videoId, runId)) {
                Video failed = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
                if (failed != null) {
                    failed.setStatus(VideoStatus.FAILED);
                    failed.setErrorMessage(e.getMessage());
                    videoRepository.save(failed);
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
            String audioPath = ffmpegService.prepareAudioForStt(video.getVideoPath());
            if (!isActiveRun(videoId, runId)) return;

            updateProgress(video, ProcessingStep.TRANSCRIBING, 25, null);
            List<SubtitleDto> dtos = deepgramService.transcribe(audioPath);
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

        } catch (Exception e) {
            if (isActiveRun(videoId, runId)) {
                video.setStatus(VideoStatus.FAILED);
                video.setErrorMessage(e.getMessage());
                videoRepository.save(video);
                e.printStackTrace();
            } else {
                System.out.println("processVideo: error ignored (superseded) id=" + videoId + " runId=" + runId);
            }
        }
    }

    private long nextRunId(Long videoId) {
        return runGeneration.computeIfAbsent(videoId, id -> new AtomicLong(0)).incrementAndGet();
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
        if (savedSubs.isEmpty()) return;

        updateProgress(video, ProcessingStep.AI_PROCESSING, progressStart, null);

        int batchSize = resolveAiBatchSize(savedSubs.size());
        int totalRounds = (savedSubs.size() + batchSize - 1) / batchSize;
        System.out.println("[Upload AI] " + savedSubs.size() + " lines  "
                + totalRounds + " round(s) × ~" + batchSize);

        for (int i = 0, round = 1; i < savedSubs.size(); i += batchSize, round++) {
            if (!isActiveRun(videoId, runId)) return;

            int end = Math.min(i + batchSize, savedSubs.size());
            List<Subtitle> batch = savedSubs.subList(i, end);
            List<String> texts = batch.stream().map(sub -> sub.getText()).toList();
            System.out.println("[Upload AI] round " + round + "/" + totalRounds
                    + " lines " + (i + 1) + "–" + end);

            boolean wantFurigana = Boolean.TRUE.equals(video.getGenerateFurigana());
            List<Map<String, String>> results =
                    openRouterService.processJapaneseBatch(texts, lang, wantFurigana);
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

            int progress = progressStart + (int) ((end / (double) savedSubs.size()) * progressRange);
            updateProgress(video, ProcessingStep.AI_PROCESSING, progress, null);
        }
    }

    /**
     * Ít câu: 1 lượt theo batch config.
     * Cỡ podcast (≤100): 2 lượt. Dài hơn: 3 lượt.
     */
    private int resolveAiBatchSize(int total) {
        if (total <= 0) {
            return Math.max(1, aiBatchSize);
        }
        if (total <= 40) {
            return Math.min(Math.max(1, aiBatchSize), total);
        }
        int rounds = total <= 100 ? 2 : 3;
        return (int) Math.ceil(total / (double) rounds);
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
