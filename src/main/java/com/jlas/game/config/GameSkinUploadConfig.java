package com.jlas.game.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Path;

@Configuration
public class GameSkinUploadConfig implements WebMvcConfigurer {
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of("uploads", "game-skins").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/game-skins/**").addResourceLocations(location.endsWith("/") ? location : location + "/");
        // Ảnh khung avatar admin tải lên (AvatarFrameAdminController).
        String frames = Path.of("uploads", "avatar-frames").toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/avatar-frames/**").addResourceLocations(frames.endsWith("/") ? frames : frames + "/");
    }
}
