package com.jlas.jlptpath.enums;

/**
 * [JLAS-W0108] Badge catalog of the JLPT path (jpath_user_badge.badge_code). Append only: the {@code ordinal} is the
 * CP reference id of the badge reward ({@code JPATH_BADGE}).
 */
public enum JpathBadge {
    STREAK_7("Chuỗi 7 ngày", "Học đủ 7 ngày liên tiếp.", 7),
    STREAK_30("Chuỗi 30 ngày", "Học đủ 30 ngày liên tiếp.", 30),
    KNOWN_100("100 từ đã học", "Học được 100 từ vựng.", 100),
    KNOWN_500("500 từ đã học", "Học được 500 từ vựng.", 500),
    KNOWN_1000("1.000 từ đã học", "Học được 1.000 từ vựng.", 1000),
    KANA_DONE("Thuộc bảng chữ cái", "Học xong bảng Hiragana và Katakana.", 1),
    N5_SKILL_STAGE("Vào chặng Rèn N5", "Đạt 80% nội dung N5 và bước sang chặng rèn kỹ năng.", 1);

    private final String titleVi;
    private final String descriptionVi;
    private final int goal;

    JpathBadge(String titleVi, String descriptionVi, int goal) {
        this.titleVi = titleVi;
        this.descriptionVi = descriptionVi;
        this.goal = goal;
    }

    public String titleVi() {
        return titleVi;
    }

    public String descriptionVi() {
        return descriptionVi;
    }

    public int goal() {
        return goal;
    }
}
