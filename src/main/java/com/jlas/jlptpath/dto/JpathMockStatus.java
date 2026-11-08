package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathMockOutcome;
import com.jlas.jlptpath.enums.JpathMockResultState;
import com.jlas.jlptpath.enums.JpathMockSection;
import com.jlas.learning.dto.MockTestDestination;
import java.time.LocalDateTime;
import java.util.List;

/**
 * [JLAS-W0108] The "Thi thử" card of a path level (Plan 3 Task 17). {@code destination} comes from the Mock team's
 * {@code MockTestDestinationProvider} as is: no url unless it is AVAILABLE. {@code resultState}/{@code notice} say
 * whether a mock attempt row exists yet; outcome and sections always come from the Mock module, never from the client.
 * {@code sections} is a list (module order) so two unknown part codes both stay visible.
 */
public record JpathMockStatus(String level, MockTestDestination destination, JpathMockResultState resultState,
        String notice, Long attemptId, JpathMockOutcome lastOutcome, Integer lastScore, List<SectionView> sections,
        LocalDateTime retakeAt, List<Advice> advice, boolean levelPassed, String nextLevel) {

    /** One Mock part: module code and title (title falls back to the code), mapped section (#43), weak flag. */
    public record SectionView(String code, String title, JpathMockSection section, int scorePct, boolean passed,
            boolean weak) { }

    /** Advice for a weak section after a failed attempt (#43). */
    public record Advice(JpathMockSection section, String action, String labelVi) { }
}
