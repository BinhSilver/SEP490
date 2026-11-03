package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathReadiness;
import com.jlas.jlptpath.dto.JpathSectionStartRequest;
import com.jlas.jlptpath.dto.JpathSkillLessonCheckRequest;
import com.jlas.jlptpath.dto.JpathSkillLessonCheckResult;
import com.jlas.jlptpath.dto.JpathSkillLessonView;
import com.jlas.jlptpath.dto.JpathSkillOverview;
import com.jlas.jlptpath.dto.JpathSkillSessionStartRequest;
import com.jlas.jlptpath.dto.JpathSkillSessionView;
import com.jlas.jlptpath.service.JpathSectionPracticeService;
import com.jlas.jlptpath.service.JpathSkillCatalogService;
import com.jlas.jlptpath.service.JpathSkillLessonService;
import com.jlas.jlptpath.service.JpathSkillSessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Đọc · Nghe skill tools of the JLPT path. The learner is always the principal. */
@RestController
@RequestMapping("/api/learning/jlpt/skills")
public class JpathSkillController {

    private final JpathSkillCatalogService catalog;
    private final JpathSkillLessonService lessons;
    private final JpathSkillSessionService sessions;
    private final JpathSectionPracticeService sections;

    public JpathSkillController(JpathSkillCatalogService catalog, JpathSkillLessonService lessons,
            JpathSkillSessionService sessions, JpathSectionPracticeService sections) {
        this.catalog = catalog;
        this.lessons = lessons;
        this.sessions = sessions;
        this.sections = sections;
    }

    /** Timed section practice (#40): the SKILL_SECTION check to take (answer and submit through /checks/{token}). */
    @PostMapping("/sections")
    public ResponseEntity<ApiResponse<JpathCheckView>> startSection(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathSectionStartRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", sections.start(user.userId(), request.skill())));
    }

    /** Exam readiness (#41) of {@code level} (default: the open level). */
    @GetMapping("/readiness")
    public ResponseEntity<ApiResponse<JpathReadiness>> readiness(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", sections.readiness(user.userId(), level)));
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<JpathSkillOverview>> overview(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(name = "level", required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", catalog.overview(user.userId(), level)));
    }

    @GetMapping("/lessons/{typeCode}")
    public ResponseEntity<ApiResponse<JpathSkillLessonView>> lesson(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable String typeCode, @RequestParam(name = "sessionId", required = false) Long sessionId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", lessons.view(user.userId(), typeCode, sessionId)));
    }

    @PostMapping("/lessons/{typeCode}/check")
    public ResponseEntity<ApiResponse<JpathSkillLessonCheckResult>> checkLesson(
            @AuthenticationPrincipal UserPrincipal user, @PathVariable String typeCode,
            @Valid @RequestBody JpathSkillLessonCheckRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
                lessons.check(user.userId(), typeCode, request.option(), request.sessionId())));
    }

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<JpathSkillSessionView>> startSession(@AuthenticationPrincipal UserPrincipal user,
            @Valid @RequestBody JpathSkillSessionStartRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("OK", sessions.start(user.userId(), request)));
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<JpathSkillSessionView>> session(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", sessions.get(user.userId(), id)));
    }

    @PostMapping("/sessions/{id}/advance")
    public ResponseEntity<ApiResponse<JpathSkillSessionView>> advance(@AuthenticationPrincipal UserPrincipal user,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("OK", sessions.advance(user.userId(), id)));
    }
}
