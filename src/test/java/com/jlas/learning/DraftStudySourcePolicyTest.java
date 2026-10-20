package com.jlas.learning;

import com.jlas.common.learning.DraftStudySourcePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner 2026-10-19 (§2.28) + 2026-10-20 (OD-44/OD-45): the J-LAS N3 textbook is studied while still DRAFT. */
class DraftStudySourcePolicyTest {

    @Test
    void onlyAnN3LearningPathTextbookStillInDraftIsOpenForStudy() {
        assertTrue(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", true, "N3"));
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", false, "N3"), "a book outside the path catalog");
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", null, "N3"));
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", true, "N5"), "N5/N4 keep the published-only rule");
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", true, "N4"));
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", true, "N2"), "N2/N1 open together with their release");
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("DRAFT", true, null));
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("PUBLISHED", true, "N3"), "published is not a draft");
        assertFalse(DraftStudySourcePolicy.isOpenDraftTextbook("ARCHIVED", true, "N3"));
    }

    @Test
    void aStudyableTextbookIsPublishedOrAnOpenDraft() {
        assertTrue(DraftStudySourcePolicy.isStudyableTextbook("PUBLISHED", false, "N5"));
        assertTrue(DraftStudySourcePolicy.isStudyableTextbook("DRAFT", true, "N3"));
        assertFalse(DraftStudySourcePolicy.isStudyableTextbook("DRAFT", true, "N4"));
        assertFalse(DraftStudySourcePolicy.isStudyableTextbook("ARCHIVED", true, "N3"));
    }

    @Test
    void aLessonOfAnOpenDraftBookIsStudyableUnlessArchivedWhileAPublishedBookKeepsPublishedLessonsOnly() {
        assertTrue(DraftStudySourcePolicy.isStudyableLesson("DRAFT", "DRAFT", true, "N3"));
        assertTrue(DraftStudySourcePolicy.isStudyableLesson("PUBLISHED", "DRAFT", true, "N3"));
        assertFalse(DraftStudySourcePolicy.isStudyableLesson("ARCHIVED", "DRAFT", true, "N3"));
        assertTrue(DraftStudySourcePolicy.isStudyableLesson("PUBLISHED", "PUBLISHED", false, "N5"));
        assertFalse(DraftStudySourcePolicy.isStudyableLesson("DRAFT", "PUBLISHED", true, "N3"),
                "a draft lesson inside a published book stays hidden");
        assertFalse(DraftStudySourcePolicy.isStudyableLesson("DRAFT", "DRAFT", true, "N5"));
    }

    @Test
    void openDraftKanjiStayAtOrBelowTheStageLevel() {
        assertTrue(DraftStudySourcePolicy.isKanjiWithinStage("N3", "N3"));
        assertTrue(DraftStudySourcePolicy.isKanjiWithinStage("N5", "N3"));
        assertTrue(DraftStudySourcePolicy.isKanjiWithinStage("N4", "N3"));
        assertFalse(DraftStudySourcePolicy.isKanjiWithinStage("N2", "N3"));
        assertFalse(DraftStudySourcePolicy.isKanjiWithinStage("N1", "N3"));
        assertFalse(DraftStudySourcePolicy.isKanjiWithinStage(null, "N3"), "an untagged kanji is not shown as N3 study");
    }
}
