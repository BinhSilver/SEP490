package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.VideoStatusDto;
import com.jlas.shadowing.dto.YoutubeAnalyzeResultDto;
import com.jlas.shadowing.dto.YoutubeImportResultDto;
import com.jlas.shadowing.dto.YoutubeImportStatusDto;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.TagRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.util.VideoAgeRatings;
import com.jlas.shadowing.util.NullSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

@Service
public class YoutubeImportService {

    @Autowired private YoutubeAnalyzeService youtubeAnalyzeService;
    @Autowired private YoutubeImportJobService youtubeImportJobService;
    @Autowired private YoutubeService youtubeService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private ShadowingVideoTags videoTags;
    @Autowired private ShadowingCreateQuotaService createQuota;

    public YoutubeAnalyzeResultDto analyze(String url) throws Exception {
        return youtubeAnalyzeService.analyze(url);
    }

    @Transactional
    public YoutubeImportResultDto startImport(String url, String category, String targetLanguage,
                                              Boolean generateFurigana) throws Exception {
        return startImport(url, category, targetLanguage, generateFurigana, null, null);
    }

    @Transactional
    public YoutubeImportResultDto startImport(String url, String category, String targetLanguage,
                                              Boolean generateFurigana, List<String> genres,
                                              Integer minAge) throws Exception {
        return startImport(url, category, targetLanguage, generateFurigana, genres, minAge, false);
    }

