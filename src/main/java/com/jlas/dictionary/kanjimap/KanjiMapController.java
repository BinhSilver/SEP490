package com.jlas.dictionary.kanjimap;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.service.DictionaryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
public class KanjiMapController {
    private final KanjiMapProgressService progress;
    private final KanjiMapQuota quota;
    private final DictionaryService dictionary;

    @GetMapping({"/dictionary/kanji-map", "/dictionary/kanji-map.html"})
    public String page() { return "dictionary/kanji-map"; }

    public record HeartbeatRequest(boolean active) { }

    @PostMapping("/api/kanji-map/heartbeat")
    @ResponseBody
    public ResponseEntity<ApiResponse<KanjiMapQuota.Remaining>> heartbeat(@AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest request, @RequestBody HeartbeatRequest beat) {
        return ResponseEntity.ok(ApiResponse.ok("OK", quota.heartbeat(user == null ? null : user.userId(),
                request.getRemoteAddr(), beat.active())));
    }

    @GetMapping("/api/kanji-map/kanji/{character}")
    @ResponseBody
    public ResponseEntity<ApiResponse<KanjiResponse>> detail(@AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest request, @PathVariable String character) {
        quota.requireAvailable(user == null ? null : user.userId(), request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("OK", dictionary.getKanjiDetail(character)));
    }

    @GetMapping("/api/kanji-map/progress")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<String>>> progress(@AuthenticationPrincipal UserPrincipal user, HttpServletRequest request) {
        quota.requireAvailable(user == null ? null : user.userId(), request.getRemoteAddr());
        return ResponseEntity.ok(ApiResponse.ok("OK", progress.mastered(user == null ? null : user.userId())));
    }

    public record MasteryRequest(@NotNull Boolean mastered) {}

    @PutMapping("/api/kanji-map/progress/{character}")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> update(@AuthenticationPrincipal UserPrincipal user,
            HttpServletRequest servletRequest, @PathVariable String character, @Valid @RequestBody MasteryRequest request) {
        quota.requireAvailable(user == null ? null : user.userId(), servletRequest.getRemoteAddr());
        progress.update(user == null ? null : user.userId(), character, request.mastered());
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu tiến độ Kanji"));
    }
}
