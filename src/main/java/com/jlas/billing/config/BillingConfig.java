package com.jlas.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PayOsProperties.class)
public class BillingConfig {
}
