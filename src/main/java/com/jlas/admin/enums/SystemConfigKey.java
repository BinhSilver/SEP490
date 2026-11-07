package com.jlas.admin.enums;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * [JLAS2-51] Every setting a System Admin may change at runtime. Only keys listed here can be read or written;
 * the database stores overrides only, so a key without a row uses {@link #defaultValue()}.
 */
public enum SystemConfigKey {
    DASHBOARD_DEFAULT_PERIOD("dashboard.default_period", "Tổng quan", "Kỳ mặc định của Tổng quan",
            "Kỳ thống kê khi mở trang Tổng quan mà người xem chưa tự chọn kỳ.",
            Type.ENUM, "year", null, null, List.of("year", "30d", "7d"), false),
    DASHBOARD_RECENT_ACTIONS_LIMIT("dashboard.recent_actions_limit", "Tổng quan", "Số thao tác gần đây",
            "Số thao tác quản trị mới nhất hiện trên trang Tổng quan.",
            Type.INT, "8", 3, 20, List.of(), false),
    PAYMENT_PENDING_ALERT_MINUTES("payment.pending_alert_minutes", "Thanh toán", "Cảnh báo giao dịch chờ (phút)",
            "Giao dịch còn PENDING lâu hơn số phút này được đếm vào thẻ \"Giao dịch chờ quá X phút\".",
            Type.INT, "30", 5, 1440, List.of(), false),
    PREMIUM_MAX_MANUAL_GRANT_DAYS("premium.max_manual_grant_days", "Premium", "Số ngày cấp Premium tối đa",
            "Giới hạn số ngày cho mỗi lần quản trị viên cấp Premium thủ công.",
            Type.INT, "365", 1, 3650, List.of(), false),
    MODERATION_WORD_CONTRIBUTION_DAILY_PAID("moderation.word_contribution_daily_paid", "Kiểm duyệt",
            "Số đóng góp từ vựng được thưởng mỗi ngày",
            "Mỗi người đóng góp được thưởng CP cho tối đa bấy nhiêu từ được duyệt trong một ngày; 0 là tắt thưởng.",
            Type.INT, "3", 0, 50, List.of(), false);

    public enum Type { INT, BOOLEAN, ENUM, STRING }

    private static final int MAX_LENGTH = 1000;

    private final String key;
    private final String group;
    private final String label;
    private final String description;
    private final Type type;
    private final String defaultValue;
    private final Integer min;
    private final Integer max;
    private final List<String> allowedValues;
    private final boolean secret;

    SystemConfigKey(String key, String group, String label, String description, Type type, String defaultValue,
                    Integer min, Integer max, List<String> allowedValues, boolean secret) {
        this.key = key;
        this.group = group;
        this.label = label;
        this.description = description;
        this.type = type;
        this.defaultValue = defaultValue;
        this.min = min;
        this.max = max;
        this.allowedValues = allowedValues;
        this.secret = secret;
    }

    public static Optional<SystemConfigKey> find(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /**
     * The stored form of {@code raw} (a JSON string, number or boolean).
     *
     * @throws IllegalArgumentException with a Vietnamese message when the type, range or allowed values do not match
     */
    public String normalize(Object raw) {
        if (raw == null) throw new IllegalArgumentException("Vui lòng nhập giá trị.");
        if (!(raw instanceof String || raw instanceof Number || raw instanceof Boolean)) {
            throw new IllegalArgumentException("Giá trị không đúng kiểu.");
        }
        String text = raw.toString().trim();
        return switch (type) {
            case INT -> {
                if (!text.matches("-?\\d{1,10}")) throw new IllegalArgumentException("Giá trị phải là số nguyên.");
                long number = Long.parseLong(text);
                if (number < min || number > max) {
                    throw new IllegalArgumentException("Giá trị phải từ " + min + " đến " + max + ".");
                }
                yield Long.toString(number);
            }
            case BOOLEAN -> {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("Giá trị phải là true hoặc false.");
                }
                yield text.toLowerCase();
            }
            case ENUM -> {
                if (!allowedValues.contains(text)) {
                    throw new IllegalArgumentException("Giá trị phải là một trong: " + String.join(", ", allowedValues) + ".");
                }
                yield text;
            }
            case STRING -> {
                if (text.length() > MAX_LENGTH) {
                    throw new IllegalArgumentException("Giá trị tối đa " + MAX_LENGTH + " ký tự.");
                }
                yield text;
            }
        };
    }

    public String key() { return key; }
    public String group() { return group; }
    public String label() { return label; }
    public String description() { return description; }
    public Type type() { return type; }
    public String defaultValue() { return defaultValue; }
    public Integer min() { return min; }
    public Integer max() { return max; }
    public List<String> allowedValues() { return allowedValues; }
    public boolean secret() { return secret; }
}
