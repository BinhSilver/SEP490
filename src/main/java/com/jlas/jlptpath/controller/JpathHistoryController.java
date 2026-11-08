package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathBadgeView;
import com.jlas.jlptpath.dto.JpathDayDetailView;
import com.jlas.jlptpath.dto.JpathHeatmapView;
import com.jlas.jlptpath.dto.JpathHistoryView;
import com.jlas.jlptpath.dto.JpathStatsView;
import com.jlas.jlptpath.service.JpathHistoryService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] History, stats, heatmap and badges of the JLPT path. Read only; the learner is always the principal.
 * Dates are ISO ({@code yyyy-MM-dd}) learner study days.
 */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathHistoryController {

    private final JpathHistoryService historyService;

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<JpathHistoryView>> history(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(ApiResponse.ok("OK", historyService.history(user.userId(), from, to)));
    }

    @GetMapping("/history/{day}")
    public ResponseEntity<ApiResponse<JpathDayDetailView>> day(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return ResponseEntity.ok(ApiResponse.ok("OK", historyService.day(user.userId(), day)));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<JpathStatsView>> stats(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(defaultValue = "7") int range) {
        return ResponseEntity.ok(ApiResponse.ok("OK", historyService.stats(user.userId(), range)));
    }

    @GetMapping("/stats/heatmap")
    public ResponseEntity<ApiResponse<JpathHeatmapView>> heatmap(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) Integer months) {
        return ResponseEntity.ok(ApiResponse.ok("OK", historyService.heatmap(user.userId(), months)));
    }

    @GetMapping("/badges")
    public ResponseEntity<ApiResponse<List<JpathBadgeView>>> badges(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", historyService.badges(user.userId())));
    }
}
