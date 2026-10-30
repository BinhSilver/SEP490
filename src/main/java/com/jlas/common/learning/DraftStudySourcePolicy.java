package com.jlas.common.learning;

import java.util.List;
import java.util.Set;

/**
 * Giáo trình JLPT còn DRAFT nhưng owner đã cho học (spec §2.28, owner 2026-10-19; OD-44/OD-45 ngày 2026-10-20).
 *
 * <p>Sách đánh cờ nguồn lộ trình ở cấp mở được dùng cho lộ trình và người học mở được trang sách, nhưng
 * trạng thái DRAFT giữ nguyên và giao diện luôn ghi "Chưa duyệt" — không đổi hàng loạt sang PUBLISHED.
 * Hiện chỉ N3; N2/N1 mở cùng đợt phát hành cấp đó (xem {@link JlptPathPolicy}). N5/N4 giữ quy tắc chỉ
 * dùng sách đã xuất bản.
 */
public final class DraftStudySourcePolicy {

    public static final Set<String> OPEN_DRAFT_LEVELS = Set.of("N3");

    private static final List<String> LEVEL_ORDER = List.of("N5", "N4", "N3", "N2", "N1");

    private DraftStudySourcePolicy() {
    }

    public static boolean isOpenDraftTextbook(String status, Boolean learningPathSource, String jlptLevel) {
        return "DRAFT".equals(status) && Boolean.TRUE.equals(learningPathSource)
                && jlptLevel != null && OPEN_DRAFT_LEVELS.contains(jlptLevel);
    }

    public static boolean isStudyableTextbook(String status, Boolean learningPathSource, String jlptLevel) {
        return "PUBLISHED".equals(status) || isOpenDraftTextbook(status, learningPathSource, jlptLevel);
    }

    /** A published book shows only published lessons; an open draft book shows every lesson except archived ones. */
    public static boolean isStudyableLesson(String lessonStatus, String textbookStatus, Boolean learningPathSource,
            String jlptLevel) {
        if ("PUBLISHED".equals(textbookStatus)) return "PUBLISHED".equals(lessonStatus);
        return isOpenDraftTextbook(textbookStatus, learningPathSource, jlptLevel)
                && ("PUBLISHED".equals(lessonStatus) || "DRAFT".equals(lessonStatus));
    }

    /**
     * Kanji of an open draft lesson come from its vocabulary with no level of their own, so N2/N1 characters would
     * enter an N3 stage (§2.2 "đúng level"). Keep only kanji tagged at or below the stage level.
     */
    public static boolean isKanjiWithinStage(String kanjiLevel, String stageLevel) {
        if (kanjiLevel == null || stageLevel == null) return false;
        int kanji = LEVEL_ORDER.indexOf(kanjiLevel);
        int stage = LEVEL_ORDER.indexOf(stageLevel);
        return kanji >= 0 && stage >= 0 && kanji <= stage;
    }
}
