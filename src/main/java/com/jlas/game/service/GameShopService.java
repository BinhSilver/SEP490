package com.jlas.game.service;

import com.jlas.game.dto.ShopDtos.ShopView;
import com.jlas.game.enums.GameType;

/** Cửa hàng Trò chơi: mua trang phục mèo / máy bay bằng CP chung và chọn món đang dùng. */
public interface GameShopService {

    ShopView shop(long userId);

    /** Trừ CP một lần cho mỗi món, thêm vào đồ đã có và dùng luôn món vừa mua. */
    ShopView buy(long userId, String itemCode);

    /** Chỉ dùng được món đã mua hoặc món có sẵn. */
    ShopView equip(long userId, String itemCode);

    /** Mã món người chơi đang dùng cho game; món có sẵn nếu chưa chọn. Null nếu game không có món nào. */
    String equippedCode(long userId, GameType gameType);
}
