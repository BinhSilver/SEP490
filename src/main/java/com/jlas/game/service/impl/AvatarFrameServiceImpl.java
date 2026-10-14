package com.jlas.game.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.config.GameProperties;
import com.jlas.game.dto.AvatarFrameDtos.CosmeticsView;
import com.jlas.game.dto.AvatarFrameDtos.FrameView;
import com.jlas.game.dto.AvatarFrameDtos.NameStyleView;
import com.jlas.game.repository.AvatarFrameJdbcRepository;
import com.jlas.game.repository.AvatarFrameJdbcRepository.Style;
import com.jlas.game.repository.GameShopJdbcRepository;
import com.jlas.game.runtime.ArenaRateLimiter;
import com.jlas.game.service.AvatarFrameService;
import com.jlas.game.util.AvatarFrameCatalog;
import com.jlas.game.util.AvatarFrameCatalog.Frame;
import com.jlas.moderation.credit.model.UserCreditTransaction.CreditReason;
import com.jlas.moderation.credit.service.UserCreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.dao.DataAccessException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AvatarFrameServiceImpl implements AvatarFrameService {

    /** reference_type trong sổ CP; reference_id là {@link Frame#id()}, nên mỗi khung chỉ bị trừ tiền một lần. */
    static final String CREDIT_REFERENCE = "AVATAR_FRAME";

    private final AvatarFrameJdbcRepository frames;
    private final GameShopJdbcRepository shop;
    private final UserCreditService credits;
    private final ArenaRateLimiter rateLimiter;
    private final GameProperties properties;

    @Override
    @Transactional(readOnly = true)
    public CosmeticsView view(long userId) {
        Set<String> owned = frames.ownedCodes(userId);
        Style style = valid(frames.style(userId), owned);
        List<FrameView> frameViews = AvatarFrameCatalog.all().stream()
                .map(frame -> new FrameView(frame.code(), frame.name(), frame.description(), frame.price(),
                        frame.image(), frame.nameStyle(), frame.nameStyleLabel(), owned.contains(frame.code()),
                        frame.code().equals(style.frameCode())))
                .toList();
        List<NameStyleView> nameStyles = new ArrayList<>();
        nameStyles.add(new NameStyleView(null, "Mặc định", true, style.nameStyle() == null));
        for (Frame frame : AvatarFrameCatalog.all()) {
            nameStyles.add(new NameStyleView(frame.nameStyle(), frame.nameStyleLabel(), owned.contains(frame.code()),
                    frame.nameStyle().equals(style.nameStyle())));
        }
        return new CosmeticsView(credits.balance(userId), style.frameCode(), style.nameStyle(), frameViews, nameStyles);
    }

    @Override
    @Transactional
    public CosmeticsView buy(long userId, String frameCode) {
        rateLimiter.check(userId, "shop", properties.arena().rateLimit().settingsPerMinute());
        Frame frame = AvatarFrameCatalog.require(frameCode);
        shop.lockUser(userId);
        // Đã có rồi (bấm mua hai lần) thì không trừ CP nữa, chỉ dùng lại khung đó.
        if (!frames.owns(userId, frame.code())) {
            if (frame.price() > 0) credits.spend(userId, frame.price(), CreditReason.GAME_ITEM_PURCHASE,
                    CREDIT_REFERENCE, frame.id(), "Mua khung " + frame.name());
            frames.addOwned(userId, frame.code(), frame.price());
            log.info("User {} mua khung {} giá {} CP", userId, frame.code(), frame.price());
        }
        frames.saveStyle(userId, frame.code(), frame.nameStyle());
        return view(userId);
    }

    @Override
    @Transactional
    public CosmeticsView equip(long userId, String frameCode, String nameStyle) {
        rateLimiter.check(userId, "shop", properties.arena().rateLimit().settingsPerMinute());
        String frame = blankToNull(frameCode);
        String name = blankToNull(nameStyle);
        if (frame != null && !frames.owns(userId, AvatarFrameCatalog.require(frame).code())) {
            throw new ApiException(ErrorCode.CONFLICT, "Bạn chưa mua khung này");
        }
        if (name != null) {
            Frame unlockedBy = AvatarFrameCatalog.byNameStyle(name)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy màu tên này"));
            if (!frames.owns(userId, unlockedBy.code())) {
                throw new ApiException(ErrorCode.CONFLICT, "Mua khung " + unlockedBy.name() + " để mở màu tên này");
            }
        }
        frames.saveStyle(userId, frame, name);
        return view(userId);
    }

    /**
     * Không đặt @Transactional: hàm này chạy bên trong giao dịch đăng nhập / cập nhật hồ sơ, lỗi đọc ở đây
     * không được đánh dấu rollback giao dịch bên ngoài (UserMapper bắt lỗi và bỏ qua khung).
     */
    @Override
    public Style style(long userId) {
        Style style = frames.style(userId);
        if (style.frameCode() == null && style.nameStyle() == null) return style;
        return valid(style, frames.ownedCodes(userId));
    }

    /** Không @Transactional, cùng lý do với {@link #style(long)}: được gọi bên trong giao dịch của tính năng khác. */
    @Override
    public Map<Long, Style> styles(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        try {
            Map<Long, Style> saved = frames.styles(ids);
            if (saved.isEmpty()) return Map.of();
            Map<Long, Set<String>> owned = frames.ownedCodes(saved.keySet());
            Map<Long, Style> result = new HashMap<>();
            saved.forEach((userId, style) -> {
                Style checked = valid(style, owned.getOrDefault(userId, Set.of()));
                if (checked.frameCode() != null || checked.nameStyle() != null) result.put(userId, checked);
            });
            return result;
        } catch (DataAccessException ex) {
            log.warn("Không đọc được khung avatar của {} người: {}", ids.size(), ex.getMessage());
            return Map.of();
        }
    }

    /** Bỏ khung/màu không còn trong danh mục hoặc chưa mua, thay vì hiển thị món không hợp lệ. */
    private static Style valid(Style style, Set<String> owned) {
        String frame = AvatarFrameCatalog.find(style.frameCode() == null ? "" : style.frameCode())
                .filter(item -> owned.contains(item.code())).map(Frame::code).orElse(null);
        String name = AvatarFrameCatalog.byNameStyle(style.nameStyle() == null ? "" : style.nameStyle())
                .filter(item -> owned.contains(item.code())).map(Frame::nameStyle).orElse(null);
        return new Style(frame, name);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
