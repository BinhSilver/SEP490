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
    private final String contentAudioLocation;
    private final String textbookThumbnailLocation;

    public WebMvcConfig(@Value("${upload.path.avatar:uploads/avatar}") String avatarDirectory,
                         @Value("${upload.path.flashcard:uploads/flashcard}") String flashcardDirectory,
                         @Value("${content.audio.path:uploads/content-audio/}") String contentAudioDirectory,
                         @Value("${upload.path.textbook-thumbnail:uploads/textbook-thumbnails}") String textbookThumbnailDirectory) {
        this.avatarLocation = Paths.get(avatarDirectory).toAbsolutePath().normalize().toUri().toString();
        this.flashcardLocation = Paths.get(flashcardDirectory).toAbsolutePath().normalize().toUri().toString();
        this.contentAudioLocation = Paths.get(contentAudioDirectory).toAbsolutePath().normalize().toUri().toString();
        // toUri() chỉ thêm "/" cuối khi thư mục đã tồn tại; thư mục này tạo lúc upload ảnh đầu tiên nên tự thêm.
        String thumbnailUri = Paths.get(textbookThumbnailDirectory).toAbsolutePath().normalize().toUri().toString();
        this.textbookThumbnailLocation = thumbnailUri.endsWith("/") ? thumbnailUri : thumbnailUri + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/avatar/**")
                .addResourceLocations(avatarLocation);
        registry.addResourceHandler("/uploads/flashcard/**")
                .addResourceLocations(flashcardLocation);
        // Audio giáo trình số hoá [JLAS-W0080]: content_audio.audio_url tương đối trong content.audio.path
        registry.addResourceHandler("/uploads/content-audio/**")
                .addResourceLocations(contentAudioLocation);
        // Thumbnail giáo trình: textbook.thumbnail_url = /uploads/textbook-thumbnails/<uuid>.<ext>
        registry.addResourceHandler("/uploads/textbook-thumbnails/**")
                .addResourceLocations(textbookThumbnailLocation);
    }
}
