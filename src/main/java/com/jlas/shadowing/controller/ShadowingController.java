package com.jlas.shadowing.controller;

import com.jlas.shadowing.service.ShadowingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller API shadowing. Base path: /api/shadowing */
@RestController
@RequestMapping("/api/shadowing")
@RequiredArgsConstructor
public class ShadowingController {
    private final ShadowingService shadowingService;
}
