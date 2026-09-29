package com.jlas.game.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Game module wiring. */
@Configuration
@EnableConfigurationProperties(GameProperties.class)
public class GameConfig {
}
