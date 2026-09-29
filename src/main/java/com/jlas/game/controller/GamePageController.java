package com.jlas.game.controller;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.game.dto.GameDtos.SessionInfoView;
import com.jlas.game.enums.GameType;
import com.jlas.game.service.GameService;
import com.jlas.game.service.GameShopService;
import com.jlas.game.util.GameRules;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Trang Game Arena: sảnh chọn game, trang thiết lập (tạo phòng / chơi đơn), phòng chờ và trang chơi
 * của từng game. Trang chơi chỉ mở cho người tham gia trận; người khác được đưa về sảnh.
 */
@Controller
@RequestMapping("/games")
@RequiredArgsConstructor
public class GamePageController {
    private final GameService gameService;
    private final GameShopService shopService;

    @GetMapping
    public String hub(Model model) {
        model.addAttribute("games", GameRules.catalog());
        model.addAttribute("pageTitle", "Trò chơi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "arena/games";
    }

    /** Thiết lập trận: ?game=NEKO_CATCH&mode=VERSUS|SOLO&setId=... (đều tuỳ chọn). */
    @GetMapping("/lobby")
    public String setup(Model model) {
        model.addAttribute("pageTitle", "Thiết lập trận - Trò chơi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "arena/lobby";
    }

    /** Cửa hàng: trang phục mèo và máy bay, mua bằng CP chung. */
    @GetMapping("/shop")
    public String shop(Model model) {
        model.addAttribute("pageTitle", "Cửa hàng - Trò chơi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "arena/shop";
    }

    @GetMapping("/room/{roomId}")
    public String room(@PathVariable String roomId, Model model) {
        model.addAttribute("roomId", roomId);
        model.addAttribute("pageTitle", "Phòng đấu - Trò chơi - JLAS");
        model.addAttribute("layoutMode", "app");
        return "arena/room";
    }

    @GetMapping("/neko/{sessionId}")
    public String neko(@PathVariable String sessionId, @AuthenticationPrincipal UserPrincipal user, Model model) {
        return play(GameType.NEKO_CATCH, "neko/board", sessionId, user, model);
    }

    @GetMapping("/memory/{sessionId}")
    public String memory(@PathVariable String sessionId, @AuthenticationPrincipal UserPrincipal user, Model model) {
        return play(GameType.MEMORY_MATCH, "memory/board", sessionId, user, model);
    }

    @GetMapping("/air-defense/{sessionId}")
    public String airDefense(@PathVariable String sessionId, @AuthenticationPrincipal UserPrincipal user,
                             Model model) {
        return play(GameType.AIR_DEFENSE, "air-defense/board", sessionId, user, model);
    }

    private String play(GameType type, String view, String sessionId, UserPrincipal user, Model model) {
        SessionInfoView session;
        try {
            session = gameService.session(user.userId(), sessionId);
        } catch (ApiException ex) {
            return "redirect:/games";
        }
        if (session.gameType() != type) {
            return "redirect:" + session.playUrl();
        }
        model.addAttribute("session", session);
        // Trang phục mèo / máy bay đang dùng; chọn ở Cửa hàng, trong trận không đổi được.
        model.addAttribute("skin", shopService.equippedCode(user.userId(), type));
        model.addAttribute("sessionId", sessionId);
        model.addAttribute("pageTitle", session.deckTitle() + " - Trò chơi - JLAS");
        return view;
    }
}
