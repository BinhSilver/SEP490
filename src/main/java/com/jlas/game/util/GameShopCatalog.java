package com.jlas.game.util;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.enums.GameType;

import java.util.List;
import java.util.Optional;

/**
 * Món bán trong Cửa hàng Trò chơi: trang phục mèo (Bắt cá) và máy bay (Bắn máy bay). Chỉ để trang trí,
 * không đổi sức mạnh, nên trận đấu tay đôi vẫn công bằng. Các mẫu gốc dùng để khởi tạo danh mục DB
 * và xác định bộ chuyển động mà admin có thể chọn cho trang phục mới.
 */
public final class GameShopCatalog {

    /**
     * {@code id} cố định: dùng làm reference_id trong sổ CP để mỗi món chỉ bị trừ tiền một lần.
     * {@code renderCode}: mã chuyển động có sẵn trong game, {@code PACK} = gói skin admin tải lên ({@code packPath}),
     * {@code CUSTOM} = ảnh tĩnh (kiểu cũ, chỉ còn cho trang phục đã tạo trước đây).
     * {@code weaponTheme}: máy bay dùng gói skin mượn bộ vũ khí của máy bay gốc nào (null = vũ khí mặc định).
     */
    public record ShopItem(long id, String code, GameType gameType, String name, String description, int price,
                           String image, boolean starter, String renderCode, boolean active, boolean deleted,
                           String packPath, String weaponTheme) {
        public ShopItem(long id, String code, GameType gameType, String name, String description, int price,
                        String image, boolean starter) {
            this(id, code, gameType, name, description, price, image, starter, code, true, false, null, null);
        }
    }

    public static final String RENDER_PACK = "PACK";
    public static final String RENDER_CUSTOM = "CUSTOM";

    private static final List<ShopItem> ITEMS = List.of(
            new ShopItem(1101, "cat-classic", GameType.NEKO_CATCH, "Mèo cam",
                    "Mèo cam!", 0, "/arena/images/shop/cat-classic.webp", true),
            new ShopItem(1102, "cat-cowboy", GameType.NEKO_CATCH, "Mèo cao bồi",
                    "Có phải mèo đi hia không?", 150, "/arena/images/shop/cat-cowboy.webp", false),
            new ShopItem(1103, "cat-wizard", GameType.NEKO_CATCH, "Mèo phù thủy",
                    "Úm ba la xì bùa!", 200, "/arena/images/shop/cat-wizard.webp", false),
            new ShopItem(1104, "cat-godzilla", GameType.NEKO_CATCH, "Godzilla",
                    "ROAAAR!", 200,
                    "/arena/app/neko/sprites/portraits/godzilla.png", false),
            new ShopItem(1105, "cat-ronova", GameType.NEKO_CATCH, "Ronova",
                    "Ronovaaaaaaaaa!", 200,
                    "/arena/app/neko/sprites/portraits/ronova.png", false),
            new ShopItem(1106, "cat-zenitsu", GameType.NEKO_CATCH, "Zenitsu",
                    "Hơi thở của Sấm Sét!", 200,
                    "/arena/app/neko/sprites/portraits/zenitsu.png", false),
            new ShopItem(1107, "cat-tanjiro", GameType.NEKO_CATCH, "Tanjiro",
                    "Hãy đợi đó, Muzan!", 200,
                    "/arena/app/neko/sprites/portraits/tanjiro.png", false),
            new ShopItem(1201, "plane-blue", GameType.AIR_DEFENSE, "Máy bay xanh",
                    "Máy bay mặc định.", 0, "/arena/images/shop/plane-blue.png", true),
            new ShopItem(1206, "plane-halo-fighter", GameType.AIR_DEFENSE, "Thiên Luân Phán Xét",
                    "Một vòng sáng xuất hiện giữa bóng tối — không phải bình minh, mà là phán quyết.", 200, "/arena/app/air-defense/assets/space/skins/halo-fighter/body.png", false),
            new ShopItem(1207, "plane-aegis-frame", GameType.AIR_DEFENSE, "Vệ Thần Thiên Giới",
                    "Thiên giới không cần quân đoàn. Một mình ta đã là quân đoàn.", 200, "/arena/app/air-defense/assets/space/skins/aegis-frame/body.png", false),
            new ShopItem(1208, "plane-split-core", GameType.AIR_DEFENSE, "Vệ Binh Tinh Tú",
                    "Giữa hàng tỷ ngôi sao, chỉ có một ánh sáng không bao giờ tắt.", 200, "/arena/app/air-defense/assets/space/skins/split-core/body.png", false),
            new ShopItem(1209, "plane-void-blade", GameType.AIR_DEFENSE, "Tử Thần",
                    "Kẻ địch gọi nó là bóng ma. Những người sống sót gọi nó bằng một cái tên khác: Tử Thần.", 200, "/arena/app/air-defense/assets/space/skins/void-blade/body.png", false)
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
