package com.jlas.notification.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Full notification history page; the bell panel links here. */
@Controller
public class NotificationPageController {

    @GetMapping("/notifications")
    public String page() {
        return "notifications";
    }
}
