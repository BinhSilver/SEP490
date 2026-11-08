package com.jlas.jlptpath.dto;

import java.util.List;

/**
 * [JLAS-W0108] The "Luyện thêm" strip for the learner's open {@code level}: practice tools ({@code links}) and the
 * path's own side pages ({@code pages}).
 */
public record JpathExtraLinksView(String level, List<JpathExtraLink> links, List<JpathExtraLink> pages) {
}
