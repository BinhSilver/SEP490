package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathDisplaySettings;
import com.jlas.jlptpath.dto.JpathPathView;
import com.jlas.jlptpath.dto.JpathSettingsUpdateRequest;
import com.jlas.jlptpath.dto.JpathSettingsView;
import com.jlas.jlptpath.dto.JpathSliderAdjustmentView;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** [JLAS-W0108] Settings of the JLPT path: target, exam, weekly schedule, display and reminders. */
public interface JpathSettingsService {

    /** What the learner types to confirm a restart. */
    String RESTART_TEXT = Normalizer.normalize("BẮT ĐẦU LẠI", Normalizer.Form.NFC);

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

    /**
     * Studies a lower level: a released level (JPATH_LEVEL_NOT_RELEASED; not N1..N5 → VALIDATION_FAILED) below the
     * open level (VALIDATION_FAILED otherwise) becomes the open level. Its level row is created when missing
     * (recommended sliders, LEVEL_START logged, not counted); rows of levels already studied stay as they were. No
     * ACTIVE path → JPATH_NO_ACTIVE_PATH.
     */
    JpathPathView lowerLevel(Long userId, String level);

    /** AI 1 #13: back to a higher level already reached (see JpathSettingsView.returnLevels). */
    JpathPathView returnLevel(Long userId, String level);

    /**
     * Restart from the beginning (spec 4.12): the text must pass {@link #restartTextMatches}
     * (JPATH_RESTART_CONFIRM_MISMATCH). Deletes the learner's items, daily plans, study sessions, check sets and study
     * events, and archives the ACTIVE or PAUSED path (none → JPATH_NO_ACTIVE_PATH); level rows, the slider log,
     * settings, content reports, badges, reminders, credit points and the team notebook are kept.
     */
    void restart(Long userId, String confirmText);

    /** Whether {@code text}, trimmed, NFC and upper-cased the Vietnamese way, reads "BẮT ĐẦU LẠI". */
    static boolean restartTextMatches(String text) {
        return text != null && Normalizer.normalize(text.strip(), Normalizer.Form.NFC)
                .toUpperCase(Locale.forLanguageTag("vi")).equals(RESTART_TEXT);
    }
}
