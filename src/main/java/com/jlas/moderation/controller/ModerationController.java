package com.jlas.moderation.controller;

import com.jlas.moderation.service.ModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller API kiểm duyệt nội dung. Base path: /api/moderation */
@RestController
@RequestMapping("/api/moderation")
@RequiredArgsConstructor
public class ModerationController {
    private final ModerationService moderationService;
}
