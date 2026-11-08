package com.jlas.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Caches content-hashed static URLs (/css/base-&lt;md5&gt;.css, produced by the resource chain in application.yaml)
 * for a year. The hash changes whenever the file changes, so the browser never needs to ask again.
 * Unhashed URLs are left to the global "no-cache" (revalidate) policy.
 */
@Component
public class StaticAssetCacheFilter extends OncePerRequestFilter {

    private static final Pattern HASHED_ASSET = Pattern.compile("^/(css|js)/.+-[0-9a-f]{32}\\.[A-Za-z0-9]+$");
    private static final String CACHE_CONTROL = "Cache-Control";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !HASHED_ASSET.matcher(path).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(CACHE_CONTROL, "public, max-age=31536000, immutable");
        // The resource handler would overwrite the header with the global "no-cache"; keep ours.
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                if (!CACHE_CONTROL.equalsIgnoreCase(name)) super.setHeader(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                if (!CACHE_CONTROL.equalsIgnoreCase(name)) super.addHeader(name, value);
            }
        });
    }
}
