package com.jlas.game.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.dto.ShopDtos.ShopItemView;
import com.jlas.game.dto.ShopDtos.ShopSectionView;
import com.jlas.game.dto.ShopDtos.ShopView;
import com.jlas.game.enums.GameType;
import com.jlas.game.repository.GameShopJdbcRepository;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.service.GameShopService;
import com.jlas.game.service.GameSkinCatalogService;
import com.jlas.game.util.GameRules;
import com.jlas.game.util.GameShopCatalog;
import com.jlas.game.util.GameShopCatalog.ShopItem;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameShopServiceImpl implements GameShopService {

    /** reference_type trong sổ CP; reference_id là {@link ShopItem#id()}, nên mỗi món chỉ bị trừ tiền một lần. */
    static final String CREDIT_REFERENCE = "GAME_ITEM";

    private final GameShopJdbcRepository shop;
    private final UserCreditService credits;
    private final ArenaRateLimiter rateLimiter;
    private final GameProperties properties;
    private final GameSkinCatalogService catalog;

    @Override
    @Transactional(readOnly = true)
    public ShopView shop(long userId) {
        Set<String> owned = shop.ownedCodes(userId);
        var sections = GameShopCatalog.SHOP_GAMES.stream().map(gameType -> {
            String equipped = equippedCode(userId, gameType, owned);
            var items = catalog.all().stream().filter(item -> item.gameType() == gameType)
                    .filter(item -> item.starter() || (item.active() && !item.deleted()) || owned.contains(item.code()))
                    .map(item -> new ShopItemView(item.code(), item.name(), item.description(), item.price(),
                            item.image(), item.starter(), item.starter() || owned.contains(item.code()),
                            item.code().equals(equipped), item.id(), item.renderCode()))
                    .toList();
            return new ShopSectionView(gameType, GameRules.gameName(gameType), GameShopCatalog.sectionTitle(gameType),
                    equipped, items);
        }).toList();
        return new ShopView(credits.balance(userId), sections);
    }

    @Override
    @Transactional
    public ShopView buy(long userId, String itemCode) {
        rateLimiter.check(userId, "shop", properties.arena().rateLimit().settingsPerMinute());
        ShopItem item = catalog.require(itemCode);
        if (!item.active() || item.deleted()) throw new ApiException(ErrorCode.CONFLICT, "Trang phục đã ngừng bán");
        if (item.starter()) {
            throw new ApiException(ErrorCode.CONFLICT, "Món này có sẵn, không cần mua");
        }
        shop.lockUser(userId);
        // Đã có rồi (bấm mua hai lần) thì không trừ CP nữa, chỉ dùng lại món đó.
        if (!shop.owns(userId, item.code())) {
            if (item.price() > 0) credits.spend(userId, item.price(), CreditReason.GAME_ITEM_PURCHASE, CREDIT_REFERENCE, item.id(),
                    "Mua " + item.name());
            shop.addOwned(userId, item.code(), item.price());
            log.info("User {} mua {} giá {} CP", userId, item.code(), item.price());
        }
        shop.equip(userId, item.gameType(), item.code());
        return shop(userId);
    }

    @Override
    @Transactional
    public ShopView equip(long userId, String itemCode) {
        rateLimiter.check(userId, "shop", properties.arena().rateLimit().settingsPerMinute());
        ShopItem item = catalog.require(itemCode);
        if (!item.starter() && !shop.owns(userId, item.code())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bạn chưa mua món này");
        }
        shop.equip(userId, item.gameType(), item.code());
        return shop(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public String equippedCode(long userId, GameType gameType) {
        return equippedCode(userId, gameType, null);
    }

    /** Món đang dùng còn hợp lệ (còn trong danh mục, đúng game, đã mua) thì giữ, không thì về món có sẵn. */
    private String equippedCode(long userId, GameType gameType, Set<String> owned) {
        ShopItem starter = catalog.starter(gameType);
        if (starter == null) {
            return null;
        }
        return shop.equipped(userId, gameType)
                .flatMap(catalog::find)
                .filter(item -> item.gameType() == gameType)
                .filter(item -> item.starter() || (owned != null ? owned.contains(item.code()) : shop.owns(userId, item.code())))
                .map(ShopItem::code)
                .orElse(starter.code());
    }
}
