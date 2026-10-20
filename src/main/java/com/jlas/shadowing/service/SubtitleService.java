package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.repository.VocabularyEntryRepository;
import com.jlas.shadowing.util.NullSafe;
import com.jlas.shadowing.util.SubtitleText;
import com.jlas.shadowing.util.JapaneseLanguageDetector;
import com.jlas.shadowing.util.VideoOwnership;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class SubtitleService {

    @Autowired private SubtitleRepository subtitleRepository;
    @Autowired private VideoRepository videoRepository;
    @Autowired private VocabularyEntryRepository vocabularyEntryRepository;
    @Autowired private OpenRouterService openRouterService;
    @Autowired @Lazy private ProcessingJobService processingJobService;
    @Autowired private SubtitleQualityReviewService subtitleQualityReviewService;

    public List<Subtitle> getByVideoId(Long videoId) {
        List<Subtitle> subtitles = subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId);
        subtitles.forEach(this::attachRuntimeFurigana);
        return subtitles;
    }

    public Subtitle getById(Long id) {
        Subtitle subtitle = subtitleRepository.findById(NullSafe.longId(id)).orElseThrow();
        attachRuntimeFurigana(subtitle);
        return subtitle;
    }

    @Transactional
    public Subtitle update(Long id, SubtitleUpdateRequest req) {
        Subtitle sub = getEditableSubtitle(id);
        if (req.getText() != null) sub.setText(req.getText());
        if (req.getFurigana() != null) sub.setFurigana(req.getFurigana());
        if (req.getTranslation() != null) sub.setTranslation(req.getTranslation());
        if (req.getStartTime() != null) sub.setStartTime(req.getStartTime());
        if (req.getEndTime() != null) sub.setEndTime(req.getEndTime());
        Subtitle saved = NullSafe.ref(subtitleRepository.save(sub));
        markModerationStale(sub.getVideo());
        return saved;
    }

    @Transactional
    public List<Subtitle> bulkUpdate(Long videoId, List<SubtitleUpdateRequest> requests) {
        Video video = getEditableVideo(videoId);
        List<Subtitle> subs = getByVideoId(videoId);
        for (int i = 0; i < Math.min(subs.size(), requests.size()); i++) {
            SubtitleUpdateRequest req = requests.get(i);
            Subtitle sub = subs.get(i);
            if (req.getText() != null) sub.setText(req.getText());
            if (req.getFurigana() != null) sub.setFurigana(req.getFurigana());
            if (req.getTranslation() != null) sub.setTranslation(req.getTranslation());
            if (req.getStartTime() != null) sub.setStartTime(req.getStartTime());
            if (req.getEndTime() != null) sub.setEndTime(req.getEndTime());
            NullSafe.ref(subtitleRepository.save(sub));
        }
        markModerationStale(video);
        return getByVideoId(videoId);
    }

    @Transactional
    public Subtitle create(Long videoId, SubtitleCreateRequest req) {
        Video video = getEditableVideo(videoId);
        if (Boolean.TRUE.equals(req.getManual())) requirePublishableTiming(video, req);
        Subtitle sub = new Subtitle();
        sub.setVideo(video);
        sub.setText(req.getText() != null ? req.getText() : "");
        sub.setFurigana(req.getFurigana());
        sub.setTranslation(req.getTranslation());
        sub.setStartTime(req.getStartTime() != null ? req.getStartTime() : 0.0);
        sub.setEndTime(req.getEndTime() != null ? req.getEndTime() : 1.0);
        sub.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : getByVideoId(videoId).size());
        sub.setAiStatus(Boolean.TRUE.equals(req.getManual()) ? AiStatus.COMPLETED : AiStatus.PENDING);
        Subtitle saved = NullSafe.ref(subtitleRepository.save(sub));
        markModerationStale(video);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Subtitle subtitle = getEditableSubtitle(id);
        Video video = subtitle.getVideo();

        // A blank subtitle is created only by the manual "add at playhead"
        // workflow. Deleting it should undo the split instead of leaving a
        // dangling time range: extend the segment before it, or restore the
        // first real segment when the blank one was inserted at time 0.
        if (subtitle.getText() == null || subtitle.getText().isBlank()) {
            List<Subtitle> siblings = getByVideoId(video.getId());
            int index = siblings.stream().map(Subtitle::getId).toList().indexOf(subtitle.getId());
            if (index > 0) {
                Subtitle previous = siblings.get(index - 1);
                if (sameTime(previous.getEndTime(), subtitle.getStartTime())) {
                    previous.setEndTime(subtitle.getEndTime());
                    subtitleRepository.save(previous);
                }
            } else if (index == 0 && siblings.size() > 1) {
                Subtitle next = siblings.get(1);
                if (sameTime(next.getStartTime(), subtitle.getEndTime())) {
                    next.setStartTime(subtitle.getStartTime());
                    subtitleRepository.save(next);
                }
            }
        }
        subtitleRepository.delete(subtitle);
        markModerationStale(video);
    }

    /**
     * A line added by hand must not create an error that later blocks publishing (see {@link #validate}):
     * it needs text, a positive length inside the video, and no overlap with another line.
     * Undo restores old lines through the same endpoint without {@code manual}, so it is not checked here.
     */
    private void requirePublishableTiming(Video video, SubtitleCreateRequest req) {
        Double start = req.getStartTime();
        Double end = req.getEndTime();
        if (req.getText() == null || req.getText().isBlank())
            throw new ApiException(ErrorCode.BAD_REQUEST, "Hãy nhập nội dung tiếng Nhật cho câu mới.");
        if (start == null || end == null || start < 0 || end - start < 0.3)
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Thời gian không hợp lệ: kết thúc phải sau bắt đầu ít nhất 0,3 giây.");
        if (video.getDuration() != null && video.getDuration() > 0 && end > video.getDuration() + 0.05)
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thời gian kết thúc vượt quá độ dài video.");
        List<Subtitle> existing = getByVideoId(video.getId());
        for (int i = 0; i < existing.size(); i++) {
            Subtitle other = existing.get(i);
            if (other.getStartTime() == null || other.getEndTime() == null) continue;
            if (start < other.getEndTime() - 0.15 && end > other.getStartTime() + 0.15)
                throw new ApiException(ErrorCode.CONFLICT, "Câu mới chồng thời gian với câu #" + (i + 1)
                        + ". Hãy chọn khoảng trống khác.");
        }
    }

    private boolean sameTime(Double left, Double right) {
        return left != null && right != null && Math.abs(left - right) < 0.0001d;
    }

    @Transactional
    public Subtitle regenerate(Long id, String overrideText) {
        Subtitle sub = getEditableSubtitle(id);
        String text = overrideText != null && !overrideText.isBlank() ? overrideText : sub.getText();
        String cleanText = SubtitleText.normalizeJapanese(text);
        String lang = sub.getVideo().getTargetLanguage() != null ? sub.getVideo().getTargetLanguage() : "vi";
        Map<String, String> processed = openRouterService.regenerateJapanese(cleanText, lang);
        sub.setText(cleanText);
        sub.setFurigana(processed.get("furigana"));
        sub.setTranslation(processed.get("translation"));
        sub.setAiStatus(AiStatus.COMPLETED);
        Subtitle saved = NullSafe.ref(subtitleRepository.save(sub));
        markModerationStale(sub.getVideo());
        return saved;
    }

    @Transactional
    public List<Subtitle> split(Long id, SplitSubtitleRequest req) {
        Subtitle original = getEditableSubtitle(id);
        double splitTime = req.getSplitAtTime() != null ? req.getSplitAtTime()
                : (original.getStartTime() + original.getEndTime()) / 2;

        Subtitle first = new Subtitle();
        first.setVideo(original.getVideo());
        first.setText(req.getText1() != null ? req.getText1() : original.getText());
        first.setStartTime(original.getStartTime());
        first.setEndTime(splitTime);
        first.setAiStatus(AiStatus.PENDING);

        Subtitle second = new Subtitle();
        second.setVideo(original.getVideo());
        second.setText(req.getText2() != null ? req.getText2() : "");
        second.setStartTime(splitTime);
        second.setEndTime(original.getEndTime());
        second.setAiStatus(AiStatus.PENDING);

        subtitleRepository.delete(original);
        subtitleRepository.save(first);
        subtitleRepository.save(second);
        markModerationStale(original.getVideo());
        return getByVideoId(original.getVideo().getId());
    }

    @Transactional
    public List<Subtitle> merge(MergeSubtitleRequest req) {
        Subtitle s1 = getEditableSubtitle(req.getId1());
        Subtitle s2 = getEditableSubtitle(req.getId2());
        if (Objects.equals(s1.getId(), s2.getId())
                || !Objects.equals(s1.getVideo().getId(), s2.getVideo().getId())) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Chỉ có thể ghép hai câu phụ đề khác nhau trong cùng một video");
        }

        Subtitle merged = new Subtitle();
        merged.setVideo(s1.getVideo());
        merged.setText(s1.getText() + s2.getText());
        merged.setFurigana((s1.getFurigana() != null ? s1.getFurigana() : "") + (s2.getFurigana() != null ? s2.getFurigana() : ""));
        merged.setTranslation((s1.getTranslation() != null ? s1.getTranslation() : "") + " " + (s2.getTranslation() != null ? s2.getTranslation() : ""));
        merged.setStartTime(Math.min(s1.getStartTime(), s2.getStartTime()));
        merged.setEndTime(Math.max(s1.getEndTime(), s2.getEndTime()));
        merged.setAiStatus(AiStatus.PENDING);

        subtitleRepository.delete(s1);
        subtitleRepository.delete(s2);
        subtitleRepository.save(merged);
        markModerationStale(s1.getVideo());
        return getByVideoId(s1.getVideo().getId());
    }

    @Transactional
    public List<Subtitle> reorder(Long videoId, List<Long> order) {
        Video video = getEditableVideo(videoId);
        List<Subtitle> subs = getByVideoId(videoId);
        Map<Long, Subtitle> map = new HashMap<>();
        subs.forEach(s -> map.put(s.getId(), s));

        // `subtitle` has a unique (video_id, sort_order) constraint. Moving #2
        // before #1 cannot be persisted as direct updates because the first
        // update temporarily duplicates an existing order. Reserve a positive
        // temporary range (the database column is INT UNSIGNED), flush it, then
        // write the requested final order.
        int highestCurrentOrder = subs.stream()
                .map(Subtitle::getSortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0);
        long temporaryStart = Math.max(1_000_000L, (long) highestCurrentOrder + subs.size() + 1L);
        if (temporaryStart + subs.size() > Integer.MAX_VALUE) {
            throw new IllegalStateException("Không thể sắp xếp lại phụ đề do thứ tự hiện tại không hợp lệ.");
        }
        for (int i = 0; i < subs.size(); i++) {
            subs.get(i).setSortOrder((int) temporaryStart + i);
        }
        subtitleRepository.saveAllAndFlush(subs);

        List<Subtitle> finalOrder = new ArrayList<>();
        if (order != null) {
            for (Long subtitleId : order) {
                Subtitle subtitle = map.remove(subtitleId);
                if (subtitle != null) finalOrder.add(subtitle);
            }
        }
        // Keep any omitted rows rather than silently losing their ordering.
        finalOrder.addAll(map.values());
        for (int i = 0; i < finalOrder.size(); i++) {
            finalOrder.get(i).setSortOrder(i);
        }
        subtitleRepository.saveAll(finalOrder);
        markModerationStale(video);
        return subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId);
    }

    public void regenerateAllSubtitles(Long videoId) {
        getEditableVideo(videoId);
        processingJobService.regenerateTranscript(videoId);
    }

    private Video getEditableVideo(Long videoId) {
        Video video = videoRepository.findById(NullSafe.longId(videoId)).orElseThrow();
        VideoOwnership.requireOwner(video);
        return video;
    }

    private Subtitle getEditableSubtitle(Long id) {
        Subtitle subtitle = subtitleRepository.findById(NullSafe.longId(id)).orElseThrow();
        VideoOwnership.requireOwner(subtitle.getVideo());
        attachRuntimeFurigana(subtitle);
        return subtitle;
    }

    private void attachRuntimeFurigana(Subtitle subtitle) {
        if (subtitle.getFurigana() == null || subtitle.getFurigana().isBlank()) {
            subtitle.setFurigana(buildRuntimeFurigana(subtitle.getText()));
        }
    }

    private void markModerationStale(Video video) {
        if (video == null) return;
        video.setModerationStatus("NOT_SCANNED");
        video.setModerationSummary(null);
        video.setModerationCheckedAt(null);
        videoRepository.save(video);
    }

    private String buildRuntimeFurigana(String text) {
        if (text == null || text.isBlank()) return text;
        List<VocabularyEntry> entries = vocabularyEntryRepository.findContainedIn(text);
        if (entries.isEmpty()) return text;
        StringBuilder result = new StringBuilder(text.length() * 2);
        for (int offset = 0; offset < text.length();) {
            VocabularyEntry match = null;
            for (VocabularyEntry entry : entries) {
                String expression = entry.getExpression();
                if (expression != null && !expression.isEmpty() && text.startsWith(expression, offset)) {
                    match = entry;
                    break;
                }
            }
            if (match == null) {
                int cp = text.codePointAt(offset);
                result.appendCodePoint(cp);
                offset += Character.charCount(cp);
            } else {
                result.append(match.getExpression());
                if (match.getReading() != null && !match.getReading().equals(match.getExpression())) {
                    result.append('(').append(match.getReading()).append(')');
                }
                offset += match.getExpression().length();
            }
        }
        return result.toString();
    }

    /**
     * Only hard errors block publish. Warnings (gaps, missing furigana/translation) never block.
     * Natural podcast pauses of several seconds are normal — only flag huge gaps.
     */
    public Map<String, Object> validate(Long videoId) {
        List<Subtitle> subs = getByVideoId(videoId);
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (subs.isEmpty()) {
            errors.add("Chưa có câu phụ đề nào");
        }

        int pendingQualityReviews = subtitleQualityReviewService.countNeedsReview(videoId);
        if (pendingQualityReviews > 0) {
            errors.add("Còn " + pendingQualityReviews
                    + " câu phụ đề cần xác nhận theo audio trước khi xuất bản");
        }

        String transcript = subs.stream()
                .map(Subtitle::getText)
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
        JapaneseLanguageDetector.Result language = JapaneseLanguageDetector.inspect(transcript);
        if (language.nonJapanese()) {
            errors.add("Nội dung phụ đề không được nhận diện là tiếng Nhật: " + language.reason());
        }

        for (Subtitle sub : subs) {
            if (sub.getText() == null || sub.getText().isBlank()) {
                errors.add("Câu #" + sub.getId() + " thiếu nội dung");
            }
            if (sub.getStartTime() != null && sub.getEndTime() != null && sub.getStartTime() >= sub.getEndTime()) {
                errors.add("Câu #" + sub.getId() + " thời gian không hợp lệ");
            }
            // Soft tips only — never block publish (furigana/translation optional after YT import)
            if (sub.getFurigana() == null || sub.getFurigana().isBlank()) {
                // not warned: plain JP text is used as furigana in player fallback
            }
            if (sub.getTranslation() == null || sub.getTranslation().isBlank()) {
                warnings.add("Câu #" + (subs.indexOf(sub) + 1) + " chưa có bản dịch");
            }
        }

        addRecheckHint(warnings, subs, SubtitleText::hasLatinInJapanese, "có chữ Latin, có thể là lỗi nhận dạng");
        addRecheckHint(warnings, subs, SubtitleText::hasRepeatedRun, "có chuỗi ký tự lặp (tiếng hét hoặc lỗi nhận dạng)");

        // Only extreme gaps (broken timeline). 3–8s pauses in speech are normal.
        final double gapWarnSeconds = 20.0;
        for (int i = 1; i < subs.size(); i++) {
            Double prevEnd = subs.get(i - 1).getEndTime();
            Double nextStart = subs.get(i).getStartTime();
            if (prevEnd == null || nextStart == null) continue;
            double gap = nextStart - prevEnd;
            if (gap > gapWarnSeconds) {
                warnings.add("Khoảng trống lớn " + String.format("%.1f", gap)
                        + "s giữa câu " + i + " và " + (i + 1) + " (gợi ý, không chặn xuất bản)");
            }
            if (gap < -0.15) {
                errors.add("Câu " + (i + 1) + " bắt đầu trước khi câu " + i + " kết thúc (chồng thời gian)");
            }
        }

        Map<String, Object> result = new HashMap<>();
        // valid = no hard errors. Warnings must never make valid=false.
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("canPublish", errors.isEmpty());
        result.put("pendingQualityReviews", pendingQualityReviews);
        return result;
    }

    /** Advisory only: names the lines a creator may want to re-listen to; never blocks publish. */
    private static void addRecheckHint(List<String> warnings, List<Subtitle> subs,
                                       java.util.function.Predicate<String> suspicious, String reason) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < subs.size(); i++) {
            if (suspicious.test(subs.get(i).getText())) lines.add("#" + (i + 1));
        }
        if (lines.isEmpty()) return;
        int shown = Math.min(lines.size(), 15);
        warnings.add(lines.size() + " câu " + reason + ", nên nghe và kiểm tra lại: "
                + String.join(", ", lines.subList(0, shown)) + (lines.size() > shown ? ", …" : ""));
    }
}
