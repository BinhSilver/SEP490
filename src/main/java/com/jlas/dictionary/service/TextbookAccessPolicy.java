package com.jlas.dictionary.service;

import com.jlas.auth.security.CurrentUser;
import com.jlas.auth.service.PremiumAccess;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Quyền HỌC giáo trình (Giáo trình — /textbooks). Chỗ DUY NHẤT định nghĩa luật miễn phí / Premium:
 * <ul>
 *   <li>Chưa đăng nhập: không xem được giáo trình nào (API → 401).</li>
 *   <li>Đã đăng nhập, miễn phí: cấp trong {@link #FREE_LEVELS} — "Nhập môn" (không cấp JLPT, không phải sách chủ đề,
 *       vd. Bảng chữ cái, Quy tắc đọc Kana) và N5.</li>
 *   <li>Premium ({@link PremiumAccess#isActive(Long)}): mọi sách — N4–N1 và sách theo chủ đề.</li>
 *   <li>Người duyệt nội dung (CONTENT_CREATOR, cùng điều kiện canViewDraft()): không bị khóa.</li>
 * </ul>
 * Sách chủ đề = không cấp JLPT và có bài gắn tag chủ đề, HOẶC sách giao tiếp của lộ trình ({@code LP-OWNED-COMM-*}).
 * <p>Ngoại lệ API ({@link #requireApiAccess}): sách nguồn lộ trình ({@code is_learning_path_source} hoặc
 * {@code LP-OWNED-}/{@code LP-REVIEWED-}) KHÔNG bị chặn ở API vì trang /learning/lesson/{id} dùng chung API nội dung bài;
 * route TRANG giáo trình vẫn khóa các sách này ({@link #canStudy}).
 */
@Component
@RequiredArgsConstructor
public class TextbookAccessPolicy {

    /** Message cố định của lỗi 403 (ErrorCode.FORBIDDEN) để FE nhận biết "cần Premium". */
    public static final String PREMIUM_REQUIRED = "PREMIUM_REQUIRED";
    /** Cấp "Nhập môn": sách không có cấp JLPT và không phải sách chủ đề. */
    public static final String LEVEL_INTRO = "INTRO";
    /** Cấp học miễn phí khi đã đăng nhập. */
    public static final Set<String> FREE_LEVELS = Set.of(LEVEL_INTRO, "N5");

    static final String LEVEL_TOPIC = "TOPIC";
    static final String COMMUNICATION_BOOK_PREFIX = "LP-OWNED-COMM-";
    private static final String ROLE_CONTENT_CREATOR = "ROLE_CONTENT_CREATOR";
    private static final String TAG_TYPE_TOPIC = "SITUATION";

    private final PremiumAccess premiumAccess;
    private final TextbookRepository textbookRepository;
    private final LessonRepository lessonRepository;

    /**
     * Người xem của MỘT request. Trạng thái Premium đọc lười và chỉ một lần (memo), không gọi lại trong vòng lặp
     * từng sách/bài; không cần thì không đọc (sách miễn phí, người duyệt nội dung).
     */
    public final class Viewer {
        private final Long userId;
        private final boolean unrestricted;
        private Boolean premium;

        private Viewer(Long userId, boolean unrestricted) {
            this.userId = userId;
            this.unrestricted = unrestricted;
        }

        public boolean loggedIn() { return userId != null; }

        public boolean unrestricted() { return unrestricted; }

        public boolean premium() {
            if (premium == null) premium = userId != null && premiumAccess.isActive(userId);
            return premium;
        }

        public boolean hasFullAccess() { return unrestricted || premium(); }
    }

    /** Người xem hiện tại (có thể là khách). */
    public Viewer currentViewer() {
        Long userId = CurrentUser.currentUserId().orElse(null);
        return new Viewer(userId, userId != null && CurrentUser.hasRole(ROLE_CONTENT_CREATOR));
    }

    /** Người xem hiện tại; khách → 401. */
    public Viewer requireViewer() {
        Viewer viewer = currentViewer();
        if (!viewer.loggedIn()) throw new ApiException(ErrorCode.UNAUTHORIZED, "Vui lòng đăng nhập để học giáo trình.");
        return viewer;
    }

    // ── Phân loại sách ───────────────────────────────────────────────────────

    /** Sách chủ đề khi đã biết số bài gắn tag chủ đề của sách (danh sách tính sẵn 1 query cho cả lô). */
    public static boolean isTopicBook(Textbook textbook, long topicTaggedLessons) {
        String code = textbook.getSourceCode();
        if (code != null && code.startsWith(COMMUNICATION_BOOK_PREFIX)) return true;
        return isBlank(textbook.getJlptLevel()) && topicTaggedLessons > 0;
    }

    /** Sách chủ đề cho MỘT sách; chỉ đếm bài (1 query) khi sách không có cấp JLPT. */
    public boolean isTopicBook(Textbook textbook) {
        String code = textbook.getSourceCode();
        if (code != null && code.startsWith(COMMUNICATION_BOOK_PREFIX)) return true;
        if (!isBlank(textbook.getJlptLevel())) return false;
        return textbookRepository.countSituationTaggedLessonsByTextbookIds(List.of(textbook.getId()), TAG_TYPE_TOPIC)
                .stream().mapToLong(row -> (Long) row[1]).sum() > 0;
    }

    /** Cấp truy cập: TOPIC, INTRO hoặc cấp JLPT (N5…N1). */
    static String accessLevel(Textbook textbook, boolean topicBook) {
        if (topicBook) return LEVEL_TOPIC;
        String level = textbook.getJlptLevel();
        return isBlank(level) ? LEVEL_INTRO : level.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isFree(Textbook textbook, boolean topicBook) {
        return FREE_LEVELS.contains(accessLevel(textbook, topicBook));
    }

    public boolean isFree(Textbook textbook) {
        return isFree(textbook, isTopicBook(textbook));
    }

    // ── Quyền học ───────────────────────────────────────────────────────────

    /** Học được sách này không (danh sách: đã biết topicBook). Dùng cho cờ locked và route trang. */
    public static boolean canStudy(Viewer viewer, Textbook textbook, boolean topicBook) {
        if (!viewer.loggedIn()) return false;
        return viewer.unrestricted() || isFree(textbook, topicBook) || viewer.premium();
    }

    public boolean canStudy(Viewer viewer, Textbook textbook) {
        if (!viewer.loggedIn()) return false;
        return viewer.unrestricted() || isFree(textbook) || viewer.premium();
    }

    /** Sách nguồn của lộ trình: API giáo trình không chặn Premium (trang /learning/lesson/{id} dùng chung API). */
    public static boolean isLearningPathApiExempt(Textbook textbook) {
        return Boolean.TRUE.equals(textbook.getIsLearningPathSource()) || isLearningPathBook(textbook);
    }

    /**
     * Sách của RIÊNG lộ trình (source_code {@code LP-OWNED-*} / {@code LP-REVIEWED-*}): dữ liệu theo kiểu lộ trình nên
     * trang Giáo trình không hiển thị đúng → route trang Giáo trình chuyển sang trang lộ trình. Khác sách NGUỒN lộ trình
     * (cờ is_learning_path_source, vd. Sekai N5) — loại đó vẫn là giáo trình bình thường.
     */
    public static boolean isLearningPathBook(Textbook textbook) {
        String code = textbook.getSourceCode();
        return code != null && (code.startsWith(TextbookRepository.LEARNING_PATH_OWNED_PREFIX)
                || code.startsWith(TextbookRepository.LEARNING_PATH_REVIEWED_PREFIX));
    }

    /** Chặn API trả nội dung sách/bài: khách → 401; sách bị khóa (trừ ngoại lệ lộ trình) → 403 PREMIUM_REQUIRED. */
    public void requireApiAccess(Viewer viewer, Textbook textbook) {
        if (!viewer.loggedIn()) throw new ApiException(ErrorCode.UNAUTHORIZED, "Vui lòng đăng nhập để học giáo trình.");
        if (viewer.unrestricted() || isLearningPathApiExempt(textbook)) return;
        if (!canStudy(viewer, textbook)) throw new ApiException(ErrorCode.FORBIDDEN, PREMIUM_REQUIRED);
    }

    /**
     * Cho API phụ của một bài (page-images, reading-images): khách → 401, sách của bài bị khóa → 403.
     * Bài không tồn tại: để service xử lý như cũ (danh sách rỗng).
     */
    public void requireLessonApiAccess(Long lessonId) {
        Viewer viewer = requireViewer();
        if (viewer.unrestricted() || lessonId == null) return;
        lessonRepository.findWithUnitById(lessonId)
                .ifPresent(lesson -> requireApiAccess(viewer, lesson.getUnit().getTextbook()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
