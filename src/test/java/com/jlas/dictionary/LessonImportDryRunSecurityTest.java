package com.jlas.dictionary;

import com.jlas.dictionary.controller.DictionaryController;
import com.jlas.dictionary.dto.LessonImportResult;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.LessonImportService;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [JLAS2-17] Chạy thử import (dryRun) dùng đúng quyền của API import: chỉ CONTENT_CREATOR; chưa đăng nhập → 401
 * (AuthenticationCredentialsNotFoundException), vai khác → 403. Controller thật + @PreAuthorize thật, service mock.
 */
class LessonImportDryRunSecurityTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean LessonImportService lessonImportService() { return Mockito.mock(LessonImportService.class); }
        @Bean DictionaryController dictionaryController(LessonImportService importService) {
            return new DictionaryController(Mockito.mock(DictionaryService.class), Mockito.mock(HandwritingRateLimiter.class),
                    Mockito.mock(DictionaryEngagementService.class), Mockito.mock(ContentAdminService.class), importService);
        }
    }

    private static final MockMultipartFile FILE = new MockMultipartFile("file", "a.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[]{1});

    private AnnotationConfigApplicationContext context;
    private DictionaryController controller;
    private LessonImportService service;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(DictionaryController.class);
        service = context.getBean(LessonImportService.class);
        when(service.importLesson(anyLong(), any(), anyBoolean()))
                .thenReturn(LessonImportResult.builder().dryRun(true).build());
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
    void anonymousIsRejected() {
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> controller.importLesson(1L, FILE, true));
        verify(service, never()).importLesson(anyLong(), any(), anyBoolean());
    }

    @Test
    void otherRolesAreForbidden() {
        for (String role : List.of("LEARNER", "MODERATOR", "ADMIN")) {
            loginAs(role);
            assertThrows(AccessDeniedException.class, () -> controller.importLesson(1L, FILE, true), role);
        }
        verify(service, never()).importLesson(anyLong(), any(), anyBoolean());
    }

    @Test
    void contentCreatorCanDryRun() {
        loginAs("CONTENT_CREATOR");
        assertDoesNotThrow(() -> controller.importLesson(1L, FILE, true));
        verify(service).importLesson(1L, FILE, true);
    }
}
