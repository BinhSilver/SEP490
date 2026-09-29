package com.jlas.dictionary.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Trang Thymeleaf duyệt giáo trình số hoá. Chỉ trả view name;
 * dữ liệu do trang tự gọi /api/dictionary/** với JWT của người dùng
 * (cùng cách với /profile, /goals) nên quyền xem DRAFT/PUBLISHED luôn theo đúng role.
 * /textbooks/** nằm trong anyRequest().authenticated() của SecurityConfig:
 * khách chưa đăng nhập bị chuyển về /login?redirect=... và quay lại sau khi đăng nhập.
 */
@Controller
public class DictionaryPageController {

    @GetMapping("/textbooks")
    public String textbooks(Model model) {
        model.addAttribute("layoutMode", "app");
        return "textbooks/index";
    }

    @GetMapping("/textbooks/{textbookId}")
    public String textbookUnits(@PathVariable Long textbookId, Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("textbookId", textbookId);
        return "textbooks/units";
    }

    @GetMapping("/textbooks/{textbookId}/lessons/{lessonId}")
    public String lesson(@PathVariable Long textbookId, @PathVariable Long lessonId, Model model) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("textbookId", textbookId);
        model.addAttribute("lessonId", lessonId);
        return "textbooks/lesson";
    }
}
