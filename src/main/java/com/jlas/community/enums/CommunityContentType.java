package com.jlas.community.enums;

/**
 * Loại nội dung cộng đồng. Dùng String (không phải enum) để thêm loại mới chỉ cần thêm một
 * {@code CommunityContentTypeHandler}, không sửa core Community.
 */
public final class CommunityContentType {
    public static final String FLASHCARD_SET = "FLASHCARD_SET";
    /** Video Shadowing: xem {@code ShadowingVideoCommunityHandler}. */
    public static final String SHADOWING_VIDEO = "SHADOWING_VIDEO";

    private CommunityContentType() {
    }

    public static String label(String type) {
        if (FLASHCARD_SET.equals(type)) return "Bộ flashcard";
        if (SHADOWING_VIDEO.equals(type)) return "Video Shadowing";
        return type;
    }
}
