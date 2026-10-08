package com.jlas.game.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.enums.GameType;

import java.util.List;
import java.util.Optional;

/**
 * Món bán trong Cửa hàng Trò chơi: trang phục mèo (Bắt cá) và máy bay (Bắn máy bay). Chỉ để trang trí,
 * không đổi sức mạnh, nên trận đấu tay đôi vẫn công bằng. Danh mục nằm trong code vì mỗi món gắn với
 * ảnh/sprite của game; DB chỉ lưu món đã mua và món đang dùng.
 */
public final class GameShopCatalog {

    /** {@code id} cố định: dùng làm reference_id trong sổ CP để mỗi món chỉ bị trừ tiền một lần. */
    public record ShopItem(long id, String code, GameType gameType, String name, String description, int price,
                           String image, boolean starter) {
    }

    private static final List<ShopItem> ITEMS = List.of(
            new ShopItem(1101, "cat-classic", GameType.NEKO_CATCH, "Mèo cam",
                    "Chú mèo cam ôm giỏ, có sẵn cho mọi người.", 0, "/arena/images/shop/cat-classic.webp", true),
            new ShopItem(1102, "cat-cowboy", GameType.NEKO_CATCH, "Mèo cao bồi",
                    "Đội mũ cao bồi, có cảnh ăn mừng riêng khi thắng.", 150, "/arena/images/shop/cat-cowboy.webp", false),
            new ShopItem(1103, "cat-wizard", GameType.NEKO_CATCH, "Mèo phù thủy",
                    "Áo choàng tím và nồi phép thuật.", 200, "/arena/images/shop/cat-wizard.webp", false),
            new ShopItem(1104, "cat-godzilla", GameType.NEKO_CATCH, "Godzilla",
                    "Godzilla chibi ôm giỏ bắt cá, với bộ chuyển động riêng.", 200,
                    "/arena/app/neko/sprites/portraits/godzilla.png", false),
            new ShopItem(1105, "cat-ronova", GameType.NEKO_CATCH, "Ronova",
                    "Mái tóc trắng và trang phục đỏ đen của Ronova.", 200,
                    "/arena/app/neko/sprites/portraits/ronova.png", false),
            new ShopItem(1106, "cat-zenitsu", GameType.NEKO_CATCH, "Zenitsu",
                    "Áo haori vàng họa tiết tam giác và chiếc giỏ bắt cá.", 200,
                    "/arena/app/neko/sprites/portraits/zenitsu.png", false),
            new ShopItem(1107, "cat-tanjiro", GameType.NEKO_CATCH, "Tanjiro",
                    "Áo haori caro xanh đen và bộ chuyển động riêng của Tanjiro.", 200,
                    "/arena/app/neko/sprites/portraits/tanjiro.png", false),
            new ShopItem(1201, "plane-blue", GameType.AIR_DEFENSE, "Máy bay xanh",
                    "Máy bay mặc định, có sẵn cho mọi người.", 0, "/arena/images/shop/plane-blue.png", true),
            new ShopItem(1202, "plane-red", GameType.AIR_DEFENSE, "Máy bay đỏ",
                    "Thân đỏ rực.", 100, "/arena/images/shop/plane-red.png", false),
            new ShopItem(1203, "plane-green", GameType.AIR_DEFENSE, "Máy bay xanh lá",
                    "Thân xanh lá.", 100, "/arena/images/shop/plane-green.png", false),
            new ShopItem(1204, "plane-violet", GameType.AIR_DEFENSE, "Máy bay tím",
                    "Thân tím.", 100, "/arena/images/shop/plane-violet.png", false),
            new ShopItem(1205, "plane-gold", GameType.AIR_DEFENSE, "Máy bay vàng",
                    "Thân vàng óng.", 200, "/arena/images/shop/plane-gold.png", false)
    );

    /** Thứ tự các mục trong cửa hàng; game khác (Lật thẻ) chưa có món nào. */
    public static final List<GameType> SHOP_GAMES = List.of(GameType.NEKO_CATCH, GameType.AIR_DEFENSE);

    private GameShopCatalog() {
    }

    public static List<ShopItem> items(GameType gameType) {
        return ITEMS.stream().filter(item -> item.gameType() == gameType).toList();
    }

    public static Optional<ShopItem> find(String code) {
        return ITEMS.stream().filter(item -> item.code().equals(code)).findFirst();
    }

    public static ShopItem require(String code) {
        return find(code).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy món này trong cửa hàng"));
    }

    /** Món có sẵn của game (dùng khi người chơi chưa chọn gì); null nếu game không có món nào. */
    public static ShopItem starter(GameType gameType) {
        return ITEMS.stream().filter(item -> item.gameType() == gameType && item.starter()).findFirst().orElse(null);
    }

    public static String sectionTitle(GameType gameType) {
        return gameType == GameType.NEKO_CATCH ? "Nhân vật và trang phục" : "Máy bay";
    }
}
