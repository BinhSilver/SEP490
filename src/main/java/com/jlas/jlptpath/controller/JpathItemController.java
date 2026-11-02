package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathItemDetailView;
import com.jlas.jlptpath.dto.JpathItemStateView;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathNotebookSource;
import com.jlas.jlptpath.service.JpathItemService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [JLAS-W0108] One item of the JLPT path from the list drawer: its detail and the manual notebook. The learner is
 * always the principal; {@code type} is VOCAB, KANJI, GRAMMAR or KANA in any case.
 */
@RestController
@RequestMapping("/api/learning/jlpt/items")
@RequiredArgsConstructor
public class JpathItemController {

    private final JpathItemService itemService;

    @GetMapping("/{type}/{code}")
    public ResponseEntity<ApiResponse<JpathItemDetailView>> detail(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String type, @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", itemService.detail(user.userId(), parse(type), code)));
    }

    @PostMapping("/{type}/{code}/notebook")
    public ResponseEntity<ApiResponse<JpathItemStateView>> addToNotebook(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String type, @PathVariable String code) {
        JpathItemStateView view = itemService.addToNotebook(user.userId(), parse(type), code,
                JpathNotebookSource.MANUAL);
        return ResponseEntity.ok(ApiResponse.ok("Đã thêm vào sổ tay.", view));
    }

    @DeleteMapping("/{type}/{code}/notebook")
    public ResponseEntity<ApiResponse<JpathItemStateView>> removeFromNotebook(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable String type, @PathVariable String code) {
        JpathItemStateView view = itemService.removeFromNotebook(user.userId(), parse(type), code);
        return ResponseEntity.ok(ApiResponse.ok("Đã bỏ khỏi sổ tay.", view));
    }

    private static JpathItemType parse(String type) {
        try {
            return JpathItemType.valueOf(type.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
        }
    }
}
