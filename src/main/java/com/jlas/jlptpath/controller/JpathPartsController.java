package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.enums.JpathTab;
import com.jlas.jlptpath.service.impl.JpathPartsService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Owner 05/11: the study parts of one tab (VOCAB | KANJI | GRAMMAR) of the learner's open level. */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathPartsController {

    private final JpathPartsService partsService;

    /** Owner 06/11: whether to offer the short warm-up on the level below the newly opened one. */
    @GetMapping("/warmup")
    public ResponseEntity<ApiResponse<JpathPartsService.Warmup>> warmup(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", partsService.warmup(user.userId())));
    }

    /** Owner 06/11: the Smart Test result the active path was made from ("Đầu vào" on the Stats page). */
    @GetMapping("/entry-result")
    public ResponseEntity<ApiResponse<JpathPartsService.EntryResult>> entryResult(@AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.ok("OK", partsService.entryResult(user.userId())));
    }

    private final com.jlas.jlptpath.repository.JpathSkillSetRepository skillSets;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Owner 06/11: translations of one Đọc · Nghe set (question + options, listening lines or reading sentences). */
    @GetMapping("/skills/sets/{code}/translation")
    @SuppressWarnings("unchecked")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> setTranslation(
            @AuthenticationPrincipal UserPrincipal user, @org.springframework.web.bind.annotation.PathVariable String code) {
        // a plain Map: the MVC JSON writer serialises it as the stored object
        java.util.Map<String, Object> node = skillSets.findById(code)
                .map(com.jlas.jlptpath.entity.JpathSkillSet::getTranslationJson)
                .map(t -> { try { return (java.util.Map<String, Object>) JSON.readValue(t, java.util.Map.class); } catch (Exception e) { return null; } })
                .orElse(null);
        return ResponseEntity.ok(ApiResponse.ok("OK", node));
    }

    @GetMapping("/parts")
    public ResponseEntity<ApiResponse<JpathPartsService.Parts>> parts(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam String tab) {
        JpathTab t;
        try {
            t = JpathTab.valueOf(tab.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            t = null;
        }
        return ResponseEntity.ok(ApiResponse.ok("OK", partsService.parts(user.userId(), t)));
    }
}
