package com.jlas.shadowing.controller;

import com.jlas.auth.security.CurrentUser;
import com.jlas.shadowing.service.ShadowingQuestionPreparationService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/videos/{videoId}/practice-preparation")
public class ShadowingQuestionPreparationController {
    private final ShadowingQuestionPreparationService service;

    public ShadowingQuestionPreparationController(ShadowingQuestionPreparationService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> status(@PathVariable long videoId) {
        return service.status(CurrentUser.requireUserId(), videoId);
    }

    @GetMapping("/availability")
    public Map<String, Object> availability(@PathVariable long videoId) {
        return service.availability(CurrentUser.requireUserId(), videoId);
    }

    @PostMapping("/suggestions")
    public Map<String, Object> suggestions(@PathVariable long videoId) {
        return service.suggestions(CurrentUser.requireUserId(), videoId);
    }

    @PutMapping("/video-level")
    public Map<String, Object> selectVideoLevel(@PathVariable long videoId,
                                                @RequestBody VideoLevelRequest request) {
        return service.selectVideoLevel(CurrentUser.requireUserId(), videoId, request.level());
    }

    @PostMapping
    public Map<String, Object> prepare(@PathVariable long videoId, @RequestBody PrepareRequest request) {
        return service.queue(CurrentUser.requireUserId(), videoId, request.levels(), request.amounts());
    }

    @DeleteMapping("/questions/{questionId}")
    public void reject(@PathVariable long videoId, @PathVariable long questionId) {
        service.reject(CurrentUser.requireUserId(), videoId, questionId);
    }

    @PostMapping("/approve")
    public Map<String, Object> approve(@PathVariable long videoId) {
        return service.approve(CurrentUser.requireUserId(), videoId);
    }

    @PostMapping("/questions/{questionId}/approve")
    public Map<String, Object> approveQuestion(@PathVariable long videoId,
                                                @PathVariable long questionId) {
        return service.approveSelected(CurrentUser.requireUserId(), videoId, Set.of(questionId));
    }

    @PostMapping("/approve-selected")
    public Map<String, Object> approveSelected(@PathVariable long videoId,
                                                @RequestBody QuestionIdsRequest request) {
        return service.approveSelected(CurrentUser.requireUserId(), videoId, request.ids());
    }

    @PostMapping("/reject-selected")
    public Map<String, Object> rejectSelected(@PathVariable long videoId,
                                               @RequestBody QuestionIdsRequest request) {
        return service.rejectSelected(CurrentUser.requireUserId(), videoId, request.ids());
    }

    public record PrepareRequest(Set<String> levels, Map<String, Integer> amounts) {
    }

    public record VideoLevelRequest(String level) {
    }

    public record QuestionIdsRequest(Set<Long> ids) {
    }
}
