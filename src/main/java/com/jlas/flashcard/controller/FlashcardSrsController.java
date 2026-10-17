package com.jlas.flashcard.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.flashcard.dto.FlashcardDtos.*;
import com.jlas.flashcard.service.FlashcardSrsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** "Ôn hôm nay": hàng ôn SRS gộp mọi bộ thẻ và thống kê ôn tập (yêu cầu đăng nhập — /api/study/**). */
@RestController @RequestMapping("/api/study") @RequiredArgsConstructor
public class FlashcardSrsController {
    private final FlashcardSrsService srs;
    @GetMapping("/due") public ResponseEntity<ApiResponse<DueQueueDto>> due(@RequestParam(required=false) Integer limit,@AuthenticationPrincipal UserPrincipal user){return ok(srs.due(id(user),limit));}
    @PostMapping("/due/review") public ResponseEntity<ApiResponse<DueReviewResultDto>> review(@RequestBody DueReviewRequest request,@AuthenticationPrincipal UserPrincipal user){return ok(srs.reviewDue(request,id(user)));}
    @GetMapping("/srs-stats") public ResponseEntity<ApiResponse<SrsStatsDto>> stats(@AuthenticationPrincipal UserPrincipal user){return ok(srs.stats(id(user)));}
    private Long id(UserPrincipal user){return user==null?null:user.userId();}
    private <T> ResponseEntity<ApiResponse<T>> ok(T body){return ResponseEntity.ok(ApiResponse.ok("OK",body));}
}
