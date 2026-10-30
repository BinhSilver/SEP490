package com.jlas.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jlas.game.enums.GameType;

import java.util.List;

/** DTO của Cửa hàng Trò chơi. */
public final class ShopDtos {

    private ShopDtos() {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    /** {@code packPath}: thư mục gói skin (khi renderCode = PACK); {@code weaponTheme}: bộ vũ khí máy bay mượn. */
    public record ShopItemView(String code, String name, String description, int price, String image,
                               boolean starter, boolean owned, boolean equipped, long id, String renderCode,
                               String packPath, String weaponTheme) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ShopSectionView(GameType gameType, String gameName, String title, String equippedCode,
                                  List<ShopItemView> items) {
    }

    /** {@code balance}: số CP hiện có (CP chung, kiếm từ nhiệm vụ hằng ngày). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ShopView(long balance, List<ShopSectionView> sections) {
    }
}
