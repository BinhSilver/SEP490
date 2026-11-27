package com.jlas.dictionary.kanjimap;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dictionary/notebooks/{notebookId}/kanji")
@RequiredArgsConstructor
public class KanjiMapNotebookController {
    private final KanjiMapNotebookService service;
    private final KanjiMapQuota quota;
    public record SaveRequest(@NotEmpty @Size(max = 100) List<String> characters) {}

    @GetMapping
    public ResponseEntity<ApiResponse<List<KanjiMapNotebookService.Entry>>> entries(@AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest servletRequest, @PathVariable Long notebookId) {
        quota.requireAvailable(user == null ? null : user.userId(), servletRequest.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("OK", service.entries(user == null ? null : user.userId(), notebookId)));
    }
    @PostMapping
    public ResponseEntity<ApiResponse<Void>> save(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest servletRequest,
            @PathVariable Long notebookId, @Valid @RequestBody SaveRequest request) {
        quota.requireAvailable(user == null ? null : user.userId(), servletRequest.getRemoteAddr());
        service.save(user == null ? null : user.userId(), notebookId, request.characters());
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu Kanji vào sổ tay"));
    }
    @DeleteMapping("/{character}")
    public ResponseEntity<ApiResponse<Void>> remove(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest servletRequest,
            @PathVariable Long notebookId, @PathVariable String character) {
        quota.requireAvailable(user == null ? null : user.userId(), servletRequest.getRemoteAddr());
        service.remove(user == null ? null : user.userId(), notebookId, character);
        return ResponseEntity.ok(ApiResponse.ok("Đã bỏ Kanji khỏi sổ tay"));
    }
}
