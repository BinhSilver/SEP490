package com.jlas.shadowing.repository;

import com.jlas.common.history.ViewHistoryRepository;
import com.jlas.shadowing.dto.LibraryPageDto;
import com.jlas.shadowing.dto.SeriesSummaryDto;
import com.jlas.shadowing.util.LibraryFilters;
import com.jlas.shadowing.util.LibraryPopularity;
import com.jlas.shadowing.util.LibrarySearch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lightweight catalog snapshots: rank/filter globally, then hydrate subtitles only for the requested page. */
@Repository
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class ShadowingLibraryCatalog {
    private final JdbcTemplate jdbc;
    public ShadowingLibraryCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Entry(long id, String title, Double duration, LocalDateTime createdAt, Long ownerId,
                        String status, String visibility, String moderation, List<String> tags, String level,
                        LibraryPopularity.Metrics metrics, Set<String> genres) {
        public boolean publicReady() {
            return "READY".equals(status) && (visibility == null || "PUBLIC".equals(visibility))
                    && !"REVIEW".equalsIgnoreCase(moderation) && !"BLOCKED".equalsIgnoreCase(moderation)
                    && !com.jlas.shadowing.util.ShadowingModerationState.isLocked(moderation);
        }
    }
    public record Snapshot(List<Entry> entries, LibraryPopularity.Caps caps) {}

    public Snapshot snapshot() {
        LocalDateTime now = LocalDateTime.now();
        // AVG/COUNT use actual user ratings, not the transient Video entity fields.
        List<Entry> entries = jdbc.query("""
                SELECT v.id, v.title, v.duration_seconds, v.created_at, v.owner_id, v.status, v.visibility,
                       v.moderation_status, COALESCE(v.view_count,0) views,
                       COALESCE(r.average_rating,0) average_rating, COALESCE(r.rating_count,0) rating_count,
                       COALESCE(e.recent_views,0) recent_views, t.tags, t.tag_level, t.genres,
                       COALESCE(NULLIF(p.selected_jlpt_level,''), p.suggested_jlpt_level) prepared_level
                FROM video v
                LEFT JOIN (SELECT video_id, AVG(rating) average_rating, COUNT(rating) rating_count
                           FROM video_user_state WHERE rating BETWEEN 1 AND 5 GROUP BY video_id) r ON r.video_id=v.id
                LEFT JOIN (SELECT video_id, COUNT(*) recent_views FROM shadowing_view_event
                           WHERE counted_at >= ? AND counted_at <= ? GROUP BY video_id) e ON e.video_id=v.id
                LEFT JOIN (SELECT vt.video_id, GROUP_CONCAT(t.name SEPARATOR ',') tags,
                                  MIN(CASE WHEN UPPER(TRIM(t.name)) IN ('N1','N2','N3','N4','N5')
                                           THEN UPPER(TRIM(t.name)) END) tag_level,
                                  GROUP_CONCAT(CASE WHEN LEFT(t.code,12)='VIDEO_GENRE_' THEN SUBSTRING(t.code,13) END) genres
                           FROM video_tag vt JOIN tag t ON t.id=vt.tag_id GROUP BY vt.video_id) t ON t.video_id=v.id
                LEFT JOIN shadowing_preparation_job p ON p.video_id=v.id
                """, (rs, n) -> new Entry(rs.getLong("id"), rs.getString("title"),
                rs.getObject("duration_seconds") == null ? null : rs.getDouble("duration_seconds"),
                local(rs.getTimestamp("created_at")), (Long) rs.getObject("owner_id", Long.class),
                rs.getString("status"), rs.getString("visibility"), rs.getString("moderation_status"),
                rs.getString("tags") == null ? List.of() : Arrays.asList(rs.getString("tags").split(",")),
                rs.getString("tag_level") != null ? rs.getString("tag_level") : rs.getString("prepared_level"),
                new LibraryPopularity.Metrics(rs.getDouble("average_rating"), rs.getLong("rating_count"),
                        rs.getLong("views"), rs.getLong("recent_views")), LibraryFilters.values(rs.getString("genres"))),
                Timestamp.valueOf(now.minusDays(7)), Timestamp.valueOf(now));
        return new Snapshot(entries, LibraryPopularity.caps(entries.stream().filter(Entry::publicReady).map(Entry::metrics).toList()));
    }

    public LibraryPageDto<Entry> videos(String query, String category, String level, String sort,
                                        String scope, String status, boolean favorites, Long userId, int page, int size) {
        return videos(query, category, null, level, sort, scope, status, favorites, userId, page, size);
    }

    /** {@code genre}: comma-separated genre codes; a video must have every selected genre. */
    public LibraryPageDto<Entry> videos(String query, String category, String genre, String level, String sort,
                                        String scope, String status, boolean favorites, Long userId, int page, int size) {
        LibraryPageDto.validate(page, size);
        Set<String> genres = LibraryFilters.values(genre);
        Snapshot catalog = snapshot();
        Map<Long, Integer> relevance = relevance(LibrarySearch.of(query), catalog.entries());
        Set<Long> favoriteIds = favorites && userId != null ? new HashSet<>(jdbc.queryForList(
                "SELECT video_id FROM video_user_state WHERE user_id=? AND is_favorite=1", Long.class, userId)) : Set.of();
        Set<String> categories = LibraryFilters.values(category), levels = LibraryFilters.values(level);
        // Lịch sử: video đã mở và vẫn xem được (công khai hoặc của mình), lần mở gần nhất trước.
        boolean history = "history".equals(scope);
        Map<Long, LocalDateTime> viewed = history && userId != null
                ? new ViewHistoryRepository(jdbc).recent(userId, ViewHistoryRepository.Item.SHADOWING_VIDEO) : Map.of();
        Comparator<Entry> chosen = history
                ? Comparator.comparing((Entry v) -> viewed.get(v.id()), Comparator.reverseOrder())
                : order(sort, Entry::metrics, Entry::duration, Entry::title, Entry::createdAt, Entry::id, catalog.caps());
        // With a keyword the best match comes first; the chosen sort only orders equally relevant videos.
        Comparator<Entry> order = relevance == null ? chosen
                : Comparator.<Entry>comparingInt(v -> relevance.get(v.id())).reversed().thenComparing(chosen);
        List<Entry> filtered = catalog.entries().stream()
                .filter(v -> !com.jlas.shadowing.util.ShadowingModerationState.isRemoved(v.moderation()))
                .filter(v -> history ? viewed.containsKey(v.id()) && (v.publicReady() || Objects.equals(userId, v.ownerId()))
                        : "my".equals(scope) ? userId != null && Objects.equals(userId, v.ownerId())
                        : "all".equals(scope) ? v.publicReady() || userId != null && Objects.equals(userId, v.ownerId()) : v.publicReady())
                .filter(v -> status == null || status.isBlank() || status.equals(v.status()))
                .filter(v -> relevance == null || relevance.containsKey(v.id()))
                .filter(v -> !favorites || favoriteIds.contains(v.id()))
                .filter(v -> categories.isEmpty() || v.tags().stream().anyMatch(t -> categories.contains(t.toLowerCase(Locale.ROOT))))
                .filter(v -> v.genres().containsAll(genres))
                .filter(v -> levels.isEmpty() || v.level() != null && levels.contains(v.level().toLowerCase(Locale.ROOT)))
                .sorted(order)
                .toList();
        return LibraryPageDto.from(filtered, page, size);
    }

    public LibraryPageDto<SeriesSummaryDto> series(String query, String category, String level, String sort,
                                                  Long userId, int page, int size) {
        return series(query, category, null, level, sort, userId, page, size);
    }

    /** A series matches a genre filter when its public videos together cover every selected genre. */
    public LibraryPageDto<SeriesSummaryDto> series(String query, String category, String genre, String level,
                                                  String sort, Long userId, int page, int size) {
        LibraryPageDto.validate(page, size);
        Set<String> genres = LibraryFilters.values(genre);
        Map<Long, Set<String>> seriesGenres = new HashMap<>();
        Snapshot catalog = snapshot();
        Map<Long, Entry> publicVideos = catalog.entries().stream().filter(Entry::publicReady)
                .collect(Collectors.toMap(Entry::id, Function.identity()));
        Map<Long, List<Entry>> members = new HashMap<>();
        jdbc.query("SELECT series_id, video_id FROM shadowing_series_video ORDER BY series_id, position", rs -> {
            Entry video = publicVideos.get(rs.getLong("video_id"));
            if (video != null) members.computeIfAbsent(rs.getLong("series_id"), k -> new ArrayList<>()).add(video);
        });
        Set<Long> withThumbnail = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM video WHERE thumbnail_url IS NOT NULL AND thumbnail_url <> ''", Long.class));
        Map<Long, LibraryPopularity.Metrics> metrics = new HashMap<>();
        List<SeriesSummaryDto> rows = jdbc.query("SELECT * FROM shadowing_series WHERE visibility='PUBLIC'", (rs, n) -> {
            var s = new SeriesSummaryDto(); long id = rs.getLong("id");
            s.setId(id); s.setTitle(rs.getString("title")); s.setDescription(rs.getString("description"));
            s.setEstimatedLevel(rs.getString("estimated_level")); s.setLevelSource(rs.getString("level_source"));
            s.setOwnerId(rs.getLong("owner_id")); s.setOwnedByMe(Objects.equals(userId, s.getOwnerId()));
            s.setVisibility("PUBLIC"); s.setCreatedAt(local(rs.getTimestamp("created_at")));
            List<Entry> videos = members.getOrDefault(id, List.of());
            long count = videos.stream().mapToLong(v -> v.metrics().ratingCount()).sum();
            double average = count == 0 ? 0 : videos.stream().mapToDouble(v -> v.metrics().averageRating() * v.metrics().ratingCount()).sum() / count;
            long views = videos.stream().mapToLong(v -> v.metrics().views()).sum();
            long recent = videos.stream().mapToLong(v -> v.metrics().recentViews()).sum();
            metrics.put(id, new LibraryPopularity.Metrics(average, count, views, recent));
            s.setVideoCount(videos.size()); s.setViewCount((int) Math.min(Integer.MAX_VALUE, views));
            s.setRatingCount((int) Math.min(Integer.MAX_VALUE, count)); s.setAvgRating(count > 0 ? average : null);
            double duration = videos.stream().map(Entry::duration).filter(d -> d != null && d > 0 && Double.isFinite(d)).mapToDouble(Double::doubleValue).sum();
            s.setDuration(duration > 0 ? duration : null);
            s.setCategories(videos.stream().flatMap(v -> v.tags().stream()).distinct().toList());
            seriesGenres.put(id, videos.stream().flatMap(v -> v.genres().stream()).collect(Collectors.toSet()));
            s.setCoverVideoId(videos.stream().map(Entry::id).filter(withThumbnail::contains).findFirst().orElse(null));
            return s;
        });
        // Series have their own global P95 distribution; filters never change the caps.
        var caps = LibraryPopularity.caps(metrics.values());
        Set<String> categories = LibraryFilters.values(category), levels = LibraryFilters.values(level);
        LibrarySearch search = LibrarySearch.of(query);
        Map<Long, Integer> relevance = new HashMap<>();
        if (search != null) rows.forEach(s -> {
            int score = search.score(s.getTitle(), List.of(), s.getDescription() == null ? List.of() : List.of(s.getDescription()));
            if (score > 0) relevance.put(s.getId(), score);
        });
        Comparator<SeriesSummaryDto> chosen = order(sort, s -> metrics.get(s.getId()), SeriesSummaryDto::getDuration,
                SeriesSummaryDto::getTitle, SeriesSummaryDto::getCreatedAt, SeriesSummaryDto::getId, caps);
        Comparator<SeriesSummaryDto> ranked = search == null ? chosen
                : Comparator.<SeriesSummaryDto>comparingInt(s -> relevance.get(s.getId())).reversed().thenComparing(chosen);
        List<SeriesSummaryDto> filtered = rows.stream()
                .filter(s -> search == null || relevance.containsKey(s.getId()))
                .filter(s -> categories.isEmpty() || s.getCategories().stream().anyMatch(c -> categories.contains(c.toLowerCase(Locale.ROOT))))
                .filter(s -> seriesGenres.getOrDefault(s.getId(), Set.of()).containsAll(genres))
                .filter(s -> levels.isEmpty() || s.getEstimatedLevel() != null && levels.contains(s.getEstimatedLevel().toLowerCase(Locale.ROOT)))
                .sorted(ranked).toList();
        return LibraryPageDto.from(filtered, page, size);
    }

    /**
     * Relevance of every matching video, or null without a keyword. Titles and tags come from the snapshot; subtitle
     * lines are pre-filtered in SQL per word, then {@link LibrarySearch} applies the whole-word rule and the score.
     */
    private Map<Long, Integer> relevance(LibrarySearch search, List<Entry> entries) {
        if (search == null) return null;
        // The Japanese line and its Vietnamese translation are both searched ("cảm ơn vì đã vất vả" finds
        // お疲れ様でした through its translation). They are kept as separate texts so a phrase never spans the two.
        Map<Long, Map<String, String>> lines = new HashMap<>();
        for (String term : search.terms()) {
            String like = "%" + term + "%";
            jdbc.query("SELECT id, video_id, text, translation FROM subtitle WHERE LOWER(text) LIKE ? OR LOWER(translation) LIKE ?",
                    rs -> {
                        Map<String, String> own = lines.computeIfAbsent(rs.getLong("video_id"), k -> new HashMap<>());
                        long id = rs.getLong("id");
                        own.put("ja:" + id, rs.getString("text"));
                        String translation = rs.getString("translation");
                        if (translation != null && !translation.isBlank()) own.put("vi:" + id, translation);
                    },
                    like, like);
        }
        Map<Long, Integer> scores = new HashMap<>();
        for (Entry entry : entries) {
            Map<String, String> own = lines.get(entry.id());
            int score = search.score(entry.title(), entry.tags(), own == null ? List.of() : own.values());
            if (score > 0) scores.put(entry.id(), score);
        }
        return scores;
    }

    public static <T> Comparator<T> order(String sort, Function<T, LibraryPopularity.Metrics> metrics,
            Function<T, Double> duration, Function<T, String> title, Function<T, LocalDateTime> created,
            Function<T, Long> id, LibraryPopularity.Caps caps) {
        Comparator<T> comparator = switch (Objects.toString(sort, "popular")) {
            case "duration-asc", "duration-desc" -> LibraryFilters.duration(duration, "duration-desc".equals(sort));
            case "newest" -> Comparator.comparing(created, Comparator.nullsLast(Comparator.reverseOrder()));
            case "title" -> Comparator.comparing(v -> Objects.toString(title.apply(v), ""), String.CASE_INSENSITIVE_ORDER);
            case "rating" -> Comparator.<T>comparingDouble(v -> metrics.apply(v).averageRating()).reversed()
                    .thenComparing(Comparator.<T>comparingLong(v -> metrics.apply(v).ratingCount()).reversed());
            case "views" -> Comparator.<T>comparingLong(v -> metrics.apply(v).views()).reversed();
            default -> Comparator.<T>comparingDouble(v -> LibraryPopularity.score(metrics.apply(v), caps)).reversed();
        };
        return comparator.thenComparing(created, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(id, Comparator.reverseOrder());
    }
    private static LocalDateTime local(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
}
