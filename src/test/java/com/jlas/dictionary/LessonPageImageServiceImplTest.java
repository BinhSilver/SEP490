package com.jlas.dictionary;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.dictionary.dto.LessonPageImageResponse;
import com.jlas.dictionary.dto.LessonPageImagesResponse;
import com.jlas.dictionary.service.impl.LessonPageImageServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [JLAS2-17] Ảnh trang (lesson_page_image): tự tắt khi DB chưa chạy migration 037; cùng quyền xem với nội dung bài —
 * CONTENT_CREATOR thấy mọi bài, vai khác / chưa đăng nhập chỉ thấy bài PUBLISHED thuộc giáo trình PUBLISHED.
 */
class LessonPageImageServiceImplTest {

    private JdbcTemplate jdbc;
    private LessonPageImageServiceImpl service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new LessonPageImageServiceImpl(jdbc);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        List<SimpleGrantedAuthority> roles = List.of(new SimpleGrantedAuthority(role));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserPrincipal(7L, "u", "", roles), null, roles));
    }

    private void tableExists(boolean exists) {
        when(jdbc.queryForObject(startsWith("SELECT COUNT(*) FROM information_schema"), eq(Integer.class))).thenReturn(exists ? 1 : 0);
    }

    @SuppressWarnings("unchecked")
    private String queriedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(5L));
        return sql.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingTableTurnsFeatureOff() {
        tableExists(false);
        LessonPageImagesResponse res = service.listForLesson(5L);
        assertFalse(res.isAvailable());
        assertTrue(res.getItems().isEmpty());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void learnerOnlySeesPublishedLessonsOfPublishedTextbooks() {
        tableExists(true);
        loginAs("ROLE_LEARNER");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(5L)))
                .thenReturn(List.of(new LessonPageImageResponse("KANJI", "vnjpclub/N3/somatome-n3-han-tu/a.png", 1)));
        LessonPageImagesResponse res = service.listForLesson(5L);
        assertTrue(res.isAvailable());
        assertEquals(1, res.getItems().size());
        String sql = queriedSql();
        assertTrue(sql.contains("l.status = 'PUBLISHED'") && sql.contains("t.status = 'PUBLISHED'"), sql);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anonymousIsFilteredLikeLearner() {
        tableExists(true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(List.of());
        service.listForLesson(5L);
        assertTrue(queriedSql().contains("l.status = 'PUBLISHED'"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contentCreatorSeesDraftLessons() {
        tableExists(true);
        loginAs("ROLE_CONTENT_CREATOR");
        when(jdbc.query(anyString(), any(RowMapper.class), eq(5L))).thenReturn(List.of());
        service.listForLesson(5L);
        String sql = queriedSql();
        assertFalse(sql.contains("status = 'PUBLISHED'"), sql);
        assertTrue(sql.contains("ORDER BY p.tab, p.order_index"), sql);
    }
}
