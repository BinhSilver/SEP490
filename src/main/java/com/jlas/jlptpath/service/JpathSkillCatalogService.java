package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathSkillOverview;

/** [JLAS-W0108] The Đọc · Nghe tab: skill catalogue of a level with the learner's state. */
public interface JpathSkillCatalogService {

    /**
     * Overview of {@code level} (null/blank = the open level). Creates today's plan when missing (not inside a writer
     * transaction). Unknown level → BAD_REQUEST; level not released → JPATH_LEVEL_NOT_RELEASED; no ACTIVE path →
     * JPATH_NO_ACTIVE_PATH.
     */
    JpathSkillOverview overview(Long userId, String level);
}
