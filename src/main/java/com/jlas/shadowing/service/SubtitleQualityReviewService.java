package com.jlas.shadowing.service;

import com.jlas.shadowing.dto.SubtitleDto;
import com.jlas.shadowing.model.Subtitle;
import com.jlas.shadowing.model.SubtitleQualityReview;
import com.jlas.shadowing.model.SubtitleQualitySource;
import com.jlas.shadowing.model.SubtitleQualityStatus;
import com.jlas.shadowing.repository.SubtitleQualityReviewRepository;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.util.SubtitleQualityComparator;
import com.jlas.shadowing.util.SubtitleText;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class SubtitleQualityReviewService {
    private static final String NEEDS_REVIEW_REASON = "Phụ đề YouTube và phần nghe từ audio chưa khớp.";

    private final SubtitleQualityReviewRepository reviewRepository;
    private final SubtitleRepository subtitleRepository;

    @Value("${youtube.subtitle.quality.enabled:true}")
    private boolean enabled;

    @Value("${youtube.subtitle.quality.max-duration-seconds:300}")
    private int maxDurationSeconds;

    @Value("${youtube.subtitle.quality.minimum-match-score:0.82}")
    private double minimumMatchScore;

    @Value("${youtube.subtitle.quality.time-overlap-padding-seconds:0.30}")
    private double overlapPaddingSeconds;

    public SubtitleQualityReviewService(SubtitleQualityReviewRepository reviewRepository,
                                        SubtitleRepository subtitleRepository) {
        this.reviewRepository = reviewRepository;
        this.subtitleRepository = subtitleRepository;
    }

    public boolean shouldCompare(int durationSeconds, SubtitleQualitySource source) {
        return enabled && source != SubtitleQualitySource.DEEPGRAM && durationSeconds > 0
                && durationSeconds <= maxDurationSeconds;
    }

    @Transactional
    public void recordYoutubeComparison(List<Subtitle> subtitles, List<SubtitleDto> transcript,
                                        SubtitleQualitySource source) {
        for (Subtitle subtitle : subtitles) {
            SubtitleDto cue = new SubtitleDto();
            cue.setText(subtitle.getText());
            cue.setStartTime(subtitle.getStartTime());
            cue.setEndTime(subtitle.getEndTime());
            SubtitleQualityComparator.Comparison comparison = SubtitleQualityComparator.compare(
                    cue, transcript, overlapPaddingSeconds);
            boolean verified = comparison.similarityScore() >= minimumMatchScore;
            reviewRepository.upsert(new SubtitleQualityReview(
                    subtitle.getId(), source, subtitle.getText(), comparison.transcriptionText(),
                    comparison.similarityScore(),
                    verified ? SubtitleQualityStatus.VERIFIED : SubtitleQualityStatus.NEEDS_REVIEW,
                    verified ? null : NEEDS_REVIEW_REASON, null, null));
        }
    }

    public Map<Long, SubtitleQualityReview> getForVideo(long videoId) {
        return reviewRepository.findByVideoId(videoId);
    }

    public int countNeedsReview(long videoId) {
        return reviewRepository.countNeedsReview(videoId);
    }

    public java.util.Set<Long> blockedSubtitleIds(long videoId) {
        return reviewRepository.findBlockedSubtitleIds(videoId);
    }

    @Transactional
    public SubtitleQualityReview approve(long subtitleId, String selectedText, long userId) {
        SubtitleQualityReview review = reviewRepository.findBySubtitleId(subtitleId)
                .orElseThrow(() -> new IllegalArgumentException("Câu này không có nội dung cần xác nhận."));
        Subtitle subtitle = subtitleRepository.findById(subtitleId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy câu phụ đề."));
        String cleaned = SubtitleText.normalizeJapanese(selectedText);
        if (cleaned == null || cleaned.isBlank()) {
            throw new IllegalArgumentException("Nội dung câu không được để trống.");
        }
        subtitle.setText(cleaned);
        subtitleRepository.save(subtitle);
        reviewRepository.approve(subtitleId, userId, LocalDateTime.now());
        return reviewRepository.findBySubtitleId(subtitleId).orElse(review);
    }
}
