package com.jlas.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarUploadServiceTest {
    private Path tempDir;

    @BeforeEach
    void createWorkspaceTempDirectory() throws Exception {
        tempDir = Path.of("target", "avatar-test", UUID.randomUUID().toString()).toAbsolutePath();
        Files.createDirectories(tempDir);
    }

    @Test
    @DisplayName("PROF-TC-017: lưu ảnh JPEG hợp lệ bằng tên file an toàn")
    void storesValidJpegWithGeneratedName() throws Exception {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        service.initialize();
        byte[] jpeg = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        String url = service.store(42L, new MockMultipartFile("file", "ảnh nguy hiểm.jpg", "image/jpeg", jpeg));
        assertThat(url).startsWith("/uploads/avatar/u42-").endsWith(".jpg");
        assertThat(Files.exists(tempDir.resolve(url.substring("/uploads/avatar/".length())))).isTrue();
    }

    @Test
    @DisplayName("PROF-TC-017b: lưu ảnh PNG hợp lệ")
    void storesValidPng() throws Exception {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        service.initialize();
        // PNG magic: 0x89 P N G \r \n 0x1a \n + padding
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', (byte) 0x1a, '\n', 0, 0, 0, 0};
        String url = service.store(5L, new MockMultipartFile("file", "avatar.png", "image/png", png));
        assertThat(url).startsWith("/uploads/avatar/u5-").endsWith(".png");
    }

    @Test
    @DisplayName("PROF-TC-017c: lưu ảnh WEBP hợp lệ")
    void storesValidWebp() throws Exception {
        // Fix #7: test WEBP upload
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        service.initialize();
        // WEBP magic: RIFF????WEBP
        byte[] webp = new byte[] {'R','I','F','F', 0,0,0,0, 'W','E','B','P'};
        String url = service.store(6L, new MockMultipartFile("file", "avatar.webp", "image/webp", webp));
        assertThat(url).startsWith("/uploads/avatar/u6-").endsWith(".webp");
    }

    @Test
    @DisplayName("PROF-TC-020: từ chối định dạng không được hỗ trợ")
    void rejectsUnsupportedFormat() {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        MockMultipartFile pdf = new MockMultipartFile("file", "file.pdf", "application/pdf", "%PDF-1.4".getBytes());
        assertThatThrownBy(() -> service.store(1L, pdf)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Only JPG, PNG and WEBP");
    }

    @Test
    @DisplayName("PROF-TC-021: từ chối file giả mạo phần mở rộng JPEG")
    void rejectsSpoofedJpeg() {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        byte[] fake = "MZ executable content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "malware.jpg", "image/jpeg", fake);
        assertThatThrownBy(() -> service.store(1L, file)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid image content");
    }

    @Test
    @DisplayName("PROF-TC-022: từ chối file rỗng")
    void rejectsEmptyFile() {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> service.store(1L, file)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("File is empty");
    }

    @Test
    @DisplayName("PROF-TC-023: từ chối file vượt 2 MB")
    void rejectsFileLargerThanTwoMegabytes() {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        byte[] content = new byte[2 * 1024 * 1024 + 1];
        content[0] = (byte) 0xff; content[1] = (byte) 0xd8; content[2] = (byte) 0xff;
        MockMultipartFile file = new MockMultipartFile("file", "large.jpg", "image/jpeg", content);
        assertThatThrownBy(() -> service.store(1L, file)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("maximum 2 MB");
    }

    @Test
    @DisplayName("PROF-TC-021b: từ chối WEBP giả mạo (magic bytes sai)")
    void rejectsSpoofedWebp() {
        AvatarUploadService service = new AvatarUploadService(tempDir.toString());
        byte[] fake = "RIFF????NOTW".getBytes(); // RIFF đúng nhưng không có WEBP
        MockMultipartFile file = new MockMultipartFile("file", "fake.webp", "image/webp", fake);
        assertThatThrownBy(() -> service.store(1L, file)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid image content");
    }
}
