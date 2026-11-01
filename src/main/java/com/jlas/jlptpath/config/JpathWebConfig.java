package com.jlas.jlptpath.config;

import com.jlas.jlptpath.service.JpathFeature;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** [JLAS-W0108] Registers the JLPT path feature guard on the path's pages and APIs. */
@Configuration
public class JpathWebConfig implements WebMvcConfigurer {

    private final JpathFeature feature;

    public JpathWebConfig(JpathFeature feature) {
        this.feature = feature;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JpathFeatureInterceptor(feature))
                .addPathPatterns(JpathFeatureInterceptor.PATH_PATTERNS.toArray(String[]::new));
    }
}
