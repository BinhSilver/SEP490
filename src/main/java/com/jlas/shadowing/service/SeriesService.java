package com.jlas.shadowing.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.dto.LibraryPageDto;
import com.jlas.shadowing.repository.ShadowingLibraryCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import com.jlas.shadowing.dto.SeriesCreateRequest;
import com.jlas.shadowing.dto.SeriesDetailDto;
import com.jlas.shadowing.dto.SeriesSummaryDto;
import com.jlas.shadowing.dto.VideoSummaryDto;
import com.jlas.shadowing.model.Video;
import com.jlas.shadowing.model.VideoStatus;
import com.jlas.shadowing.model.VideoVisibility;
import com.jlas.shadowing.util.LibraryFilters;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SeriesService {
    private final JdbcTemplate jdbc;
    private final VideoService videoService;
    @Autowired private ShadowingLibraryCatalog libraryCatalog;

    public SeriesService(JdbcTemplate jdbc, VideoService videoService) {
        this.jdbc = jdbc;
        this.videoService = videoService;
    }

    public LibraryPageDto<SeriesSummaryDto> listPublicPage(String query, String sort, String category,
            String level, int page, int size) {
        return listPublicPage(query, sort, category, null, level, page, size);
    }

    public LibraryPageDto<SeriesSummaryDto> listPublicPage(String query, String sort, String category, String genre,
            String level, int page, int size) {
        return libraryCatalog.series(query, category, genre, level, sort, CurrentUser.currentUserId().orElse(null), page, size);
    }

    public List<SeriesSummaryDto> listPublic(String query, String sort) {
        return listPublic(query, sort, null, null);
    }

    public List<SeriesSummaryDto> listPublic(String query, String sort, String category, String level) {
        List<SeriesSummaryDto> rows = jdbc.query(
                "SELECT id, owner_id, title, description, estimated_level, level_source, visibility, created_at "
                        + "FROM shadowing_series WHERE visibility = 'PUBLIC' ORDER BY created_at DESC",
                (rs, n) -> mapRow(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("title"),
                        rs.getString("description"), rs.getString("estimated_level"), rs.getString("level_source"),
                        rs.getString("visibility"), timestamp(rs.getTimestamp("created_at"))));

        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!q.isBlank()) {
            String[] terms = q.split("\\s+");
            rows = rows.stream().filter(s -> {
                String haystack = ((s.getTitle() == null ? "" : s.getTitle()) + " "
                        + (s.getDescription() == null ? "" : s.getDescription()) + " "
                        + (s.getEstimatedLevel() == null ? "" : s.getEstimatedLevel())).toLowerCase(Locale.ROOT);
                return Arrays.stream(terms).anyMatch(haystack::contains);
            }).collect(Collectors.toList());
        }
        rows.forEach(this::enrichStats);
        Set<String> categories = LibraryFilters.values(category);
        Set<String> levels = LibraryFilters.values(level);
        rows = rows.stream().filter(s -> categories.isEmpty() || s.getCategories().stream()
                        .anyMatch(c -> categories.contains(c.toLowerCase(Locale.ROOT))))
                .filter(s -> levels.isEmpty() || (s.getEstimatedLevel() != null
                        && levels.contains(s.getEstimatedLevel().toLowerCase(Locale.ROOT))))
                .collect(Collectors.toList());
        if ("duration-asc".equals(sort) || "duration-desc".equals(sort)) {
            rows.sort(LibraryFilters.duration(SeriesSummaryDto::getDuration, "duration-desc".equals(sort)));
        } else if ("title".equalsIgnoreCase(sort)) {
            rows.sort(Comparator.comparing(s -> Optional.ofNullable(s.getTitle()).orElse(""), String.CASE_INSENSITIVE_ORDER));
        } else if ("views".equalsIgnoreCase(sort)) {
            rows.sort(Comparator.comparingInt(SeriesSummaryDto::getViewCount).reversed());
        } else if ("rating".equalsIgnoreCase(sort)) {
            rows.sort(Comparator.comparingDouble((SeriesSummaryDto s) -> Optional.ofNullable(s.getAvgRating()).orElse(0d)).reversed());
        }
        return rows;
    }

    public SeriesDetailDto get(Long id) {
        SeriesSummaryDto series = find(id);
        Long me = CurrentUser.currentUserId().orElse(null);
        if (!"PUBLIC".equalsIgnoreCase(series.getVisibility())
                && !Objects.equals(me, series.getOwnerId())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Series này đang ở chế độ riêng tư");
        }
        List<VideoSummaryDto> videos = readableVideos(series);
        SeriesDetailDto result = new SeriesDetailDto();
        result.setSeries(series);
        result.setVideos(videos);
        return result;
    }

    @Transactional
    public SeriesDetailDto create(SeriesCreateRequest request) {
        Long ownerId = CurrentUser.requireUserId();
        if (request == null || request.getTitle() == null || request.getTitle().isBlank()) {
            throw new IllegalArgumentException("Series cần có tên");
        }
        List<Long> videoIds = request.getVideoIds() == null ? List.of() : request.getVideoIds().stream()
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (videoIds.isEmpty()) throw new IllegalArgumentException("Hãy chọn ít nhất một video");
        if (videoIds.size() > 100) throw new IllegalArgumentException("Series tối đa 100 video");

        List<Video> videos = new ArrayList<>();
        for (Long videoId : videoIds) {
            Video video = videoService.getVideoInternal(videoId);
            videoService.requireOwner(video);
            if (video.getStatus() != VideoStatus.READY) {
                throw new IllegalArgumentException("Chỉ được thêm video đã xử lý xong");
            }
            videos.add(video);
        }
        String visibility = normalizeVisibility(request.getVisibility());
        if ("PUBLIC".equals(visibility) && videos.stream().anyMatch(v -> !isPublic(v))) {
            throw new IllegalArgumentException("Series công khai chỉ được chứa video công khai");
        }

        String level = blankToNull(request.getEstimatedLevel());
        String source = blankToNull(request.getLevelSource());
        if (source == null && level != null) source = "CREATOR";
        if (source != null) source = source.toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO shadowing_series "
                        + "(owner_id, title, description, estimated_level, level_source, visibility) VALUES (?, ?, ?, ?, ?, ?)",
                ownerId, request.getTitle().trim(), blankToNull(request.getDescription()), level, source, visibility);
        Long seriesId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (int i = 0; i < videoIds.size(); i++) {
            jdbc.update("INSERT INTO shadowing_series_video (series_id, video_id, position) VALUES (?, ?, ?)",
                    seriesId, videoIds.get(i), i + 1);
        }
        return get(seriesId);
    }

    private SeriesSummaryDto find(Long id) {
        List<SeriesSummaryDto> found = jdbc.query(
                "SELECT id, owner_id, title, description, estimated_level, level_source, visibility, created_at "
                        + "FROM shadowing_series WHERE id = ?", (rs, n) -> mapRow(rs.getLong("id"), rs.getLong("owner_id"),
                        rs.getString("title"), rs.getString("description"), rs.getString("estimated_level"),
                        rs.getString("level_source"), rs.getString("visibility"), timestamp(rs.getTimestamp("created_at"))), id);
        if (found.isEmpty()) throw new NoSuchElementException("Không tìm thấy series");
        SeriesSummaryDto result = found.get(0);
        result.setOwnedByMe(CurrentUser.currentUserId().map(result.getOwnerId()::equals).orElse(false));
        enrichStats(result);
        return result;
    }

    private void enrichStats(SeriesSummaryDto series) {
        List<VideoSummaryDto> videos = readableVideos(series);
        series.setVideoCount(videos.size());
        double seconds = videos.stream().map(VideoSummaryDto::getDuration)
                .filter(d -> d != null && Double.isFinite(d) && d > 0).mapToDouble(Double::doubleValue).sum();
        series.setDuration(seconds > 0 ? seconds : null);
        series.setCategories(videos.stream().map(VideoSummaryDto::getCategory).filter(Objects::nonNull)
                .distinct().toList());
        series.setViewCount(videos.stream().mapToInt(VideoSummaryDto::getViewCount).sum());
        int ratings = videos.stream().mapToInt(VideoSummaryDto::getRatingCount).sum();
        series.setRatingCount(ratings);
        if (ratings > 0) {
            double weighted = videos.stream().filter(v -> v.getAvgRating() != null)
                    .mapToDouble(v -> v.getAvgRating() * v.getRatingCount()).sum();
            series.setAvgRating(weighted / ratings);
        }
    }

    private List<VideoSummaryDto> readableVideos(SeriesSummaryDto series) {
        List<Long> ids = jdbc.query(
                "SELECT video_id FROM shadowing_series_video WHERE series_id = ? ORDER BY position ASC",
                (rs, n) -> rs.getLong("video_id"), series.getId());
        // A member may have become private since its public series was created.
        return videoService.getReadableSummaries(ids, null);
    }

    private static SeriesSummaryDto mapRow(long id, long ownerId, String title, String description, String level,
                                           String source, String visibility, LocalDateTime createdAt) {
        SeriesSummaryDto dto = new SeriesSummaryDto();
        dto.setId(id); dto.setOwnerId(ownerId); dto.setTitle(title); dto.setDescription(description);
        dto.setEstimatedLevel(level); dto.setLevelSource(source); dto.setVisibility(visibility); dto.setCreatedAt(createdAt);
        return dto;
    }

    private static LocalDateTime timestamp(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String normalizeVisibility(String value) {
        return "PUBLIC".equalsIgnoreCase(value) ? "PUBLIC" : "PRIVATE";
    }

    private static boolean isPublic(Video v) {
        if (v.getVisibility() != null && v.getVisibility() != VideoVisibility.PUBLIC) return false;
        String moderation = v.getModerationStatus();
        return moderation == null || moderation.isBlank() || "SAFE".equalsIgnoreCase(moderation);
    }
}
