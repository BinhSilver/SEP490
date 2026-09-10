package com.jlas.auth.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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
        return "profile";
    }

    @GetMapping({"/goals", "/learning/goals", "/streak"})
    public String goals(Model model) {
        model.addAttribute("layoutMode", "app");
        return "goals";
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
