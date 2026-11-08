package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathAdminReportView;

/** [JLAS-W0108] Usage figures of the JLPT path for the system admin. */
public interface JpathAdminReportService {

    /**
     * activeLearners7d = distinct learners with a study event in the last 7 days; dayCompletionPercent30d = non-REST
     * plans of the last 30 days that are all done; averageStreak = mean current streak of learners with an ACTIVE path
     * (0 when none has a streak row); microCheckPassPercent30d = passed / submitted micro-checks of the last 30 days.
     */
    JpathAdminReportView report();
}
