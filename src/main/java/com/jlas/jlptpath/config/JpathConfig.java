package com.jlas.jlptpath.config;

import com.jlas.jlptpath.service.JpathFeature;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** [JLAS-W0108] JLPT path wiring: settings and the feature flag. */
@Configuration
@EnableConfigurationProperties(JpathProperties.class)
public class JpathConfig {

    @Bean
    public JpathFeature jpathFeature(JpathProperties properties) {
        return properties::enabled;
    }
}
