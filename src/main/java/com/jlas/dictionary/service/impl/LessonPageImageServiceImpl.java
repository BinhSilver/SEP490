package com.jlas.dictionary.service.impl;

import com.jlas.auth.security.CurrentUser;
import com.jlas.dictionary.dto.LessonPageImageResponse;
import com.jlas.dictionary.dto.LessonPageImagesResponse;
import com.jlas.dictionary.service.LessonPageImageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * [JLAS2-17] Ảnh trang. Dùng JdbcTemplate (không phải @Entity) có chủ đích, như ReadingImageServiceImpl: với
 * ddl-auto=validate, một entity mới sẽ làm app không khởi động được trên DB chưa chạy migration 037. Ở đây bảng thiếu
 * thì chức năng tự tắt (available = false).
 */
@Slf4j
@Service
public class LessonPageImageServiceImpl implements LessonPageImageService {

    private static final String ROLE_CONTENT_CREATOR = "ROLE_CONTENT_CREATOR";
    private static final long RECHECK_MS = 60_000;

    private final JdbcTemplate jdbcTemplate;
    private volatile Boolean available;
    private volatile long checkedAt;

    public LessonPageImageServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean isAvailable() {
        Boolean known = available;
        if (Boolean.TRUE.equals(known)) return true;
        long now = System.currentTimeMillis();
        if (known != null && now - checkedAt < RECHECK_MS) return false;
        boolean found;
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                            + "AND TABLE_NAME = 'lesson_page_image'", Integer.class);
            found = count != null && count > 0;
        } catch (DataAccessException exception) {
            found = false;
        }
        if (!found && known == null) log.info("[page-image] Chưa có bảng lesson_page_image (migration 037) — tắt ảnh trang.");
        available = found;
        checkedAt = now;
        return found;
    }

    @Override
    public LessonPageImagesResponse listForLesson(Long lessonId) {
        if (!isAvailable()) return LessonPageImagesResponse.builder().available(false).items(List.of()).build();
        String sql = "SELECT p.tab, p.image_url, p.order_index FROM lesson_page_image p "
                + "JOIN lesson l ON l.id = p.lesson_id JOIN unit u ON u.id = l.unit_id JOIN textbook t ON t.id = u.textbook_id "
                + "WHERE p.lesson_id = ?"
                + (CurrentUser.hasRole(ROLE_CONTENT_CREATOR) ? "" : " AND l.status = 'PUBLISHED' AND t.status = 'PUBLISHED'")
                + " ORDER BY p.tab, p.order_index, p.id";
        List<LessonPageImageResponse> items = jdbcTemplate.query(sql,
                (rs, i) -> new LessonPageImageResponse(rs.getString(1), rs.getString(2), rs.getInt(3)), lessonId);
        return LessonPageImagesResponse.builder().available(true).items(items).build();
    }
}
