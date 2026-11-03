package com.jlas.jlptpath.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * [JLAS-W0108] Admin pages of the JLPT path, rendered inside the shared admin shell. Access is checked in
 * SecurityConfig (/admin/learning-path/** → CONTENT_CREATOR, ADMIN); the feature flag in JpathFeatureInterceptor.
 */
@Controller
public class JpathAdminPageController {

    @GetMapping({"/admin/learning-path", "/admin/learning-path/"})
    public String home() {
        return "redirect:/admin/learning-path/reports";
    }

    @GetMapping("/admin/learning-path/reports")
    public String reports() {
        return "admin/jpath-reports";
    }

    /** ADMIN only (/admin/system/**); this fixed route wins over AdminPageController's /admin/system/{section}. */
    @GetMapping("/admin/system/learning-path-report")
    public String report() {
        return "admin/jpath-report";
    }
}
