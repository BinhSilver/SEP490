package com.jlas.shadowing.controller;

import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.dto.ShadowingProgressDto;
import com.jlas.shadowing.service.ShadowingProgressService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shadowing-progress")
@RequiredArgsConstructor
public class ShadowingProgressController {
    private final ShadowingProgressService progress;

    @GetMapping
    public ShadowingProgressDto mine() {
        return progress.forUser(CurrentUser.requireUserId());
    }
}
