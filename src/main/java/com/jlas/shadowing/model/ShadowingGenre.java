package com.jlas.shadowing.model;

import java.util.Arrays;
import java.util.Optional;

/**
 * Story genres of a Shadowing video. A video may have several (Hành động + Học đường), unlike its single
 * format in {@link CategoryConstants} (Anime, News…). Stored as VIDEO tags whose code is {@link #tagCode()}.
 */
public enum ShadowingGenre {
    ACTION("Hành động"),
    ROMANCE("Lãng mạn"),
    SCHOOL("Học đường"),
    COMEDY("Hài hước"),
    SLICE_OF_LIFE("Đời thường"),
    ADVENTURE("Phiêu lưu"),
    FANTASY("Kỳ ảo"),
    SCI_FI("Khoa học viễn tưởng"),
    SUPERNATURAL("Siêu nhiên"),
    MYSTERY("Bí ẩn / Trinh thám"),
    HORROR("Kinh dị"),
    SPORTS("Thể thao"),
    MUSIC("Âm nhạc"),
    FAMILY("Gia đình"),
    FOOD("Ẩm thực"),
    WORK("Công việc"),
    TRAVEL("Du lịch"),
    HISTORY("Lịch sử"),
    HEARTWARMING("Cảm động");

    public static final String TAG_PREFIX = "VIDEO_GENRE_";
    /** Enough to describe a video while keeping each genre filter meaningful. */
    public static final int MAX_PER_VIDEO = 5;

    private final String label;

    ShadowingGenre(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String tagCode() {
        return TAG_PREFIX + name();
    }

    public static Optional<ShadowingGenre> fromCode(String code) {
        if (code == null) return Optional.empty();
        String value = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.startsWith(TAG_PREFIX)) value = value.substring(TAG_PREFIX.length());
        String name = value;
        return Arrays.stream(values()).filter(genre -> genre.name().equals(name)).findFirst();
    }

    public static boolean isGenreTag(Tag tag) {
        return tag != null && tag.getCode() != null && tag.getCode().startsWith(TAG_PREFIX);
    }
}
