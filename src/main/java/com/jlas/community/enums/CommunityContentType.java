package com.jlas.community.enums;

/**
 * Loại nội dung cộng đồng. Dùng String (không phải enum) để thêm loại mới chỉ cần thêm một
 * {@code CommunityContentTypeHandler}, không sửa core Community.
 */
public final class CommunityContentType {
    public static final String FLASHCARD_SET = "FLASHCARD_SET";
    /** Chưa có handler: S4-04 thêm cuối sprint. Không có handler thì loại này không xuất hiện ở feed. */
    public static final String SHADOWING_VIDEO = "SHADOWING_VIDEO";

    private CommunityContentType() {
    }

    public static String label(String type) {
        if (FLASHCARD_SET.equals(type)) return "Bộ flashcard";
        if (SHADOWING_VIDEO.equals(type)) return "Video Shadowing";
        return type;
    }
}
