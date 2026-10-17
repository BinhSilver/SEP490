package com.jlas.dictionary;

import com.jlas.dictionary.controller.DictionaryController;
import com.jlas.dictionary.dto.BulkStatusRequest;
import com.jlas.dictionary.dto.BulkStatusResponse;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.LessonImportService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [JLAS2-17] Endpoint công bố / ẩn hàng loạt: quyền (@PreAuthorize thật trên controller thật, service mock — repo không có
 * test nạp full context), validation của request (rỗng, &gt; 500 id, loại / hành động sai) và SecurityConfig không mở
 * route ghi này cho người chưa đăng nhập (→ 401 qua anyRequest().authenticated()).
 */
class BulkStatusControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean DictionaryService dictionaryService() { return Mockito.mock(DictionaryService.class); }
        @Bean DictionaryController dictionaryController(DictionaryService service) {
            return new DictionaryController(service, Mockito.mock(HandwritingRateLimiter.class),
                    Mockito.mock(DictionaryEngagementService.class),
                    Mockito.mock(ContentAdminService.class), Mockito.mock(LessonImportService.class));
        }
    }

    private AnnotationConfigApplicationContext context;
    private DictionaryController controller;
    private DictionaryService service;
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(DictionaryController.class);
        service = context.getBean(DictionaryService.class);
        when(service.bulkUpdateStatus(anyString(), anyString(), anyList(), any()))
                .thenReturn(BulkStatusResponse.builder().kind("LESSON").status("PUBLISHED").requested(1).updated(1).ids(List.of(1L)).build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private static BulkStatusRequest request(String kind, String action, List<Long> ids) {
        BulkStatusRequest r = new BulkStatusRequest();
        r.setKind(kind);
        r.setAction(action);
        r.setIds(ids);
        return r;
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "u", "p", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    void anonymousIsRejectedAsUnauthenticated() {
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> controller.bulkUpdateStatus(request("LESSON", "PUBLISH", List.of(1L)), null));
        verify(service, never()).bulkUpdateStatus(anyString(), anyString(), anyList(), any());
    }

    @Test
    void otherRolesAreForbidden() {
        // Vai sau JLAS-W0250 (SUPER_ADMIN → ADMIN, CONTENT_ADMIN → CONTENT_CREATOR): chỉ CONTENT_CREATOR được gọi.
        for (String role : List.of("LEARNER", "MODERATOR", "ADMIN")) {
            loginAs(role);
            assertThrows(AccessDeniedException.class,
                    () -> controller.bulkUpdateStatus(request("LESSON", "PUBLISH", List.of(1L)), null), role);
        }
        verify(service, never()).bulkUpdateStatus(anyString(), anyString(), anyList(), any());
    }

    @Test
    void contentCreatorCanCall() {
        loginAs("CONTENT_CREATOR");
        assertDoesNotThrow(() -> controller.bulkUpdateStatus(request("LESSON", "PUBLISH", List.of(1L)), null));
        verify(service).bulkUpdateStatus("LESSON", "PUBLISH", List.of(1L), null);
    }

    @Test
    void requestValidation() {
        assertTrue(validator.validate(request("LESSON", "PUBLISH", List.of(1L, 2L))).isEmpty());
        List<Long> max = new ArrayList<>(LongStream.rangeClosed(1, 500).boxed().toList());
        assertTrue(validator.validate(request("TEXTBOOK", "UNPUBLISH", max)).isEmpty(), "đúng 500 id hợp lệ");
        max.add(501L);
        assertEquals(1, validator.validate(request("TEXTBOOK", "UNPUBLISH", max)).size(), "> 500 id");
        assertFalse(validator.validate(request("LESSON", "PUBLISH", List.of())).isEmpty(), "danh sách rỗng");
        assertFalse(validator.validate(request("LESSON", "PUBLISH", null)).isEmpty(), "thiếu danh sách");
        assertFalse(validator.validate(request("UNIT", "PUBLISH", List.of(1L))).isEmpty(), "loại sai");
        assertFalse(validator.validate(request("LESSON", "ARCHIVE", List.of(1L))).isEmpty(), "hành động sai");
        List<Long> withNull = new ArrayList<>(List.of(1L));
        withNull.add(null);
        assertFalse(validator.validate(request("LESSON", "PUBLISH", withNull)).isEmpty(), "id null");
    }

    @Test
    void bulkRouteIsNotPermitAllInSecurityConfig() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/jlas/config/SecurityConfig.java"), StandardCharsets.UTF_8);
        Matcher block = Pattern.compile(
                "\\.requestMatchers\\(((?:[^()]|\\([^()]*\\))*)\\)\\s*\\.permitAll\\(\\)", Pattern.DOTALL).matcher(source);
        AntPathMatcher ant = new AntPathMatcher();
        int groups = 0;
        while (block.find()) {
            groups++;
            String args = block.group(1);
            if (args.trim().startsWith("HttpMethod.GET")) continue;   // GET /api/dictionary/** chỉ mở cho đọc
            Matcher literal = Pattern.compile("\"([^\"]*)\"").matcher(args);
            while (literal.find()) {
                assertFalse(ant.match(literal.group(1), "/api/dictionary/manage/status/bulk"),
                        "permitAll '" + literal.group(1) + "' không được phủ route hàng loạt");
            }
        }
        assertTrue(groups > 0);
        assertTrue(source.contains(".anyRequest().authenticated()"));
    }
}
