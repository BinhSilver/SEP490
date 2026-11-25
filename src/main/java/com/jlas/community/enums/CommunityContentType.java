package com.jlas.community.enums;

/**
 * Loại nội dung cộng đồng. Dùng String (không phải enum) để thêm loại mới chỉ cần thêm một
 * {@code CommunityContentTypeHandler}, không sửa core Community.
 */
public final class CommunityContentType {
    public static final String FLASHCARD_SET = "FLASHCARD_SET";
    /** Video Shadowing: xem {@code ShadowingVideoCommunityHandler}. */
    public static final String SHADOWING_VIDEO = "SHADOWING_VIDEO";
    /** Bài viết người học tự đăng: xem {@code CommunityPostHandler}. */
    public static final String POST = "POST";

    private CommunityContentType() {
    }

    public static String label(String type) {
        if (FLASHCARD_SET.equals(type)) return "Bộ flashcard";
        if (SHADOWING_VIDEO.equals(type)) return "Video Shadowing";
        if (POST.equals(type)) return "Bài viết";
        return type;
    }

    /** Trang cộng đồng của nội dung: bài viết có trang riêng /community/posts/{id}. */
    public static String pageUrl(String type, Long contentId) {
        if (POST.equals(type)) return "/community/posts/" + contentId;
        return "/community/" + type + "/" + contentId;
    }
}
