package com.jlas.game.enums;

/**
 * Bậc trang phục trong Cửa hàng Trò chơi (kế hoạch Pro/CP, "Sản phẩm bán bằng CP"). Bậc quyết định giá:
 * Thường 500, Hiếm 800, Huyền thoại 1.000 CP. Trang phục mặc định của mỗi game luôn miễn phí, bậc nào cũng vậy.
 */
public enum SkinTier {
    COMMON("Thường", 500),
    RARE("Hiếm", 800),
    LEGENDARY("Huyền thoại", 1000);

    private final String label;
    private final int price;

    SkinTier(String label, int price) {
        this.label = label;
        this.price = price;
    }

    public String label() { return label; }
    public int price() { return price; }

    /** Giá của trang phục: 0 cho trang phục mặc định, còn lại theo bậc. */
    public int priceFor(boolean starter) {
        return starter ? 0 : price;
    }

    /** Bậc gần nhất với một giá cũ (dữ liệu trước khi có bậc, hoặc request chưa gửi bậc). */
    public static SkinTier fromPrice(int price) {
        if (price >= LEGENDARY.price) return LEGENDARY;
        if (price >= RARE.price) return RARE;
        return COMMON;
    }

    /** Đọc bậc lưu trong DB; null hay giá trị lạ thì suy ra từ giá. */
    public static SkinTier parse(String value, int price) {
        if (value != null) {
            for (SkinTier tier : values()) {
                if (tier.name().equals(value)) return tier;
            }
        }
        return fromPrice(price);
    }
}
