package com.jlas.moderation.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.entity.UserBulkViolation;
import com.jlas.moderation.repository.UserBulkViolationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.AntPathMatcher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chống tải nội dung hàng loạt: đếm request của từng tài khoản theo cửa sổ thời gian cố định, chia hai loại
 * <ul>
 *   <li>{@link Kind#DOWNLOAD}: GET vào API nội dung (từ điển, bài học, đề JLPT, flashcard, video, audio…) — chống cào
 *       dữ liệu;</li>
 *   <li>{@link Kind#UPLOAD}: POST tạo nội dung (bài/bình luận Cộng đồng, video Shadowing, bộ flashcard, đóng góp từ
 *       điển…) — chống đăng spam.</li>
 * </ul>
 * Vượt ngưỡng → request bị trả 429 tới hết cửa sổ, và lần vượt đầu tiên của cửa sổ được ghi thành một vi phạm. Số vi
 * phạm chưa được admin gỡ trong {@code strikeDays} ngày quyết định mức: 1 → cảnh báo, 2 → tạm ngưng (BAN có thời hạn),
 * 3+ → khoá (BAN vĩnh viễn); hai lần ghi vi phạm cách nhau ít nhất {@code violationCooldownMinutes} phút. BAN dùng
 * chung {@link UserSanctionService} nên {@code BanEnforcementFilter}, thông báo và kháng nghị hoạt động như chế tài
 * admin áp.
 * <p>
 * Bộ đếm nằm trong bộ nhớ (app chạy 1 instance, như phòng Arena và cache chế tài); vi phạm và chế tài nằm trong DB nên
 * các bậc xử lý vẫn đúng nếu sau này chạy nhiều instance, chỉ ngưỡng đếm bị nhân theo số instance. Người chưa đăng nhập
 * đếm theo IP và chỉ bị 429 (không có tài khoản để khoá). ADMIN / MODERATOR / CONTENT_CREATOR được miễn.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkContentGuardService {

    public enum Kind { DOWNLOAD, UPLOAD }

    public enum Action { WARN, SUSPEND, LOCK }

    /** API nội dung tính là "tải xuống" (chỉ GET). */
    static final List<String> DOWNLOAD_PATHS = List.of(
            "/api/dictionary/**", "/api/learning/**", "/api/jlpt/**", "/api/flashcards/**", "/api/series/**",
            "/api/videos/**", "/api/study/**", "/api/shadowing/**", "/api/verb-practice/**", "/audio/**");

    /** Tạo nội dung tính là "đăng tải" (chỉ POST). */
    static final List<String> UPLOAD_PATHS = List.of(
            "/api/community/posts", "/api/community/contents/*/*/comments",
            "/api/videos/upload", "/api/youtube/import", "/api/videos/*/comments", "/api/series",
            "/api/flashcards", "/api/flashcards/import", "/api/flashcards/import/file", "/api/flashcards/*/clone",
            "/api/dictionary/vocabulary/*/contributions", "/api/dictionary/kanji/*/contributions");

    /** Vai trò không bị giới hạn (quản trị, kiểm duyệt, người soạn nội dung). */
    public static final List<String> EXEMPT_ROLES = List.of("ROLE_ADMIN", "ROLE_MODERATOR", "ROLE_CONTENT_CREATOR");

    private static final int MAX_COUNTERS = 50_000;

    private final UserBulkViolationRepository violations;
    private final UserSanctionService sanctions;
    private final ModerationNotifier notifier;
    private final ModerationAuditService audit;
    private final AntPathMatcher matcher = new AntPathMatcher();
    private final Map<String, long[]> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> lastViolation = new ConcurrentHashMap<>();

    @Value("${moderation.bulk-guard.enabled:true}")
    private boolean enabled;
    @Value("${moderation.bulk-guard.download.per-minute:300}")
    private int downloadPerMinute;
    @Value("${moderation.bulk-guard.download.per-hour:3000}")
    private int downloadPerHour;
    @Value("${moderation.bulk-guard.upload.per-5-minutes:10}")
    private int uploadPer5Minutes;
    @Value("${moderation.bulk-guard.upload.per-day:40}")
    private int uploadPerDay;
    @Value("${moderation.bulk-guard.suspend-hours:24}")
    private int suspendHours;
    @Value("${moderation.bulk-guard.strike-days:30}")
    private int strikeDays;
    @Value("${moderation.bulk-guard.violation-cooldown-minutes:60}")
    private int violationCooldownMinutes;

    /** Một ngưỡng: tối đa {@code max} request trong {@code windowSeconds} giây. */
    public record Limit(Kind kind, int max, int windowSeconds) {
    }

    /** Ngưỡng đang áp dụng, cho trang admin. */
    public record Settings(boolean enabled, List<Limit> limits, int suspendHours, int strikeDays,
                           int violationCooldownMinutes, List<String> exemptRoles) {
    }

    /**
     * Kết quả đếm một request. {@code blocked}: trả 429; {@code newViolation}: lần vượt đầu tiên của cửa sổ này (ghi
     * vi phạm cho tài khoản); {@code retryAfterSeconds}: còn bao lâu tới cửa sổ mới.
     */
    public record Hit(boolean blocked, boolean newViolation, Kind kind, Limit limit, int count, long retryAfterSeconds) {
        static final Hit PASS = new Hit(false, false, null, null, 0, 0);
    }

    public record ViolationView(Long id, Long userId, String kind, int requestCount, int requestLimit,
                                int windowSeconds, String samplePath, String actionTaken, Long sanctionId,
                                LocalDateTime createdAt, LocalDateTime clearedAt, String clearReason) {
    }

    /** Request này thuộc loại nào; null = không đếm. */
    public Kind classify(String method, String uri) {
        if (!enabled || uri == null) return null;
        if ("GET".equalsIgnoreCase(method) && matches(DOWNLOAD_PATHS, uri)) return Kind.DOWNLOAD;
        if ("POST".equalsIgnoreCase(method) && matches(UPLOAD_PATHS, uri)) return Kind.UPLOAD;
        return null;
    }

    /** Đếm một request của {@code subject} ("u:&lt;id&gt;" hoặc "ip:&lt;địa chỉ&gt;"); không ghi DB. */
    public Hit hit(String subject, Kind kind) {
        return hit(subject, kind, System.currentTimeMillis());
    }

    Hit hit(String subject, Kind kind, long nowMillis) {
        if (counters.size() >= MAX_COUNTERS) counters.clear();
        if (lastViolation.size() >= MAX_COUNTERS) lastViolation.clear();
        Hit worst = Hit.PASS;
        for (Limit limit : limits(kind)) {
            long windowMillis = limit.windowSeconds() * 1000L;
            long windowStart = nowMillis - Math.floorMod(nowMillis, windowMillis);
            long[] counter = counters.compute(subject + "|" + kind + "|" + limit.windowSeconds(), (key, value) ->
                    value == null || value[0] != windowStart ? new long[]{windowStart, 1} : new long[]{windowStart, value[1] + 1});
            int count = (int) counter[1];
            if (count > limit.max() && !worst.blocked()) {
                long retry = Math.max(1, (windowStart + windowMillis - nowMillis + 999) / 1000);
                worst = new Hit(true, count == limit.max() + 1 && cooledDown(subject, kind, nowMillis),
                        kind, limit, count, retry);
            }
        }
        return worst;
    }

    /**
     * Giữa hai lần ghi vi phạm cùng loại phải cách {@code violationCooldownMinutes} phút (trong lúc đó request vượt
     * ngưỡng vẫn bị 429): người dùng có thời gian đọc cảnh báo thay vì bị tạm ngưng ngay phút sau.
     */
    private boolean cooledDown(String subject, Kind kind, long nowMillis) {
        long cooldown = violationCooldownMinutes * 60_000L;
        boolean[] ok = {false};
        lastViolation.compute(subject + "|" + kind, (key, last) -> {
            if (last == null || nowMillis - last >= cooldown) {
                ok[0] = true;
                return nowMillis;
            }
            return last;
        });
        return ok[0];
    }

    /**
     * Ghi một vi phạm và áp mức xử lý theo số vi phạm chưa gỡ trong {@code strikeDays} ngày. Trả về mức đã áp.
     */
    @Transactional
    public Action recordViolation(Long userId, Hit hit, String path) {
        long previous = violations.countActiveSince(userId, LocalDateTime.now().minusDays(strikeDays));
        Action action = previous == 0 ? Action.WARN : previous == 1 ? Action.SUSPEND : Action.LOCK;
        UserBulkViolation violation = violations.save(UserBulkViolation.builder()
                .userId(userId)
                .kind(hit.kind().name())
                .requestCount(hit.count())
                .requestLimit(hit.limit().max())
                .windowSeconds(hit.limit().windowSeconds())
                .samplePath(path == null ? null : path.length() > 255 ? path.substring(0, 255) : path)
                .actionTaken(action.name())
                .build());
        boolean upload = hit.kind() == Kind.UPLOAD;
        String reason = (upload ? "Đăng tải" : "Tải") + " nội dung hàng loạt: " + hit.count() + " request trong "
                + describeWindow(hit.limit().windowSeconds()) + " (giới hạn " + hit.limit().max() + "), vi phạm lần "
                + (previous + 1) + " trong " + strikeDays + " ngày.";
        switch (action) {
            case WARN -> notifier.bulkWarning(userId, violation.getId(), upload, suspendHours);
            case SUSPEND -> violation.setSanctionId(sanctions.applyAutomaticBan(userId,
                    LocalDateTime.now().plusHours(suspendHours), "Tạm ngưng " + suspendHours + " giờ. " + reason).id());
            case LOCK -> violation.setSanctionId(sanctions.applyAutomaticBan(userId, null,
                    "Khoá tài khoản. " + reason).id());
        }
        log.warn("Bulk content guard user={} kind={} count={}/{} in {}s path={} -> {}", userId, hit.kind(),
                hit.count(), hit.limit().max(), hit.limit().windowSeconds(), path, action);
        return action;
    }

    /** Admin gỡ một vi phạm: không còn tính vào số lần tái phạm (chế tài đi kèm gỡ riêng ở danh sách chế tài). */
    @Transactional
    public ViolationView clear(Long violationId, Long adminId, String reason) {
        String why = ModerationAuditService.requireReason(reason);
        UserBulkViolation violation = violations.findById(violationId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy vi phạm"));
        if (violation.getClearedAt() != null) {
            throw new ApiException(ErrorCode.CONFLICT, "Vi phạm đã được gỡ trước đó");
        }
        violation.setClearedAt(LocalDateTime.now());
        violation.setClearedBy(adminId);
        violation.setClearReason(why.length() > 500 ? why.substring(0, 500) : why);
        UserBulkViolation saved = violations.save(violation);
        audit.record(adminId, new ModerationAuditService.Entry("BULK_VIOLATION_CLEAR", "USER", saved.getUserId(),
                saved.getKind() + ":" + saved.getActionTaken() + "#" + saved.getId(), "CLEARED", why, null));
        return view(saved);
    }

    @Transactional(readOnly = true)
    public List<ViolationView> recent(int limit) {
        return violations.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
                .stream().map(BulkContentGuardService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ViolationView> forUser(Long userId) {
        return violations.findByUserIdOrderByCreatedAtDesc(userId).stream().map(BulkContentGuardService::view).toList();
    }

    public Settings settings() {
        List<Limit> all = new java.util.ArrayList<>(limits(Kind.DOWNLOAD));
        all.addAll(limits(Kind.UPLOAD));
        return new Settings(enabled, all, suspendHours, strikeDays, violationCooldownMinutes,
                EXEMPT_ROLES.stream().map(role -> role.substring("ROLE_".length())).toList());
    }

    List<Limit> limits(Kind kind) {
        return kind == Kind.DOWNLOAD
                ? List.of(new Limit(kind, downloadPerMinute, 60), new Limit(kind, downloadPerHour, 3600))
                : List.of(new Limit(kind, uploadPer5Minutes, 300), new Limit(kind, uploadPerDay, 86_400));
    }

    private boolean matches(List<String> patterns, String uri) {
        for (String pattern : patterns) {
            if (matcher.match(pattern, uri)) return true;
        }
        return false;
    }

    static String describeWindow(int seconds) {
        if (seconds % 86_400 == 0) return seconds / 86_400 + " ngày";
        if (seconds % 3600 == 0) return seconds / 3600 + " giờ";
        if (seconds % 60 == 0) return seconds / 60 + " phút";
        return seconds + " giây";
    }

    private static ViolationView view(UserBulkViolation v) {
        return new ViolationView(v.getId(), v.getUserId(), v.getKind(), v.getRequestCount(), v.getRequestLimit(),
                v.getWindowSeconds(), v.getSamplePath(), v.getActionTaken(), v.getSanctionId(), v.getCreatedAt(),
                v.getClearedAt(), v.getClearReason());
    }
}
