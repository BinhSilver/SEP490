package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathEntryPreferenceRequest;
import com.jlas.jlptpath.dto.JpathEntryStatusView;
import com.jlas.jlptpath.entity.JpathUserPath;
import java.util.Optional;

/** [JLAS-W0108] Entering the JLPT path and the learner's path. */
public interface JpathPathService {

    JpathEntryStatusView entryStatus(Long userId);

    /**
     * Saves the entry answers. A learner who never studied gets kana NONE; one who studied must pick REMEMBER or
     * REVIEW (VALIDATION_FAILED otherwise). Days must pass {@link com.jlas.jlptpath.util.JpathDays#validate}.
     */
    JpathEntryStatusView saveEntryPreference(Long userId, JpathEntryPreferenceRequest request);

    Optional<JpathUserPath> findActive(Long userId);

    /** The ACTIVE path; JPATH_NO_ACTIVE_PATH when there is none. */
    JpathUserPath requireActive(Long userId);
}
