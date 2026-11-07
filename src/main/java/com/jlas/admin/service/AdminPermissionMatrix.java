package com.jlas.admin.service;

import java.util.List;
import java.util.Set;

/**
 * [JLAS2-51] Who may use which admin area, as enforced today by {@code SecurityConfig} and the controllers'
 * {@code @PreAuthorize}; this class only describes it. Each group lists read-only GET endpoints that stand for it:
 * the first one is shown in the matrix, all of them are checked against the real security rules by the tests.
 * There is no role hierarchy: ADMIN does not inherit CONTENT_CREATOR rights.
 */
public final class AdminPermissionMatrix {
    public static final List<String> ROLES = List.of("LEARNER", "MODERATOR", "ADMIN", "CONTENT_CREATOR");

    public record Group(String key, String label, String description, Set<String> roles, List<String> endpoints) {
        public String endpoint() {
            return endpoints.getFirst();
        }
    }

    private static final Set<String> MODERATION = Set.of("MODERATOR", "ADMIN");
    private static final Set<String> ADMIN = Set.of("ADMIN");
    private static final Set<String> CONTENT = Set.of("CONTENT_CREATOR");

    public static final List<Group> GROUPS = List.of(
            new Group("MODERATION", "Kiểm duyệt",
                    "Báo cáo nội dung (gồm bộ thẻ và video Community Hub), hàng đợi AI, đóng góp từ điển, kháng nghị, từ khoá cấm",
                    MODERATION, List.of("/api/admin/moderation/reports", "/api/admin/moderation/queue",
                    "/api/admin/moderation/appeals", "/api/admin/moderation/denylist")),
            new Group("SANCTIONS", "Chế tài người dùng", "Xem, áp dụng và gỡ chế tài cấm bình luận, khoá tài khoản",
                    ADMIN, List.of("/api/admin/moderation/sanctions/active")),
            new Group("SYSTEM", "Quản trị hệ thống",
                    "Người dùng, gói Premium, thanh toán, nhật ký quản trị, cấu hình, Tổng quan",
                    ADMIN, List.of("/api/admin/system/dashboard", "/api/admin/system/users",
                    "/api/admin/system/premium-plans", "/api/admin/system/payments", "/api/admin/system/audit-logs",
                    "/api/admin/system/configs", "/api/admin/system/permissions")),
            new Group("CONTENT", "Nội dung", "Giáo trình và bài học, đề thi JLPT, luyện chia động từ, lược đồ Kanji",
                    CONTENT, List.of("/api/dictionary/manage/textbooks", "/api/admin/jlpt/practice-types",
                    "/api/admin/verb-practice/catalog", "/api/admin/kanji-map/catalog")),
            new Group("GAME", "Trang phục game / khung avatar", "Danh mục trang phục game và khung avatar",
                    ADMIN, List.of("/api/admin/game-skins", "/api/admin/avatar-frames")));

    public record Cell(String role, String group, boolean allowed, String endpoint) {
    }

    public record Matrix(List<String> roles, List<Group> groups, List<Cell> cells) {
    }

    public static Matrix matrix() {
        List<Cell> cells = ROLES.stream().flatMap(role -> GROUPS.stream()
                .map(group -> new Cell(role, group.key(), group.roles().contains(role), group.endpoint()))).toList();
        return new Matrix(ROLES, GROUPS, cells);
    }

    private AdminPermissionMatrix() {
    }
}
