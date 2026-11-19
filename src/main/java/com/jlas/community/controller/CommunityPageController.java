package com.jlas.community.controller;

import com.jlas.community.enums.CommunityContentType;
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

    /** Trang một bài viết: link chia sẻ và link từ thông báo bình luận. */
    @GetMapping("/community/posts/{postId}")
    public String post(@PathVariable Long postId, Model model) {
        model.addAttribute("postId", postId);
        return "community/post";
    }

    @GetMapping("/community/{contentType}/{contentId}")
    public String detail(@PathVariable String contentType, @PathVariable Long contentId, Model model) {
        if (CommunityContentType.POST.equals(contentType)) return "redirect:/community/posts/" + contentId;
        model.addAttribute("contentType", contentType);
        model.addAttribute("contentId", contentId);
        return "community/detail";
    }
}
