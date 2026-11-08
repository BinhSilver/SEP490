package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathLevelCellState;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] Preview of one level from the level bar: item counts (visible when released, planned otherwise), up to
 * 5 sample words (none when not released), how the level opens, the open level's % and the Mock Test link (null when
 * the Mock Test is not available).
 */
public record JpathLevelPreviewView(String level, JpathLevelCellState state, Map<JpathTab, Integer> counts,
        List<JpathPreviewItem> previewItems, String conditionVi, int currentPercent, String mockUrl) {
}
