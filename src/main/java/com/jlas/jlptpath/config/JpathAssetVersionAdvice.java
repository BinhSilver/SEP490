package com.jlas.jlptpath.config;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * [JLAS-W0108] Exposes {@code jpathAssetVersion} (application start time, {@code yyyyMMddHHmmss}) to every JLPT path and communication path
 * view, so the page kit's {@code ?v=} cache-buster changes on each deploy/restart.
 */
@ControllerAdvice(basePackages = {"com.jlas.jlptpath", "com.jlas.cpath"})
public class JpathAssetVersionAdvice {

    private final String assetVersion =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));

    @ModelAttribute("jpathAssetVersion")
    public String jpathAssetVersion() {
        return assetVersion;
    }
}
