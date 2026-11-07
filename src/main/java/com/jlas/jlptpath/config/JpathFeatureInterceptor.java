package com.jlas.jlptpath.config;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.service.JpathFeature;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * [JLAS-W0108] The only gate of the JLPT path feature flag. When the flag is off, API routes answer
 * {@code JPATH_DISABLED} (404) and pages go back to the old path; {@code GET /api/learning/jlpt/config} still
 * answers so the survey and Smart Test can read the flag. {@code /learning} itself is not guarded: its router
 * picks the old path.
 */
public class JpathFeatureInterceptor implements HandlerInterceptor {

    /** Guarded routes; a {@code /**} pattern also matches its bare root. */
    public static final List<String> PATH_PATTERNS = List.of(
            "/api/learning/jlpt/**",
            "/api/admin/learning-path/**",
            "/api/admin/system/learning-path-report",
            "/learning/jlpt/**",
            "/admin/learning-path/**",
            "/admin/system/learning-path-report");

    private static final String CONFIG_PATH = "/api/learning/jlpt/config";
    private static final String FALLBACK_PAGE = "/survey";

    private final JpathFeature feature;

    public JpathFeatureInterceptor(JpathFeature feature) {
        this.feature = feature;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (feature.enabled()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            if (CONFIG_PATH.equals(path) && "GET".equalsIgnoreCase(request.getMethod())) {
                return true;
            }
            throw new ApiException(ErrorCode.JPATH_DISABLED);
        }
        response.sendRedirect(request.getContextPath() + FALLBACK_PAGE);
        return false;
    }
}
