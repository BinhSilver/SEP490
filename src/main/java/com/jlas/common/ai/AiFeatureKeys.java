package com.jlas.common.ai;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads {@code openrouter.feature-keys.<feature>} for {@link AiFeature#apiKey(String)}. Held statically so every AI
 * client (many built by hand in tests) can ask without a new constructor argument; without Spring the shared key is used.
 */
@Component
public class AiFeatureKeys implements EnvironmentAware {
    private static volatile Environment environment;

    @Override
    public void setEnvironment(Environment env) {
        environment = env;
    }

    static String lookup(AiFeature feature) {
        Environment env = environment;
        return env == null ? null : env.getProperty("openrouter.feature-keys." + feature.configKey());
    }
}
