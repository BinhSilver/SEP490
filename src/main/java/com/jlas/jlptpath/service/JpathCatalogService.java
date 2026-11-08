package com.jlas.jlptpath.service;

import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.List;
import java.util.Set;

/** [JLAS-W0108] Which JLPT levels exist, which are released, and which content statuses learners see. */
public interface JpathCatalogService {

    /** Always the five levels N5→N1; a level without a catalogue row is coming soon with 0 items. */
    List<JpathLevelInfo> levels();

    /** Whether learners can study {@code level}; false for an unknown or blank level. */
    boolean isReleased(String level);

    /** The released level with the largest sort order (towards N1), or null when nothing is released. */
    String highestReleasedLevel();

    /**
     * The released level closest to {@code level} at or below it (trimmed, upper-cased).
     *
     * @throws com.jlas.common.exception.ApiException BAD_REQUEST when {@code level} is not N1..N5,
     *         JPATH_LEVEL_NOT_RELEASED when no released level is at or below it
     */
    String capToReleased(String level);

    /** REVIEWED and AI_CHECKED, plus DRAFT in draft preview; never HOLD. */
    Set<JpathContentStatus> visibleStatuses();

    /** Catalogue entry of {@code level}; BAD_REQUEST when it is not N1..N5. */
    JpathLevelInfo info(String level);
}
