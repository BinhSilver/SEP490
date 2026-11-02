package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;

/** [JLAS-W0108] Learner state of one item after a notebook action; state NEW|LEARNING|NOTEBOOK|KNOWN. */
public record JpathItemStateView(JpathItemType itemType, String code, String state, boolean notebook) {
}
