package com.jlas.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.time.Duration;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    private final String avatarLocation;
    private final String flashcardLocation;
    private final String contentAudioLocation;
    private final String textbookThumbnailLocation;
    private final String readingImageLocation;

    public WebMvcConfig(@Value("${upload.path.avatar:uploads/avatar}") String avatarDirectory,
                         @Value("${upload.path.flashcard:uploads/flashcard}") String flashcardDirectory,
                         @Value("${content.audio.path:data/content-audio/}") String contentAudioDirectory,
                         @Value("${upload.path.textbook-thumbnail:uploads/textbook-thumbnails}") String textbookThumbnailDirectory,
                         @Value("${upload.path.reading-image:uploads/reading-images}") String readingImageDirectory) {
        this.avatarLocation = Paths.get(avatarDirectory).toAbsolutePath().normalize().toUri().toString();
        this.flashcardLocation = Paths.get(flashcardDirectory).toAbsolutePath().normalize().toUri().toString();
        this.contentAudioLocation = Paths.get(contentAudioDirectory).toAbsolutePath().normalize().toUri().toString();
        // toUri() chỉ thêm "/" cuối khi thư mục đã tồn tại; thư mục này tạo lúc upload ảnh đầu tiên nên tự thêm.
        String thumbnailUri = Paths.get(textbookThumbnailDirectory).toAbsolutePath().normalize().toUri().toString();
        this.textbookThumbnailLocation = thumbnailUri.endsWith("/") ? thumbnailUri : thumbnailUri + "/";
        String readingImageUri = Paths.get(readingImageDirectory).toAbsolutePath().normalize().toUri().toString();
        this.readingImageLocation = readingImageUri.endsWith("/") ? readingImageUri : readingImageUri + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Every upload gets a new u<id>-<uuid> name and files are never overwritten, so browsers may keep them.
        registry.addResourceHandler("/uploads/avatar/**")
                .addResourceLocations(avatarLocation)
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic());
        registry.addResourceHandler("/uploads/flashcard/**")
                .addResourceLocations(flashcardLocation);
        // Audio giáo trình số hoá [JLAS-W0080]: content_audio.audio_url tương đối trong content.audio.path
        registry.addResourceHandler("/uploads/content-audio/**")
                .addResourceLocations(contentAudioLocation);
        // Thumbnail giáo trình: textbook.thumbnail_url = /uploads/textbook-thumbnails/<uuid>.<ext>
        registry.addResourceHandler("/uploads/textbook-thumbnails/**")
                .addResourceLocations(textbookThumbnailLocation);
        // [JLAS2-17] Ảnh đoạn đọc tải lên: reading_image_override.image_url = /uploads/reading-images/<uuid>.<ext>
        registry.addResourceHandler("/uploads/reading-images/**")
                .addResourceLocations(readingImageLocation);
    }
}
