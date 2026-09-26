package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.auth.security.CurrentUser;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.model.ModerationVerdict;
import com.jlas.common.event.LearningEvent;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.moderation.service.UserActionPolicyPort;
import com.jlas.shadowing.dto.*;
import com.jlas.shadowing.model.*;
import com.jlas.shadowing.repository.CommentRepository;
import com.jlas.shadowing.repository.SubtitleRepository;
import com.jlas.shadowing.repository.TagRepository;
import com.jlas.shadowing.repository.VideoFavoriteRepository;
import com.jlas.shadowing.repository.VideoRatingRepository;
import com.jlas.shadowing.repository.VideoRepository;
import com.jlas.shadowing.repository.VideoWatchSessionRepository;
import com.jlas.shadowing.util.NullSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VideoService {

    @Autowired private VideoRepository videoRepository;
    @Autowired private SubtitleRepository subtitleRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private ProcessingJobService processingJobService;
    @Autowired private SubtitleService subtitleService;
    @Autowired private FfmpegService ffmpegService;
    @Autowired private InteractionService interactionService;
    @Autowired private CommentRepository commentRepository;
    @Autowired private VideoRatingRepository ratingRepository;
    @Autowired private VideoFavoriteRepository favoriteRepository;
    @Autowired private VideoWatchSessionRepository watchSessionRepository;
    @Autowired private YoutubeImportService youtubeImportService;
    @Autowired private ContentModerationPort moderationPort;
    @Autowired private VideoModerationService videoModerationService;
    @Autowired private UserActionPolicyPort actionPolicy;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @Value("${upload.path.video}")
    private String videoDir;

    @Value("${upload.path.audio}")
    private String audioDir;

    @Value("${upload.path.thumbnail}")
    private String thumbnailDir;

    /** General cap when AI is OFF. 0 = unlimited. */
    @Value("${video.max.duration.seconds:0}")
    private int maxDurationSeconds;

    /** Cap when generateFurigana (AI furigana + translate) is ON — all upload paths. */
    @Value("${processing.ai.max.duration.seconds:1200}")
    private int aiMaxDurationSeconds;

    private static final Set<String> AUDIO_EXTENSIONS = Set.of(
            ".mp3", ".wav", ".m4a", ".ogg", ".flac", ".aac", ".opus"
    );
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mov", ".webm", ".mkv", ".avi", ".m4v"
    );

    @Transactional
    public Video uploadVideo(MultipartFile file, MultipartFile thumbnail, String title,
                             String category, List<String> tagNames, String targetLanguage) throws Exception {
        return uploadVideoInternal(file, thumbnail, title, category, tagNames, targetLanguage, false);
    }

    @Transactional
    public Video uploadVideo(MultipartFile file, MultipartFile thumbnail, String title,
                             String category, List<String> tagNames, String targetLanguage,
                             Boolean generateFurigana) throws Exception {
        return uploadVideoInternal(file, thumbnail, title, category, tagNames, targetLanguage, generateFurigana);
    }

    private Video uploadVideoInternal(MultipartFile file, MultipartFile thumbnail, String title,
                             String category, List<String> tagNames, String targetLanguage,
                             Boolean generateFurigana) throws Exception {
        actionPolicy.assertCanAct(CurrentUser.requireUserId());
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn file video hoặc âm thanh.");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = resolveExtension(originalFilename, file.getContentType());
        boolean isAudio = isAudioUpload(extension, file.getContentType());
        boolean isVideo = !isAudio && isVideoUpload(extension, file.getContentType());
        if (!isAudio && !isVideo) {
            throw new IllegalArgumentException(
                    "Định dạng không hỗ trợ. Video: MP4, MOV, WebM. Audio: MP3, WAV, M4A, OGG, FLAC, AAC.");
        }

        File uploadDir = new File(isAudio ? audioDir : videoDir);
        if (!uploadDir.exists()) uploadDir.mkdirs();

        String fileName = UUID.randomUUID().toString() + extension;
        File targetFile = new File(uploadDir.getAbsolutePath(), fileName);
        file.transferTo(targetFile);

        String mediaLabel = isAudio ? "file âm thanh" : "video";
        double duration = ffmpegService.getDuration(targetFile.getAbsolutePath());
        if (duration <= 0) {
            targetFile.delete();
            throw new IllegalArgumentException("Không đọc được độ dài " + mediaLabel + ".");
        }
        boolean wantAi = Boolean.TRUE.equals(generateFurigana);
        if (wantAi && duration > aiMaxDurationSeconds) {
            targetFile.delete();
            int maxMin = Math.max(1, aiMaxDurationSeconds / 60);
            throw new IllegalArgumentException(
                    (isAudio ? "Audio" : "Video") + " quá dài khi bật furigana/dịch AI. Tối đa "
                            + maxMin + " phút. Tắt AI để import file dài hơn.");
        }
        if (!wantAi && maxDurationSeconds > 0 && duration > maxDurationSeconds) {
            targetFile.delete();
            int maxMin = Math.max(1, maxDurationSeconds / 60);
            throw new IllegalArgumentException(
                    (isAudio ? "Audio" : "Video") + " quá dài. Tối đa " + maxMin + " phút.");
        }

        Video video = new Video();
        video.setTitle(title != null && !title.isBlank() ? title : originalFilename);
        video.setVideoPath(targetFile.getAbsolutePath());
        video.setDuration(duration);
        video.setStatus(VideoStatus.PROCESSING);
        video.setProcessingStep(ProcessingStep.UPLOADED);
        video.setProgressPercent(5);
        video.setTargetLanguage(targetLanguage != null ? targetLanguage : "vi");
        video.setGenerateFurigana(wantAi);
        // Owner + default private (owner can switch to PUBLIC when ready)
        Long ownerId = CurrentUser.currentUserId().orElse(null);
        video.setOwnerId(ownerId);
        video.setVisibility(VideoVisibility.PRIVATE);
        if (category != null && !category.isBlank()) {
            video.setTags(resolveCategory(category));
        } else if (tagNames != null && !tagNames.isEmpty()) {
            video.setTags(resolveTags(tagNames));
        } else {
            video.setTags(resolveCategory(CategoryConstants.DEFAULT));
        }
        if (thumbnail != null && !thumbnail.isEmpty()) {
            video.setThumbnailPath(saveThumbnail(thumbnail));
        }
        video = videoRepository.save(video);

        Long videoId = video.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    processingJobService.processVideo(videoId);
                }
            });
        } else {
            // Fallback if called outside a transaction (should not happen with @Transactional)
            processingJobService.processVideo(videoId);
        }
        return video;
    }

    private static String resolveExtension(String originalFilename, String contentType) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        }
        if (contentType != null) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("mpeg") || ct.contains("mp3")) return ".mp3";
            if (ct.contains("wav")) return ".wav";
            if (ct.contains("ogg")) return ".ogg";
            if (ct.contains("flac")) return ".flac";
            if (ct.contains("aac") || ct.contains("m4a") || ct.equals("audio/mp4")) return ".m4a";
            if (ct.contains("webm")) return ".webm";
            if (ct.contains("quicktime")) return ".mov";
            if (ct.contains("mp4")) return ".mp4";
        }
        return ".mp4";
    }

    private static boolean isAudioUpload(String extension, String contentType) {
        if (AUDIO_EXTENSIONS.contains(extension)) return true;
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("audio/");
    }

    private static boolean isVideoUpload(String extension, String contentType) {
        if (VIDEO_EXTENSIONS.contains(extension)) return true;
        return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("video/");
    }

    public VideoStatusDto getStatus(Long id) {
        // Progress polling for processing page — only exposes status fields, not media/subtitles
        Video video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        VideoStatusDto dto = new VideoStatusDto();
        dto.setId(video.getId());
        dto.setTitle(video.getTitle());
        dto.setStatus(video.getStatus());
        dto.setProcessingStep(video.getProcessingStep());
        dto.setProgressPercent(video.getProgressPercent());
        dto.setErrorMessage(video.getErrorMessage());
        return dto;
    }

    public List<Subtitle> getSubtitles(Long videoId) {
        return subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(videoId);
    }

    public List<VideoSummaryDto> getAllSummaries(String clientId) {
        List<Video> videos = videoRepository.findByStatusInOrderByCreatedAtDesc(
                List.of(VideoStatus.READY, VideoStatus.DRAFT, VideoStatus.PROCESSING, VideoStatus.FAILED));
        return mapSummaries(videos, clientId);
    }

    public List<VideoSummaryDto> getLibrary(String clientId) {
        List<Video> videos = videoRepository.findByStatusInOrderByCreatedAtDesc(
                List.of(VideoStatus.READY, VideoStatus.DRAFT, VideoStatus.PROCESSING));
        return mapSummaries(videos, clientId);
    }

    private List<VideoSummaryDto> mapSummaries(List<Video> videos, String clientId) {
        Set<Long> favoriteIds = interactionService.getFavoriteVideoIds(clientId);
        Map<Long, Integer> userRatings = loadUserRatings(clientId, videos);
        return videos.stream()
                .map(v -> toSummary(v, clientId, favoriteIds, userRatings))
                .collect(Collectors.toList());
    }

    /**
     * @param scope public | my | all (legacy). Default public.
     */
    public List<VideoSummaryDto> search(String query, String tag, String category, String clientId,
                                        String sort, Boolean favoritesOnly, String scope) {
        String sc = scope == null || scope.isBlank() ? "public" : scope.trim().toLowerCase(Locale.ROOT);
        Long me = CurrentUser.currentUserId().orElse(null);
        String catFilter = category != null && !category.isBlank() ? category : tag;
        List<Video> videos;

        if ("my".equals(sc)) {
            if (me == null) {
                return List.of();
            }
            List<VideoStatus> mineStatuses = List.of(
                    VideoStatus.READY, VideoStatus.DRAFT, VideoStatus.PROCESSING, VideoStatus.FAILED);
            videos = videoRepository.findByOwnerIdAndStatusInOrderByCreatedAtDesc(me, mineStatuses);
            if (catFilter != null && !catFilter.isBlank()) {
                String cat = catFilter;
                videos = videos.stream()
                        .filter(v -> v.getTags() != null && v.getTags().stream()
                                .anyMatch(t -> t.getName() != null && t.getName().equalsIgnoreCase(cat)))
                        .collect(Collectors.toList());
            }
            if (query != null && !query.isBlank()) {
                String q = query.toLowerCase(Locale.ROOT);
                videos = videos.stream()
                        .filter(v -> v.getTitle() != null && v.getTitle().toLowerCase(Locale.ROOT).contains(q))
                        .collect(Collectors.toList());
            }
        } else if ("all".equals(sc)) {
            // Legacy: all statuses (admin-ish) — still filter private of others
            List<VideoStatus> statuses = List.of(
                    VideoStatus.READY, VideoStatus.DRAFT, VideoStatus.PROCESSING, VideoStatus.FAILED);
            if (catFilter != null && !catFilter.isBlank()) {
                videos = videoRepository.findByTag(catFilter, statuses);
            } else if (query != null && !query.isBlank()) {
                videos = videoRepository.search(query, statuses);
            } else {
                videos = videoRepository.findByStatusInOrderByCreatedAtDesc(statuses);
            }
            Long userId = me;
            videos = videos.stream()
                    .filter(v -> isPublicReady(v) || isOwner(v, userId))
                    .collect(Collectors.toList());
        } else {
            // public: READY + PUBLIC (or null visibility legacy)
            if (catFilter != null && !catFilter.isBlank()) {
                videos = videoRepository.findPublicByTag(catFilter);
            } else if (query != null && !query.isBlank()) {
                LinkedHashMap<Long, Video> matches = new LinkedHashMap<>();
                for (String term : query.trim().split("\\s+")) {
                    if (term.length() >= 2) {
                        videoRepository.searchPublic(term).forEach(v -> matches.putIfAbsent(v.getId(), v));
                    }
                }
                videos = matches.isEmpty() ? videoRepository.searchPublic(query) : new ArrayList<>(matches.values());
            } else {
                videos = videoRepository.findAllPublicReady();
            }
        }

        Set<Long> favoriteIds = interactionService.getFavoriteVideoIds(clientId);

        if (Boolean.TRUE.equals(favoritesOnly)) {
            videos = videos.stream().filter(v -> favoriteIds.contains(v.getId())).collect(Collectors.toList());
        }

        Map<Long, Integer> userRatings = loadUserRatings(clientId, videos);
        Long currentUser = me;
        List<VideoSummaryDto> summaries = videos.stream()
                .map(v -> toSummary(v, clientId, favoriteIds, userRatings, currentUser))
                .collect(Collectors.toList());
        return sortSummaries(summaries, sort);
    }

    private static boolean isPublicReady(Video v) {
        if (v.getStatus() != VideoStatus.READY) return false;
        if (v.getVisibility() != null && v.getVisibility() != VideoVisibility.PUBLIC) return false;
        // Ẩn nội dung REVIEW / BLOCKED khỏi feed công khai
        String ms = v.getModerationStatus();
        if (ms != null) {
            String u = ms.toUpperCase(Locale.ROOT);
            if ("REVIEW".equals(u) || "BLOCKED".equals(u)) return false;
        }
        return true;
    }

    private static boolean isOwner(Video v, Long userId) {
        return userId != null && userId.equals(v.getOwnerId());
    }

    public void requireOwner(Video video) {
        Long me = CurrentUser.requireUserId();
        if (video.getOwnerId() != null && !me.equals(video.getOwnerId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Chỉ chủ sở hữu mới được sửa hoặc xóa video này");
        }
    }

    public void requireReadable(Video video) {
        Long me = CurrentUser.currentUserId().orElse(null);
        // Public library items, or owner (draft/private/processing)
        if (isPublicReady(video) || isOwner(video, me)) {
            return;
        }
        // Legacy rows without owner: any logged-in user (matches requireOwner null-owner rule)
        if (video.getOwnerId() == null && me != null) {
            return;
        }
        throw new ApiException(ErrorCode.FORBIDDEN, "Video này là riêng tư hoặc chưa xuất bản");
    }

    public List<String> getCategories() {
        return CategoryConstants.ALL;
    }

    private List<VideoSummaryDto> sortSummaries(List<VideoSummaryDto> summaries, String sort) {
        if (sort == null || sort.isBlank() || "newest".equals(sort)) {
            return summaries.stream()
                    .sorted(Comparator.comparing(
                            (VideoSummaryDto v) -> v.getCreatedAt(),
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        }
        if ("title".equals(sort)) {
            return summaries.stream()
                    .sorted(Comparator.comparing(v -> v.getTitle() != null ? v.getTitle().toLowerCase() : ""))
                    .collect(Collectors.toList());
        }
        if ("rating".equals(sort)) {
            return summaries.stream()
                    .sorted(Comparator.comparingDouble((VideoSummaryDto v) ->
                            v.getAvgRating() != null ? v.getAvgRating() : 0).reversed())
                    .collect(Collectors.toList());
        }
        if ("views".equals(sort)) {
            return summaries.stream()
                    .sorted(Comparator.<VideoSummaryDto>comparingInt(VideoSummaryDto::getViewCount).reversed())
                    .collect(Collectors.toList());
        }
        return summaries;
    }

    public Video getVideo(Long id) {
        Video video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        requireReadable(video);
        return video;
    }

    /** Summary used by public playlist/detail pages. */
    public VideoSummaryDto getSummary(Long id, String clientId) {
        Video video = getVideo(id);
        Set<Long> favoriteIds = interactionService.getFavoriteVideoIds(clientId);
        Map<Long, Integer> userRatings = loadUserRatings(clientId, List.of(video));
        return toSummary(video, clientId, favoriteIds, userRatings,
                CurrentUser.currentUserId().orElse(null));
    }

    /** Load without permission (internal processing). */
    public Video getVideoInternal(Long id) {
        return videoRepository.findById(NullSafe.longId(id)).orElseThrow();
    }

    @Transactional
    public Video updateVideo(Long id, VideoUpdateRequest req) {
        Video video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        requireOwner(video);
        if (req.getTitle() != null) {
            video.setTitle(req.getTitle());
            video.setModerationStatus("NOT_SCANNED");
            video.setModerationSummary(null);
            video.setModerationCheckedAt(null);
        }
        if (req.getCategory() != null && !req.getCategory().isBlank()) {
            video.setTags(resolveCategory(req.getCategory()));
        } else if (req.getTags() != null) {
            video.setTags(resolveTags(req.getTags()));
        }
        if (req.getVisibility() != null && !req.getVisibility().isBlank()) {
            try {
                video.setVisibility(VideoVisibility.valueOf(req.getVisibility().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // keep current
            }
        }
        return NullSafe.ref(videoRepository.save(video));
    }

    @Transactional
    public Video publish(Long id) {
        Video video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        requireOwner(video);
        actionPolicy.assertCanAct(CurrentUser.requireUserId());
        boolean wasPublic = video.getVisibility() == VideoVisibility.PUBLIC;
        Map<String, Object> validation = subtitleService.validate(id);
        // Only hard errors block publish — warnings (gaps, missing translation) are ignored here
        Object validObj = validation.get("valid");
        boolean ok = validObj instanceof Boolean b ? b : Boolean.TRUE.equals(validObj);
        if (!ok) {
            throw new IllegalStateException("Validation failed: " + validation.get("errors"));
        }
        video.setStatus(VideoStatus.READY);

        Long me = CurrentUser.currentUserId().orElse(null);
        if (me != null) {
            moderationPort.checkPublish(String.valueOf(me));
        }

        // Full pipeline (text + quick/targeted frames) — re-run if never scanned or stale
        String ms = video.getModerationStatus();
        boolean needScan = ms == null || ms.isBlank()
                || "NOT_SCANNED".equalsIgnoreCase(ms)
                || "ERROR".equalsIgnoreCase(ms)
                // Re-check BLOCKED so older false-positive decisions do not remain permanent.
                || "BLOCKED".equalsIgnoreCase(ms);
        ModerationResult mod;
        if (needScan) {
            mod = videoModerationService.moderateVideo(id);
            video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        } else {
            try {
                mod = ModerationResult.builder()
                        .verdict(ModerationVerdict.valueOf(ms.toUpperCase(Locale.ROOT)))
                        .summary(video.getModerationSummary())
                        .confidence(0.9)
                        .categories(List.of())
                        .modelName("cached")
                        .build();
            } catch (Exception e) {
                mod = videoModerationService.moderateVideo(id);
                video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
            }
        }

        if (mod.isBlocked() || "BLOCKED".equalsIgnoreCase(video.getModerationStatus())) {
            video.setVisibility(VideoVisibility.PRIVATE);
            videoRepository.save(video);
            throw new ApiException(ErrorCode.CONTENT_BLOCKED,
                    mod.getSummary() != null ? mod.getSummary()
                            : "Nội dung không phù hợp — không thể công khai.");
        }
        if (mod.needsReview() || "REVIEW".equalsIgnoreCase(video.getModerationStatus())
                || mod.getVerdict() == ModerationVerdict.ERROR) {
            video.setVisibility(VideoVisibility.PRIVATE);
            video.setModerationStatus(ModerationVerdict.REVIEW.name());
            Long moderationLogId = moderationPort.persistLog(
                    "VIDEO", id, video.getOwnerId(), "PUBLISH",
                    video.getModerationSummary(), mod);
            moderationPort.enqueueReview(moderationLogId, "VIDEO", id, video.getOwnerId(),
                    video.getTitle(), mod.getSummary());
            return NullSafe.ref(videoRepository.save(video));
        }

        // SAFE only
        video.setVisibility(VideoVisibility.PUBLIC);
        video.setModerationStatus(ModerationVerdict.SAFE.name());
        Video published = NullSafe.ref(videoRepository.save(video));
        if (!wasPublic && published.getOwnerId() != null) {
            eventPublisher.publishEvent(LearningEvent.shadowingPublished(
                    published.getOwnerId(), published.getId()));
        }
        return published;
    }

    @Transactional
    public void deleteVideo(Long id) {
        Video video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        requireOwner(video);
        subtitleRepository.deleteByVideoId(id);
        commentRepository.deleteByVideoId(id);
        favoriteRepository.deleteByVideoId(id);
        ratingRepository.deleteByVideoId(id);
        watchSessionRepository.deleteByVideoId(id);
        video.getTags().clear();
        deleteFileIfExists(video.getVideoPath());
        deleteFileIfExists(video.getThumbnailPath());
        videoRepository.delete(video);
    }

    public void retryProcessing(Long id) {
        Video video = videoRepository.findById(NullSafe.longId(id)).orElseThrow();
        requireOwner(video);
        if (video.getSourceUrl() != null && !video.getSourceUrl().isBlank()) {
            youtubeImportService.retryImport(id);
        } else {
            processingJobService.retryProcessing(id);
        }
    }

    private VideoSummaryDto toSummary(Video v, String clientId, Set<Long> favoriteIds,
                                      Map<Long, Integer> userRatings) {
        return toSummary(v, clientId, favoriteIds, userRatings, CurrentUser.currentUserId().orElse(null));
    }

    private VideoSummaryDto toSummary(Video v, String clientId, Set<Long> favoriteIds,
                                      Map<Long, Integer> userRatings, Long currentUserId) {
        VideoSummaryDto dto = new VideoSummaryDto();
        dto.setId(v.getId());
        dto.setTitle(v.getTitle());
        dto.setStatus(v.getStatus());
        dto.setThumbnailPath(v.getThumbnailPath());
        dto.setDuration(v.getDuration());
        dto.setProgressPercent(v.getProgressPercent());
        dto.setCreatedAt(v.getCreatedAt());
        dto.setTags(v.getTags().stream().map(tag -> tag.getName()).collect(Collectors.toList()));
        dto.setCategory(extractCategory(v));
        v.getTags().stream().map(Tag::getName).filter(Objects::nonNull)
                .map(String::trim).filter(name -> name.matches("(?i)N[1-5]"))
                .findFirst().ifPresent(level -> {
                    dto.setEstimatedLevel(level.toUpperCase(Locale.ROOT));
                    dto.setLevelSource("CREATOR");
                });
        dto.setSubtitleCount(subtitleRepository.findByVideoIdOrderBySortOrderAscStartTimeAsc(v.getId()).size());
        dto.setOwnerId(v.getOwnerId());
        dto.setVisibility(v.getVisibility() != null ? v.getVisibility().name() : VideoVisibility.PRIVATE.name());
        dto.setOwnedByMe(currentUserId != null && currentUserId.equals(v.getOwnerId()));
        dto.setViewCount((int) watchSessionRepository.countViewsByVideoId(v.getId()));
        dto.setAvgRating(v.getAvgRating());
        dto.setRatingCount(v.getRatingCount() != null ? v.getRatingCount() : 0);
        dto.setCommentCount(commentRepository.countByVideoId(v.getId()));
        if (clientId != null) {
            dto.setFavorited(favoriteIds.contains(v.getId()));
            dto.setUserRating(userRatings.get(v.getId()));
        }
        return dto;
    }

    private Map<Long, Integer> loadUserRatings(String clientId, List<Video> videos) {
        if (clientId == null || clientId.isBlank()) return Map.of();
        Map<Long, Integer> map = new HashMap<>();
        for (Video v : videos) {
            ratingRepository.findByClientIdAndVideoId(clientId, v.getId())
                    .ifPresent(r -> map.put(v.getId(), r.getRating()));
        }
        return map;
    }

    private String extractCategory(Video v) {
        return v.getTags().stream()
                .map(tag -> tag.getName())
                .filter(CategoryConstants::isValid)
                .findFirst()
                .orElse(CategoryConstants.DEFAULT);
    }

    private List<Tag> resolveCategory(String category) {
        return resolveTags(List.of(CategoryConstants.normalize(category)));
    }

    private List<Tag> resolveTags(List<String> tagNames) {
        if (tagNames == null) return new ArrayList<>();
        List<Tag> tags = new ArrayList<>();
        for (String name : tagNames) {
            if (name == null || name.isBlank()) continue;
            String trimmed = name.trim();
            Tag tag = tagRepository.findByNameAndTagType(trimmed, Tag.TagType.VIDEO).orElseGet(() -> {
                Tag t = new Tag();
                t.setName(trimmed);
                t.setCode("VIDEO_" + Integer.toUnsignedString(trimmed.hashCode(), 36).toUpperCase(Locale.ROOT));
                t.setTagType(Tag.TagType.VIDEO);
                return tagRepository.save(t);
            });
            tags.add(tag);
        }
        return tags;
    }

    private String saveThumbnail(MultipartFile thumbnail) throws Exception {
        String contentType = thumbnail.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Ảnh bìa phải là file ảnh (JPG, PNG, WebP).");
        }
        if (thumbnail.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("Ảnh bìa quá lớn (tối đa 5MB).");
        }

        File dir = new File(thumbnailDir);
        if (!dir.exists()) dir.mkdirs();

        String ext = ".jpg";
        if ("image/png".equals(contentType)) ext = ".png";
        else if ("image/webp".equals(contentType)) ext = ".webp";

        String fileName = UUID.randomUUID() + ext;
        File target = new File(dir.getAbsolutePath(), fileName);
        thumbnail.transferTo(target);
        return target.getAbsolutePath();
    }

    private void deleteFileIfExists(String path) {
        if (path != null) {
            File f = new File(path);
            if (f.exists()) f.delete();
        }
    }
}
