package com.jlas.dictionary.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.service.DictionaryContributionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dictionary")
@RequiredArgsConstructor
public class DictionaryContributionController {
    private final DictionaryContributionService contributions;

    @GetMapping("/vocabulary/{id}/contributions")
    public ApiResponse<PageResponse<ContributionResponse>> vocabularyComments(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", contributions.vocabularyComments(id, page, size, userId(user)));
    }

    @PostMapping("/vocabulary/{id}/contributions")
    public ApiResponse<ContributionResponse> addVocabulary(@PathVariable Long id,
            @Valid @RequestBody ContributionRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Created", contributions.addVocabulary(id, request, userId(user)));
    }

    @GetMapping("/kanji/{character}/contributions")
    public ApiResponse<PageResponse<ContributionResponse>> kanjiComments(
            @PathVariable String character, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "5") int size, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", contributions.kanjiComments(character, page, size, userId(user)));
    }

    @PostMapping("/kanji/{character}/contributions")
    public ApiResponse<ContributionResponse> addKanji(@PathVariable String character,
            @Valid @RequestBody ContributionRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("Created", contributions.addKanji(character, request, userId(user)));
    }

    @PutMapping("/contributions/{id}/vote")
    public ApiResponse<ContributionResponse> vote(@PathVariable Long id,
            @Valid @RequestBody ContributionVoteRequest request, @AuthenticationPrincipal UserPrincipal user) {
        return ApiResponse.ok("OK", contributions.vote(id, request.getVote(), userId(user)));
    }

    @DeleteMapping("/contributions/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal user) {
        contributions.delete(id, userId(user));
        return ApiResponse.ok("Deleted", null);
    }

    @PostMapping("/contributions/{id}/report")
    public ApiResponse<Void> report(@PathVariable Long id,
            @Valid @RequestBody(required = false) ReportRequest request,
            @AuthenticationPrincipal UserPrincipal user) {
        contributions.report(id, request == null ? null : request.getReason(), userId(user));
        return ApiResponse.ok("Đã báo cáo", null);
    }

    @Data
    public static class ReportRequest {
        @Size(max = 500)
        private String reason;
    }

    private Long userId(UserPrincipal user) { return user == null ? null : user.userId(); }
}
