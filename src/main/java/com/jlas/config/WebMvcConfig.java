package com.jlas.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final String avatarLocation;
    private final String flashcardLocation;

    public WebMvcConfig(@Value("${upload.path.avatar:uploads/avatar}") String avatarDirectory,
                         @Value("${upload.path.flashcard:uploads/flashcard}") String flashcardDirectory) {
        this.avatarLocation = Paths.get(avatarDirectory).toAbsolutePath().normalize().toUri().toString();
        this.flashcardLocation = Paths.get(flashcardDirectory).toAbsolutePath().normalize().toUri().toString();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/avatar/**")
                .addResourceLocations(avatarLocation);
        registry.addResourceHandler("/uploads/flashcard/**")
                .addResourceLocations(flashcardLocation);
    }
}