    /**
     * {@code useCredit}: the user agreed to pay CP for the minutes past this week's allowance
     * ({@link ShadowingCreateQuotaService}); without it such a video stops with SHADOWING_CREATE_QUOTA_EXCEEDED.
     */
    @Transactional
    public YoutubeImportResultDto startImport(String url, String category, String targetLanguage,
                                              Boolean generateFurigana, List<String> genres,
                                              Integer minAge, boolean useCredit) throws Exception {
        List<Tag> genreTags = videoTags.genreTags(genres);
        Integer requestedRating = VideoAgeRatings.parse(minAge);
        YoutubeAnalyzeResultDto meta = youtubeAnalyzeService.analyze(url);
        if (meta.isBlockedNonJapanese()) {
            throw new IllegalArgumentException(meta.getLanguageMessage() != null
                    ? meta.getLanguageMessage()
                    : "Video không được nhận diện là tiếng Nhật. Vui lòng chọn video có nội dung tiếng Nhật.");
        }
        if (meta.isBlockedNoSubtitleTooLong()) {
            throw new IllegalArgumentException(
                    "Video quá dài vì không có phụ đề. Vui lòng chọn video dưới 5 phút.");
        }
        // A long video with AI on is allowed; the page only warns that it costs more and takes longer.
        boolean wantAi = Boolean.TRUE.equals(generateFurigana);

        requireEmbeddable(meta);
        // Minutes with AI (furigana/translation, or speech to text when the video has no subtitles) count against the
        // weekly allowance; taken in this transaction, so a video that fails to start gives them back.
        ShadowingCreateQuotaService.Charge charge = createQuota.reserve(
                com.jlas.auth.security.CurrentUser.currentUserId().orElse(null),
                ShadowingCreateQuotaService.billableMinutes(meta.getDuration(), wantAi, meta.isRequiresAiStt()), useCredit);

        Video video = new Video();
        video.setTitle(meta.getTitle() != null && !meta.getTitle().isBlank()
                ? meta.getTitle()
                : "YouTube import");
        video.setSourceUrl(meta.getUrl());
        // video_path filled after async download (YoutubeImportJobService) — DB column is nullable
        video.setVideoPath(null);
        video.setDuration((double) meta.getDuration());
        video.setStatus(VideoStatus.PROCESSING);
        video.setProcessingStep(ProcessingStep.UPLOADED);
        video.setProgressPercent(5);
        video.setTargetLanguage(targetLanguage != null ? targetLanguage : "vi");
        // Default false = faster (skip furigana AI). User can opt-in on upload form.
        video.setGenerateFurigana(wantAi);
        video.setTags(ShadowingVideoTags.withGenres(resolveCategory(category), genreTags));
        try {
            video.setOwnerId(com.jlas.auth.security.CurrentUser.currentUserId().orElse(null));
        } catch (Exception ignored) {
            video.setOwnerId(null);
        }
        video.setVisibility(VideoVisibility.PRIVATE);
        video.setMinAge(VideoAgeRatings.stricter(requestedRating, meta.getYoutubeMinAge()));
        // New lessons are always played through the YouTube embed; the video file is no longer downloaded.
        video.setPlaybackMode(Video.PLAYBACK_EMBED);

        try {
            String thumbPath = meta.getThumbnail() != null && !meta.getThumbnail().isBlank()
                    ? youtubeService.downloadThumbnailFromUrl(meta.getThumbnail())
                    : null;
            video.setThumbnailPath(thumbPath);
        } catch (Exception e) {
            System.err.println("Preview thumbnail save failed: " + e.getMessage());
        }

        video = videoRepository.save(video);
        Long videoId = video.getId();
        createQuota.attach(charge, videoId);
        String normalizedUrl = meta.getUrl();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                youtubeImportJobService.processImport(videoId, normalizedUrl, meta);
            }
        });

        YoutubeImportResultDto result = new YoutubeImportResultDto();
        result.setLessonId(videoId);
        result.setJobId(videoId);
        result.setMessage("Đang tạo bài shadowing...");
        result.setCreditCharged(charge.creditPaid());
        return result;
    }

    /**
     * Lessons play through the YouTube embed and the file is not downloaded, so a video whose owner blocks
     * embedding cannot be used: say so before any work starts.
     */
    private static void requireEmbeddable(YoutubeAnalyzeResultDto meta) {
        if (!meta.isEmbeddable()) {
            throw new IllegalArgumentException("Vì lý do bản quyền, chủ video không cho phép phát video này ngoài YouTube "
                    + "nên không thể tạo bài shadowing. Hãy chọn một video khác.");
        }
    }

    public YoutubeImportStatusDto getImportStatus(Long jobId) {
        VideoStatusDto status = toStatusDto(videoRepository.findById(NullSafe.longId(jobId)).orElseThrow());
        YoutubeImportStatusDto dto = new YoutubeImportStatusDto();
        dto.setJobId(jobId);
        dto.setLessonId(jobId);
        dto.setProgress(status.getProgressPercent() != null ? status.getProgressPercent() : 0);
        dto.setVideoStatus(status.getStatus());
        dto.setProcessingStep(status.getProcessingStep());
        dto.setErrorMessage(status.getErrorMessage());
        dto.setStatus(humanStatus(status));
        return dto;
    }

    public void retryImport(Long videoId) {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null || video.getSourceUrl() == null) return;

        video.setStatus(VideoStatus.PROCESSING);
        video.setErrorMessage(null);
        video.setProgressPercent(5);
        video.setProcessingStep(ProcessingStep.UPLOADED);
        videoRepository.save(video);

        try {
            YoutubeAnalyzeResultDto meta = youtubeAnalyzeService.analyze(video.getSourceUrl());
            // The owner may have turned embedding off since the first import; such a video can no longer play here.
            requireEmbeddable(meta);
            video.setPlaybackMode(Video.PLAYBACK_EMBED);
            videoRepository.save(video);
            youtubeImportJobService.processImport(NullSafe.longId(videoId), video.getSourceUrl(), meta);
        } catch (Exception e) {
            video.setStatus(VideoStatus.FAILED);
            video.setErrorMessage(e.getMessage());
            videoRepository.save(video);
        }
    }

    public void resumeImport(Long videoId) {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElse(null);
        if (video == null || video.getSourceUrl() == null || video.getSourceUrl().isBlank()) return;

        video.setStatus(VideoStatus.PROCESSING);
        video.setErrorMessage(null);
        videoRepository.save(video);
        try {
            YoutubeAnalyzeResultDto meta = youtubeAnalyzeService.analyze(video.getSourceUrl());
            youtubeImportJobService.resumeImport(videoId, video.getSourceUrl(), meta);
        } catch (Exception e) {
            video.setStatus(VideoStatus.FAILED);
            video.setErrorMessage(e.getMessage());
            videoRepository.save(video);
        }
    }

    private VideoStatusDto toStatusDto(Video video) {
        VideoStatusDto dto = new VideoStatusDto();
        dto.setId(video.getId());
        dto.setTitle(video.getTitle());
        dto.setStatus(video.getStatus());
        dto.setProcessingStep(video.getProcessingStep());
        dto.setProgressPercent(video.getProgressPercent());
        dto.setErrorMessage(video.getErrorMessage());
        return dto;
    }

    private String humanStatus(VideoStatusDto status) {
        if (status.getStatus() == VideoStatus.FAILED) {
            return status.getErrorMessage() != null ? status.getErrorMessage() : "Xử lý thất bại";
        }
        // Only say "done" when pipeline finished (DONE + DRAFT/READY), not mid AI at 55%
        ProcessingStep step = status.getProcessingStep();
        if (status.getStatus() == VideoStatus.DRAFT || status.getStatus() == VideoStatus.READY) {
            if (step == null || step == ProcessingStep.DONE) {
                return "Hoàn thành — sẵn sàng chỉnh sửa";
            }
            // Legacy/mid-run DRAFT: fall through to step label
        }
        if (step == null) return "Đang xử lý...";
        return switch (step) {
            case UPLOADED -> "Bắt đầu import YouTube...";
            case YOUTUBE_DOWNLOAD -> "Đang lưu thông tin video...";
            case SUBTITLE_DOWNLOAD -> "Đang tải phụ đề (JP + dịch YouTube)...";
            case EXTRACTING_AUDIO -> "Đang tải audio...";
            case TRANSCRIBING -> "Đang nhận dạng giọng nói (Deepgram)...";
            case AI_PROCESSING -> "Đang xử lý AI (dịch / furigana nếu bật)...";
            case DONE -> "Hoàn thành — sẵn sàng chỉnh sửa";
            default -> "Đang tạo shadowing...";
        };
    }

    private List<Tag> resolveCategory(String category) {
        String name = CategoryConstants.normalize(
                category != null && !category.isBlank() ? category : CategoryConstants.DEFAULT);
        Tag tag = tagRepository.findByNameAndTagType(name, Tag.TagType.VIDEO).orElseGet(() -> {
            Tag t = new Tag();
            t.setName(name);
            t.setCode("VIDEO_" + Integer.toUnsignedString(name.hashCode(), 36).toUpperCase());
            t.setTagType(Tag.TagType.VIDEO);
            return tagRepository.save(t);
        });
        List<Tag> tags = new ArrayList<>();
        tags.add(tag);
        return tags;
    }
}
