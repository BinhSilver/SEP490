package com.jlas.shadowing.controller;

import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.dto.practice.*;
import com.jlas.shadowing.service.ShadowingPracticeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shadowing-practice")
public class ShadowingPracticeController {
    private final ShadowingPracticeService service;

    public ShadowingPracticeController(ShadowingPracticeService service) {
        this.service = service;
    }

    @PostMapping("/sessions")
    public CreatePracticeSessionResponse create(@Valid @RequestBody CreatePracticeSessionRequest request) {
        return service.create(CurrentUser.requireUserId(), request);
    }

    @GetMapping("/sessions/{sessionId}")
    public PracticeSessionView get(@PathVariable long sessionId) {
        return service.get(CurrentUser.requireUserId(), sessionId);
    }

    @PostMapping("/sessions/{sessionId}/answers")
    public PracticeAnswerResponse answer(@PathVariable long sessionId,
                                         @Valid @RequestBody SubmitPracticeAnswerRequest request) {
        return service.submit(CurrentUser.requireUserId(), sessionId, request);
    }

    @PostMapping("/sessions/{sessionId}/reveal")
    public PracticeAnswerResponse reveal(@PathVariable long sessionId) {
        return service.reveal(CurrentUser.requireUserId(), sessionId);
    }

    @PostMapping("/sessions/{sessionId}/next")
    public PracticeSessionView next(@PathVariable long sessionId) {
        return service.next(CurrentUser.requireUserId(), sessionId);
    }
}
