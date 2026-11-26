package com.jlas.mypaths;

import com.jlas.auth.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** [JLAS-W0108] Owner 24/11: the "Lộ trình của tôi" dashboard page; its data comes from /api/learning/my-paths. */
@Controller
public class MyPathsPageController {

    @GetMapping("/learning/my")
    public String page(@AuthenticationPrincipal UserPrincipal user, Model model) {
        if (user == null) return "redirect:/login";
        model.addAttribute("layoutMode", "app");
        return "learning/my-paths";
    }
}
