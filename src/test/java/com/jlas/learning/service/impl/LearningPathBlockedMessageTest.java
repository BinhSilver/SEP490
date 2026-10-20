package com.jlas.learning.service.impl;

import com.jlas.learning.dto.CoverageGap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec §2.1: a refused partial path explains, in Vietnamese, which level lacks which skills. */
class LearningPathBlockedMessageTest {

    @Test
    void missingSkillsAreNamedPerLevelInVietnamese() {
        String message = LearningServiceImpl.blockedPathMessage(List.of(
                new CoverageGap("LEVEL:N3", "MISSING_SKILL_GRAMMAR"), new CoverageGap("LEVEL:N3", "MISSING_SKILL_READING"),
                new CoverageGap("LEVEL:N3", "MISSING_SKILL_LISTENING"), new CoverageGap("LEVEL:N2", "MISSING_SKILL_KANJI")));
        assertEquals("Chưa thể tạo lộ trình đầy đủ: cấp N3 còn thiếu Ngữ pháp, Đọc, Nghe; cấp N2 còn thiếu Kanji. "
                + "J-LAS không tạo lộ trình thiếu kỹ năng — nội dung sẽ được bổ sung trước khi mở các cấp này.", message);
    }

    @Test
    void aSingleLevelIsNamedInTheSingular() {
        String message = LearningServiceImpl.blockedPathMessage(List.of(
                new CoverageGap("LEVEL:N3", "MISSING_SKILL_READING"), new CoverageGap("LEVEL:N3", "MISSING_SKILL_LISTENING")));
        assertEquals("Chưa thể tạo lộ trình đầy đủ: cấp N3 còn thiếu Đọc, Nghe. "
                + "J-LAS không tạo lộ trình thiếu kỹ năng — nội dung sẽ được bổ sung trước khi mở cấp này.", message);
    }

    @Test
    void otherGapsKeepTheGenericMessageWithTheirCodes() {
        String message = LearningServiceImpl.blockedPathMessage(List.of(new CoverageGap("LESSON:10", "NO_LEARNABLE_CONTENT")));
        assertTrue(message.startsWith("Lộ trình V2 chưa được phát hành vì thiếu học liệu"), message);
        assertTrue(message.contains("NO_LEARNABLE_CONTENT"), message);
    }
}
