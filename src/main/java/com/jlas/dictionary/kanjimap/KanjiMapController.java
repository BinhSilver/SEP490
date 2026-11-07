package com.jlas.dictionary.kanjimap;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
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

    @GetMapping({"/dictionary/kanji-map", "/dictionary/kanji-map.html"})
    public String page() { return "dictionary/kanji-map"; }

    @GetMapping("/api/kanji-map/progress")
    @ResponseBody
    public ResponseEntity<ApiResponse<List<String>>> progress(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", progress.mastered(user == null ? null : user.userId())));
    }

    public record MasteryRequest(@NotNull Boolean mastered) {}

    @PutMapping("/api/kanji-map/progress/{character}")
    @ResponseBody
    public ResponseEntity<ApiResponse<Void>> update(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String character, @Valid @RequestBody MasteryRequest request) {
        progress.update(user == null ? null : user.userId(), character, request.mastered());
        return ResponseEntity.ok(ApiResponse.ok("Đã lưu tiến độ Kanji"));
    }
}
