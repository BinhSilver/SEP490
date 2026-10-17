package com.jlas.dictionary;

import com.jlas.dictionary.controller.ReadingImageController;
import com.jlas.dictionary.dto.LessonReadingImagesResponse;
import com.jlas.dictionary.service.ReadingImageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [JLAS2-17] Phân quyền API ảnh đoạn đọc. Repo không có test nạp full context (JlasApplication bật
 * {@code @EnableJpaRepositories}), nên kiểm hai lớp riêng:
 * <ul>
 *   <li>@PreAuthorize thật (context nhỏ bật method security, controller thật, service mock): chưa đăng nhập →
 *       AuthenticationCredentialsNotFoundException (filter chain trả 401), sai vai → AccessDeniedException (403),
 *       chỉ CONTENT_CREATOR qua được — đúng quyền {@code hasRole('CONTENT_CREATOR')} của mọi API quản trị nội dung
 *       JLAS2-17 (DictionaryController /manage/**, JlptAdminController), CONTENT_ADMIN cũng bị 403;</li>
 *   <li>SecurityConfig: route ghi không nằm trong matcher permitAll nào (trừ matcher chỉ cho GET), nên rơi vào
 *       anyRequest().authenticated() → 401 khi chưa đăng nhập.</li>
 * </ul>
 */
class ReadingImageControllerSecurityTest {

    private static final String CODE = "LUYEN-DOC-N5-L001-RDG-001";
    private static final MockMultipartFile FILE = new MockMultipartFile("file", "a.png", "image/png",
            new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D});

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean ReadingImageService readingImageService() { return Mockito.mock(ReadingImageService.class); }
        @Bean ReadingImageController readingImageController(ReadingImageService service) { return new ReadingImageController(service); }
    }

    private AnnotationConfigApplicationContext context;
    private ReadingImageController controller;
    private ReadingImageService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(ReadingImageController.class);
        service = context.getBean(ReadingImageService.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "u", "p", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void anonymousWriteIsRejectedAsUnauthenticated() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.upload(CODE, FILE, null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.hide(CODE, null));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.remove(CODE, null));
        verify(service, never()).upload(any(), any(), any());
    }

    @Test
    void otherRolesWriteIsForbidden() {
        for (String role : List.of("LEARNER", "MODERATOR", "ADMIN", "CONTENT_ADMIN", "SUPER_ADMIN")) {
            loginAs(role);
            assertThrows(AccessDeniedException.class, () -> controller.upload(CODE, FILE, null), role);
            assertThrows(AccessDeniedException.class, () -> controller.hide(CODE, null), role);
            assertThrows(AccessDeniedException.class, () -> controller.remove(CODE, null), role);
        }
        verify(service, never()).upload(any(), any(), any());
        verify(service, never()).remove(any(), any());
        verify(service, never()).hideSeedImage(any(), any());
    }

    @Test
    void onlyContentCreatorCanWrite() {
        loginAs("CONTENT_CREATOR");
        assertDoesNotThrow(() -> controller.upload(CODE, FILE, null));
        assertDoesNotThrow(() -> controller.hide(CODE, null));
        assertDoesNotThrow(() -> controller.remove(CODE, null));
        verify(service, Mockito.times(1)).upload(any(), any(), any());
    }

    @Test
    void anonymousCanReadLessonImages() {
        when(service.listForLesson(anyLong())).thenReturn(LessonReadingImagesResponse.builder().available(true).items(List.of()).build());
        assertDoesNotThrow(() -> controller.list(1000127L));
    }

    @Test
    void writeRoutesAreNotPermitAllInSecurityConfig() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jlas/config/SecurityConfig.java"), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile(
                "\\.requestMatchers\\(((?:[^()]|\\([^()]*\\))*)\\)\\s*\\.permitAll\\(\\)", Pattern.DOTALL).matcher(source);
        AntPathMatcher ant = new AntPathMatcher();
        int groups = 0;
        while (block.find()) {
            groups++;
            String args = block.group(1);
            if (args.trim().startsWith("HttpMethod.GET")) continue; // GET /api/dictionary/** chỉ mở cho đọc
            Matcher literal = Pattern.compile("\"([^\"]*)\"").matcher(args);
            while (literal.find()) {
                for (String route : List.of("/api/dictionary/reading-images/" + CODE, "/api/dictionary/reading-images/" + CODE + "/hidden")) {
                    assertFalse(ant.match(literal.group(1), route), "permitAll '" + literal.group(1) + "' không được phủ " + route);
                }
            }
        }
        assertTrue(groups > 0, "phải đọc được ít nhất một nhóm permitAll");
        assertTrue(source.contains(".anyRequest().authenticated()"), "chuỗi phải kết thúc bằng anyRequest().authenticated()");
    }
}
