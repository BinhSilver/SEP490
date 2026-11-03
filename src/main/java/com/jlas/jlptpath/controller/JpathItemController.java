package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathCheckIssueRequest;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathItemDetailView;
import com.jlas.jlptpath.dto.JpathKnownResult;
import com.jlas.jlptpath.dto.JpathTodayView;
import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.JpathCheckService;
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
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathItemController {

    private final JpathItemService itemService;
    private final JpathCheckService checkService;

    /** "Đã biết" from the list drawer: KNOWN at once for a learned item, or the SKIP_ONE check token for a NEW one. */
    @PostMapping("/items/{type}/{code}/known")
    public ResponseEntity<ApiResponse<JpathKnownResult>> known(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String type, @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", itemService.markKnown(user.userId(), parse(type), code)));
    }

    /** "Tôi biết chủ đề này": a 10-question TOPIC_KNOWN check of the topic's words (policy #19). */
    @PostMapping("/topics/{level}/{topicKey}/known-check")
    public ResponseEntity<ApiResponse<JpathCheckView>> topicKnownCheck(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String level, @PathVariable String topicKey) {
        JpathCheckIssueRequest request = new JpathCheckIssueRequest(JpathCheckKind.TOPIC_KNOWN, JpathTab.VOCAB, null,
                level.trim().toUpperCase(Locale.ROOT), topicKey, null, null, null, null, null, null, null);
        return ResponseEntity.ok(ApiResponse.ok("OK", checkService.issue(user.userId(), request)));
    }

    /** "Học mẫu này trong lượt hôm nay"; returns today's block. */
    @PostMapping("/grammar/{code}/queue-today")
    public ResponseEntity<ApiResponse<JpathTodayView>> queueGrammarToday(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("Đã thêm mẫu vào lượt hôm nay.",
                itemService.queueGrammarToday(user.userId(), code)));
    }

    @GetMapping("/items/{type}/{code}")
    public ResponseEntity<ApiResponse<JpathItemDetailView>> detail(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String type, @PathVariable String code) {
        return ResponseEntity.ok(ApiResponse.ok("OK", itemService.detail(user.userId(), parse(type), code)));
    }

    @PostMapping("/items/{type}/{code}/notebook")
    public ResponseEntity<ApiResponse<JpathItemStateView>> addToNotebook(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String type, @PathVariable String code) {
        JpathItemStateView view = itemService.addToNotebook(user.userId(), parse(type), code,
                JpathNotebookSource.MANUAL);
        return ResponseEntity.ok(ApiResponse.ok("Đã thêm vào sổ tay.", view));
    }

    @DeleteMapping("/items/{type}/{code}/notebook")
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
