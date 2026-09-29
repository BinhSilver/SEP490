package com.jlas.auth.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthPageController {
    @Value("${app.google.client-id:}")
    private String googleClientId;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("layoutMode", "marketing");
        return "index";
    }

    @GetMapping("/login")
    public String login(Model model) {
        authModel(model, "/", "Trang chủ");
        model.addAttribute("googleClientId", googleClientId);
        return "login";
    }

    @GetMapping("/register")
    public String register(Model model) {
        authModel(model, "/", "Trang chủ");
        model.addAttribute("googleClientId", googleClientId);
        return "register";
    }

    @GetMapping("/forgot-password")
    public String forgotPassword(Model model) {
        authModel(model, "/login", "Về trang đăng nhập");
        return "forgot-password";
    }

    @GetMapping("/verify-email")
    public String verifyEmail(Model model, @RequestParam(required = false) String email) {
        authModel(model, "/login", "Về trang đăng nhập");
        model.addAttribute("email", email == null ? "" : email);
        return "verify-email";
    }

    @GetMapping("/profile")
    public String profile(Model model, @RequestParam(required = false) String tab) {
        model.addAttribute("layoutMode", "app");
        model.addAttribute("activeTab", tab == null ? "overview" : tab);
        model.addAttribute("securitySection", "home");
        model.addAttribute("googleClientId", googleClientId);
        return "profile";
    }

    @GetMapping("/profile/security/{section}")
    public String profileSecurity(Model model, @PathVariable String section) {
        String resolved = switch (section) {
            case "password", "email", "google", "login-alerts" -> section;
            default -> "home";
        };
        model.addAttribute("layoutMode", "app");
        model.addAttribute("activeTab", "security");
        model.addAttribute("securitySection", resolved);
        model.addAttribute("googleClientId", googleClientId);
        return "profile";
    }

    @GetMapping({"/goals", "/learning/goals", "/streak"})
    public String goals(Model model) {
        model.addAttribute("layoutMode", "app");
        return "goals";
    }

    @GetMapping("/progress")
    public String progress(Model model) {
        model.addAttribute("layoutMode", "app");
        return "progress";
    }

    @GetMapping("/credit-history")
    public String creditHistory(Model model) {
        model.addAttribute("layoutMode", "app");
        return "credit-history";
    }

    @GetMapping("/retention-demo")
    public String retentionDemo() {
        return "retention-demo";
    }

    private static void authModel(Model model, String backHref, String backLabel) {
        model.addAttribute("layoutMode", "auth");
        model.addAttribute("authBackHref", backHref);
        model.addAttribute("authBackLabel", backLabel);
    }
}
