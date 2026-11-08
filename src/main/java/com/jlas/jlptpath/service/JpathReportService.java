package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathReportRequest;
import com.jlas.jlptpath.dto.JpathReportResponse;

/** [JLAS-W0108] Learner-side content error reports ("Báo sai"). */
public interface JpathReportService {

    /**
     * Stores an OPEN report of {@code userId}. Unknown target → JPATH_ITEM_NOT_FOUND; 20 reports since the start of
     * the learner's day → TOO_MANY_REQUESTS; an OPEN report of the same learner on the same target →
     * JPATH_REPORT_DUPLICATE. Authored content with OPEN reports from 3 distinct learners is hidden (policy #22).
     */
    JpathReportResponse report(Long userId, JpathReportRequest request);
}
