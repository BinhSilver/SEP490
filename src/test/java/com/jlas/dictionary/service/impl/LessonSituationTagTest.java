package com.jlas.dictionary.service.impl;

import com.jlas.dictionary.dto.LessonUpsertRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [JLAS2-17] Mã tình huống của bài học phải khớp chk_lesson_situation_tag (NULL | TRAVEL | BA | FOUNDATION).
 * "WORK" (mã cũ trước migration 019) vẫn được nhận và đổi thành BA; giá trị lạ bị chặn ở DTO (400), không tới DB (500).
 */
class LessonSituationTagTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private boolean valid(String situationTag) {
        LessonUpsertRequest request = new LessonUpsertRequest();
        request.setKnowledgeTagId(1L);
        request.setTitle("Bài thử");
        request.setLessonType("CURRICULUM");
        request.setSkill("VOCABULARY");
        request.setSituationTag(situationTag);
        return validator.validate(request).stream().noneMatch(v -> v.getPropertyPath().toString().equals("situationTag"));
    }

    @Test
    void acceptsSchemaCodesBlankAndLegacyWork() {
        assertTrue(valid(null));
        assertTrue(valid(""));
        assertTrue(valid("TRAVEL"));
        assertTrue(valid("BA"));
        assertTrue(valid("FOUNDATION"));
        assertTrue(valid("WORK"));
    }

    @Test
    void rejectsUnknownCodes() {
        assertFalse(valid("DAILY_LIFE"));
        assertFalse(valid("work"));
        assertFalse(valid("Công việc"));
    }

    @Test
    void normalizesBeforeSaving() {
        assertEquals("BA", ContentAdminServiceImpl.normalizeSituationTag("WORK"));
        assertEquals("BA", ContentAdminServiceImpl.normalizeSituationTag("BA"));
        assertEquals("TRAVEL", ContentAdminServiceImpl.normalizeSituationTag("TRAVEL"));
        assertEquals("FOUNDATION", ContentAdminServiceImpl.normalizeSituationTag(" FOUNDATION "));
        assertNull(ContentAdminServiceImpl.normalizeSituationTag(""));
        assertNull(ContentAdminServiceImpl.normalizeSituationTag("   "));
        assertNull(ContentAdminServiceImpl.normalizeSituationTag(null));
    }
}
