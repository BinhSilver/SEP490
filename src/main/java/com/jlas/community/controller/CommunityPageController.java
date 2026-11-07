package com.jlas.community.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/** Trang Community Hub (cần đăng nhập theo mặc định của SecurityConfig). */
@Controller
public class CommunityPageController {

    @GetMapping("/community")
    public String feed() {
        return "community/index";
    }

    @GetMapping("/community/{contentType}/{contentId}")
    public String detail(@PathVariable String contentType, @PathVariable Long contentId, Model model) {
        model.addAttribute("contentType", contentType);
        model.addAttribute("contentId", contentId);
        return "community/detail";
    }
}
