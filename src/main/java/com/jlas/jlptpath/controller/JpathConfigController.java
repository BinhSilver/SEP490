package com.jlas.jlptpath.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.jlptpath.dto.JpathConfigView;
import com.jlas.jlptpath.service.JpathCatalogService;
import com.jlas.jlptpath.service.JpathFeature;
import com.jlas.jlptpath.service.JpathLevelInfo;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS-W0108] Feature switch and released levels of the JLPT path; answers even when the path is off. */
@RestController
@RequestMapping("/api/learning/jlpt")
@RequiredArgsConstructor
public class JpathConfigController {

    private final JpathCatalogService catalogService;
    private final JpathFeature feature;

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<JpathConfigView>> config() {
        if (!feature.enabled()) {
            return ResponseEntity.ok(ApiResponse.ok("OK", JpathConfigView.disabled()));
        }
        List<JpathLevelInfo> levels = catalogService.levels();
        List<String> released = levels.stream().filter(JpathLevelInfo::released).map(JpathLevelInfo::level).toList();
        List<String> comingSoon = levels.stream().filter(JpathLevelInfo::comingSoon).map(JpathLevelInfo::level)
                .toList();
        String highest = released.isEmpty() ? null : released.get(released.size() - 1);
        return ResponseEntity.ok(ApiResponse.ok("OK", new JpathConfigView(true, released, highest, comingSoon)));
    }
}
