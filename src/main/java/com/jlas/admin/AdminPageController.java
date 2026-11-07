package com.jlas.admin;

import com.jlas.auth.security.CurrentUser;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared admin workspace. Every management area is a section rendered inside the same shell;
 * access rules live in SecurityConfig and on each API.
 */
@Controller
public class AdminPageController {
    private static final Set<String> MODERATION_SECTIONS =
            Set.of("reports", "queue", "appeals", "dictionary", "denylist", "sanctions", "audit");
    private static final Set<String> SYSTEM_SECTIONS = Set.of("dashboard", "users", "plans", "coupons", "credit-packages", "payments", "audit",
            "config", "permissions");

    @GetMapping({"/admin", "/admin/"})
    public String overview(Model model) {
        // System Admin starts on its dashboard, which also shows the moderation backlog.
        if (CurrentUser.hasRole("ROLE_ADMIN")) {
            return "redirect:/admin/system/dashboard";
        }
        model.addAttribute("section", "overview");
        return "admin/workspace";
    }

    @GetMapping("/admin/system/{section}")
    public String system(@PathVariable String section, Model model) {
        if (!SYSTEM_SECTIONS.contains(section)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("section", "system-" + section);
        return "admin/workspace";
    }

    @GetMapping("/admin/moderation/{section}")
    public String moderation(@PathVariable String section, Model model) {
        if (!MODERATION_SECTIONS.contains(section)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("section", section);
        return "admin/workspace";
    }

    @GetMapping("/admin/game-effects")
    public String gameEffects(Model model) {
        model.addAttribute("section", "game-effects");
        return "admin/workspace";
    }

    @GetMapping("/admin/game-skins")
    public String gameSkins(Model model) {
        model.addAttribute("section", "game-skins");
        return "admin/workspace";
    }

    @GetMapping("/admin/avatar-frames")
    public String avatarFrames(Model model) {
        model.addAttribute("section", "avatar-frames");
        return "admin/workspace";
    }

    @GetMapping("/admin/game-effects/preview")
    public String gameEffectsPreview() {
        return "admin/game-effects-preview";
    }
}
