package com.jlas.jlptpath.util;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathStepKind;
import com.jlas.jlptpath.enums.JpathTab;

/**
 * [JLAS-W0108] One element of a session queue (jpath_session.queue_json). {@code step} is the 0-based position;
 * card steps carry {@code itemType}/{@code itemCode}, check steps carry {@code checkKind} (and a token once issued).
 */
public record JpathQueueStep(int step, JpathStepKind kind, JpathTab tab, JpathItemType itemType, String itemCode,
                             JpathCheckKind checkKind, String checkToken, boolean repeat) {

    public JpathQueueStep withToken(String token) {
        return new JpathQueueStep(step, kind, tab, itemType, itemCode, checkKind, token, repeat);
    }

    /** Copy as a repeat of this card at {@code newStep} (token dropped). */
    public JpathQueueStep asRepeat(int newStep) {
        return new JpathQueueStep(newStep, kind, tab, itemType, itemCode, checkKind, null, true);
    }

    JpathQueueStep atStep(int newStep) {
        return new JpathQueueStep(newStep, kind, tab, itemType, itemCode, checkKind, checkToken, repeat);
    }
}
