package com.jlas.admin.service;

import java.util.List;

/**
 * [JLAS2-51] Where every dashboard figure comes from. Each entry describes the query that produces it, so keep it
 * in step with {@link AdminDashboardService}, its repositories and the moderation overview.
 * {@code key} is the field name in the dashboard response.
 */
public final class AdminKpiCatalog {
    public record KpiSource(String key, String label, String sourceTables, String filter, String formula,
                            String period) {
    }

    private static final String NOW = "Tại thời điểm tải trang";
    private static final String ALL_TIME = "Toàn bộ dữ liệu";
    private static final String CHOSEN = "Kỳ đang chọn: năm nay (từ 01/01), 30 ngày hoặc 7 ngày gần nhất; "
            + "so với cùng kỳ năm trước (năm nay) hoặc khoảng liền trước có cùng độ dài";

    public static final List<KpiSource> ALL = List.of(
            new KpiSource("totalUsers", "Tổng người dùng", "users", "Tất cả tài khoản", "COUNT(*)", ALL_TIME),
            new KpiSource("usersByRole", "Người dùng theo vai trò", "users JOIN roles", "Tất cả tài khoản",
                    "COUNT(*) GROUP BY roles.name", ALL_TIME),
            new KpiSource("lockedUsers", "Tài khoản bị khóa", "users", "locked_at IS NOT NULL", "COUNT(*)", ALL_TIME),
            new KpiSource("unverifiedUsers", "Tài khoản chưa xác thực email", "users", "is_verified = false",
                    "COUNT(*)", ALL_TIME),
            new KpiSource("newUsersLast7Days", "Người dùng mới 7 ngày", "users", "created_at >= now − 7 ngày",
                    "COUNT(*)", "7 ngày gần nhất"),
            new KpiSource("newUsersLast30Days", "Người dùng mới 30 ngày", "users", "created_at >= now − 30 ngày",
                    "COUNT(*)", "30 ngày gần nhất"),
            new KpiSource("premiumActive", "Premium đang hiệu lực", "users JOIN premium_plans",
                    "premium_plan_id IS NOT NULL AND UPPER(premium_plans.name) <> 'FREE' AND premium_expired_at > now",
                    "COUNT(*); biểu đồ tỉ lệ chỉ tính trên học viên (role LEARNER)", NOW),
            new KpiSource("plansOnSale", "Gói Premium đang bán", "premium_plans",
                    "name khác 'FREE' (không phân biệt hoa thường) AND is_active = true", "COUNT(*)", NOW),
            new KpiSource("plansStopped", "Gói Premium ngừng bán", "premium_plans",
                    "name khác 'FREE' (không phân biệt hoa thường) AND is_active = false", "COUNT(*)", NOW),
            new KpiSource("paymentsByStatus", "Giao dịch theo trạng thái", "payments", "Tất cả giao dịch",
                    "COUNT(*) GROUP BY status; thẻ \"Giao dịch đang chờ\" là nhóm PENDING, \"Thất bại\" gộp "
                            + "FAILED + CANCELLED + EXPIRED", ALL_TIME),
            new KpiSource("pendingPaymentsOverdue", "Giao dịch chờ quá X phút", "payments",
                    "status = 'PENDING' AND created_at < now − X phút (X = cấu hình payment.pending_alert_minutes)",
                    "COUNT(*)", NOW),
            new KpiSource("periodStats.newUsers", "Người dùng mới trong kỳ", "users",
                    "created_at trong [đầu kỳ, now]", "COUNT(*)", CHOSEN),
            new KpiSource("periodStats.paid", "Doanh thu trong kỳ", "payments",
                    "status = 'PAID' AND paid_at trong [đầu kỳ, now]",
                    "COUNT(*), SUM(amount_paid) GROUP BY currency", CHOSEN),
            new KpiSource("paidLast30Days", "Doanh thu 30 ngày", "payments", "status = 'PAID' AND paid_at >= now − 30 ngày",
                    "COUNT(*), SUM(amount_paid) GROUP BY currency", "30 ngày gần nhất"),
            new KpiSource("paidByProductLast30Days", "Doanh thu 30 ngày theo sản phẩm", "payments",
                    "status = 'PAID' AND paid_at >= now − 30 ngày",
                    "COUNT(*), SUM(amount_paid), SUM(discount_amount) GROUP BY product_type, currency", "30 ngày gần nhất"),
            new KpiSource("discountLast30Days", "Giảm giá 30 ngày", "payments",
                    "status = 'PAID' AND paid_at >= now − 30 ngày", "SUM(discount_amount)", "30 ngày gần nhất"),
            new KpiSource("registrationsByYear", "Người đăng ký mới mỗi năm", "users",
                    "created_at >= 01/01 của năm (năm nay − 4)", "COUNT(*) GROUP BY YEAR(created_at); năm không có ai là 0",
                    "5 năm gần nhất, gồm năm nay"),
            new KpiSource("premiumByMonth", "Đăng ký Premium mỗi tháng", "payments",
                    "status = 'PAID' AND product_type = 'PREMIUM' AND paid_at trong [01/01 năm nay, now]",
                    "COUNT(*), SUM(amount_paid) GROUP BY MONTH(paid_at), currency", "Năm nay"),
            new KpiSource("moderationPending", "Kiểm duyệt chờ xử lý", "review_case",
                    "status = 'OPEN' AND case_type IN ('CONTENT_REVIEW', 'APPEAL') (hàng đợi AI + đóng góp từ điển + "
                            + "kháng nghị; báo cáo cộng đồng không tính)",
                    "COUNT(*), lấy từ GET /api/admin/moderation/overview", NOW),
            new KpiSource("recentActions", "Thao tác quản trị gần đây", "admin_audit_log", "Tất cả thao tác",
                    "ORDER BY created_at DESC, id DESC LIMIT N (N = cấu hình dashboard.recent_actions_limit)", NOW),
            new KpiSource("configChanges", "Cấu hình thay đổi gần đây", "admin_audit_log",
                    "action IN ('CONFIG_UPDATE', 'CONFIG_RESET')",
                    "ORDER BY created_at DESC, id DESC LIMIT 5", NOW));

    private AdminKpiCatalog() {
    }
}
