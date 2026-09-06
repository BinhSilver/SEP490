package com.jlas.kaiwa.controller;

import com.jlas.kaiwa.service.KaiwaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller API hội thoại AI. Base path: /api/kaiwa */
@RestController
@RequestMapping("/api/kaiwa")
@RequiredArgsConstructor
public class KaiwaController {
    private final KaiwaService kaiwaService;
}
