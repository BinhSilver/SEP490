package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.jlptpath.dto.JpathSkillOverview;
import com.jlas.jlptpath.service.JpathSkillCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Đọc · Nghe skill tools of the JLPT path. The learner is always the principal. */
@RestController
@RequestMapping("/api/learning/jlpt/skills")
public class JpathSkillController {

    private final JpathSkillCatalogService catalog;

    public JpathSkillController(JpathSkillCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<JpathSkillOverview>> overview(@AuthenticationPrincipal UserPrincipal user,
            @RequestParam(name = "level", required = false) String level) {
        return ResponseEntity.ok(ApiResponse.ok("OK", catalog.overview(user.userId(), level)));
    }
}
