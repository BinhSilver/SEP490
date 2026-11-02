package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathDisplaySettings;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.dto.JpathSettingsView;
import com.jlas.jlptpath.dto.JpathSliderAdjustmentView;
import java.util.List;

/** [JLAS-W0108] Settings of the JLPT path: target, exam, weekly schedule, display and reminders. */
public interface JpathSettingsService {

    /** The settings page; without an ACTIVE path only the display and reminder block. */
    JpathSettingsView get(Long userId);

    /**
     * Applies the given fields (null = keep) after checking all of them; nothing is written when one check fails. A
     * changed target or exam date must be reachable (else JPATH_TARGET_TOO_SOON) and resets the sliders to the
     * recommendation (TARGET_CHANGE, not counted); changed minutes or days keep the sliders. Schedule fields without
     * an ACTIVE path → JPATH_NO_ACTIVE_PATH.
     */
    JpathSettingsView update(Long userId, JpathSettingsUpdateRequest request);

    /** Display settings for the study pages (defaults without a settings row). */
    JpathDisplaySettings display(Long userId);

    /** Slider changes of the ACTIVE path, newest first; empty without one. */
    List<JpathSliderAdjustmentView> sliderHistory(Long userId);
}
