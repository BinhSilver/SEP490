package com.jlas.kaiwa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AiCallQuotaProperties.class, AiCallTurnProperties.class, AiCallTurnLimits.class})
public class KaiwaConfig {
}
