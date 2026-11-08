package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathDayKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathSessionMode;
import com.jlas.jlptpath.enums.JpathSessionTab;
import com.jlas.jlptpath.enums.JpathTab;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [JLAS-W0108] Everything {@link JpathQueueBuilder} needs to order one session.
 *
 * @param grammarPracticeCodes grammar patterns with at least one visible ORDER and one visible FILL_TEXT exercise
 */
public record JpathQueueInput(JpathSessionTab tab, JpathSessionMode mode, JpathDayKind dayKind, boolean kanaPhase,
                              Map<JpathTab, List<DueCard>> due, Map<JpathTab, Integer> reviewRoom,
                              List<String> grammarDue, Map<JpathTab, List<String>> newCodes,
                              Set<String> grammarPracticeCodes, List<KanaRowPlan> kanaRows, boolean skillQuizNeeded) {

    public JpathQueueInput {
        due = due == null ? Map.of() : due;
        reviewRoom = reviewRoom == null ? Map.of() : reviewRoom;
        grammarDue = grammarDue == null ? List.of() : grammarDue;
        newCodes = newCodes == null ? Map.of() : newCodes;
        grammarPracticeCodes = grammarPracticeCodes == null ? Set.of() : grammarPracticeCodes;
        kanaRows = kanaRows == null ? List.of() : kanaRows;
    }

    /** A review card due now. */
    public record DueCard(JpathItemType type, String code, boolean notebook, boolean hard, boolean retype,
                          LocalDateTime nextReviewAt) {
    }

    /** One kana row to learn today: its kana in order, then a KANA_ROW check. */
    public record KanaRowPlan(String rowCode, List<String> kanaCodes) {
    }
}
