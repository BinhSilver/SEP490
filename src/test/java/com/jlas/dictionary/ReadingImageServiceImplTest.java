package com.jlas.dictionary;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.LessonReadingImagesResponse;
import com.jlas.dictionary.service.impl.ReadingImageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockMultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [JLAS2-17] Ảnh đoạn đọc: kiểm file (magic bytes, dung lượng), mã bài đọc, và không bao giờ xoá ảnh seed. */
class ReadingImageServiceImplTest {

    private static final String CODE = "LUYEN-DOC-N5-L001-RDG-001";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    @TempDir Path temp;
    private Path uploadDir;
    private Path seedDir;
    private JdbcTemplate jdbc;
    private ReadingImageServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        uploadDir = temp.resolve("uploads/reading-images");
        seedDir = Files.createDirectories(temp.resolve("data/content-audio/vnjpclub/N3"));
        jdbc = mock(JdbcTemplate.class);
        service = new ReadingImageServiceImpl(jdbc, uploadDir.toString());
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM information_schema"), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM content_source"), eq(Integer.class), eq(CODE))).thenReturn(1);
    }

    @SuppressWarnings("unchecked")
    private void currentUrl(String url) {
        when(jdbc.query(startsWith("SELECT image_url FROM reading_image_override"), any(RowMapper.class), eq(CODE)))
                .thenReturn(url == null ? List.of() : List.of(url));
    }

    @Test
    void uploadValidPngStoresUuidFileAndSavesOverride() throws IOException {
        currentUrl(null);
        service.upload(CODE, new MockMultipartFile("file", "anh.png", "image/png", PNG), 7L);

        List<Path> files;
        try (Stream<Path> s = Files.list(uploadDir)) { files = s.toList(); }
        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString().matches("[0-9a-f-]{36}\\.png"), "tên file phải là UUID");
        verify(jdbc).update(startsWith("UPDATE reading_image_override"), eq(ReadingImageServiceImpl.URL_PREFIX + files.get(0).getFileName()), eq(7L), eq(CODE));
    }

    @Test
    void renamedNonImageIsRejectedByMagicBytes() {
        byte[] text = "<?php echo 'not an image'; ?>".getBytes(StandardCharsets.UTF_8);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.upload(CODE, new MockMultipartFile("file", "fake.png", "image/png", text), 7L));
        assertEquals(ErrorCode.INVALID_IMAGE, ex.getErrorCode());
        assertFalse(Files.exists(uploadDir), "file bị từ chối không được ghi ra đĩa");
    }

    @Test
    void jpegContentWithPngExtensionIsRejected() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0, 1};
        ApiException ex = assertThrows(ApiException.class,
                () -> service.upload(CODE, new MockMultipartFile("file", "x.png", "image/png", jpeg), 7L));
        assertEquals(ErrorCode.INVALID_IMAGE, ex.getErrorCode());
    }

    @Test
    void fileOverFiveMegabytesIsRejected() {
        byte[] big = new byte[(int) ReadingImageServiceImpl.MAX_SIZE + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.upload(CODE, new MockMultipartFile("file", "big.png", "image/png", big), 7L));
        assertEquals(ErrorCode.PAYLOAD_TOO_LARGE, ex.getErrorCode());
    }

    @Test
    void pathTraversalCodeIsRejectedBeforeTouchingDatabase() {
        for (String bad : List.of("../../etc/passwd", "..", "a/../b", "LUYEN..DOC", "a\\b", "")) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> service.upload(bad, new MockMultipartFile("file", "a.png", "image/png", PNG), 7L), bad);
            assertEquals(ErrorCode.BAD_REQUEST, ex.getErrorCode(), bad);
        }
        verify(jdbc, never()).queryForObject(startsWith("SELECT COUNT(*) FROM content_source"), eq(Integer.class), anyString());
    }

    @Test
    void unknownReadingCodeIsNotFound() {
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM content_source"), eq(Integer.class), eq("KHONG-CO"))).thenReturn(0);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.upload("KHONG-CO", new MockMultipartFile("file", "a.png", "image/png", PNG), 7L));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void replaceDeletesPreviousUploadButNeverSeedFiles() throws IOException {
        Files.createDirectories(uploadDir);
        Path oldUpload = Files.write(uploadDir.resolve("11111111-1111-1111-1111-111111111111.png"), PNG);
        Path seed = Files.write(seedDir.resolve("N3_Dokkai_Tuan1Ngay1_2.png"), PNG);

        currentUrl(ReadingImageServiceImpl.URL_PREFIX + oldUpload.getFileName());
        service.upload(CODE, new MockMultipartFile("file", "moi.webp", "image/webp",
                new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '}), 7L);
        assertFalse(Files.exists(oldUpload), "ảnh tải lên trước đó phải bị xoá khi thay");

        // Mọi dạng URL trỏ ra ngoài thư mục upload (ảnh seed tương đối, /uploads/content-audio, ../) đều bị bỏ qua.
        for (String url : List.of("vnjpclub/N3/N3_Dokkai_Tuan1Ngay1_2.png", "/uploads/content-audio/vnjpclub/N3/N3_Dokkai_Tuan1Ngay1_2.png",
                ReadingImageServiceImpl.URL_PREFIX + "../../data/content-audio/vnjpclub/N3/N3_Dokkai_Tuan1Ngay1_2.png",
                ReadingImageServiceImpl.URL_PREFIX + "..\\..\\data\\content-audio\\vnjpclub\\N3\\N3_Dokkai_Tuan1Ngay1_2.png")) {
            currentUrl(url);
            service.remove(CODE, 7L);
            assertTrue(Files.exists(seed), "ảnh seed không được bị xoá: " + url);
        }
    }

    @Test
    void featureIsOffWhenTableMissing() {
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM information_schema"), eq(Integer.class))).thenReturn(0);
        LessonReadingImagesResponse list = service.listForLesson(1000127L);
        assertFalse(list.isAvailable());
        assertTrue(list.getItems().isEmpty());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.upload(CODE, new MockMultipartFile("file", "a.png", "image/png", PNG), 7L));
        assertEquals(ErrorCode.READING_IMAGE_UNAVAILABLE, ex.getErrorCode());
    }

    /** Seed chạy lại không được chạm bảng override: không DELETE/DROP/TRUNCATE/INSERT nào nhắc tới nó. */
    @Test
    void seedFilesNeverTouchOverrideTable() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        for (String name : List.of("sql/seeds/seed_data_vnjpclub.sql", "sql/seeds/seed_data_minna.sql", "sql/seeds/seed_data_jlas.sql")) {
            Path seed = root.resolve(name);
            if (!Files.exists(seed)) continue;
            try (BufferedReader reader = Files.newBufferedReader(seed, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    assertFalse(lower.contains("reading_image_override") || lower.startsWith("drop database"),
                            name + " không được chạm bảng reading_image_override: " + line);
                }
            }
        }
    }
}
