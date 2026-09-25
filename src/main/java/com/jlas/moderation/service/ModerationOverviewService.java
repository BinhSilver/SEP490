package com.jlas.moderation.service;

import com.jlas.moderation.report.ReportReviewService;
import com.jlas.moderation.repository.ReviewCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pending-work counters for the admin overview page. */
@Service
@RequiredArgsConstructor
public class ModerationOverviewService {
    private final ReportReviewService reports;
    private final ModerationAdminService queue;
    private final ReviewCaseRepository reviewCases;
    private final UserSanctionService sanctions;
    private final DenylistService denylist;

    public record Overview(boolean reportsAvailable, int pendingReports, int pendingQueue,
                           int pendingContributions, long pendingAppeals, int activeSanctions,
                           int activeDenylistTerms) {
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        DenylistService.Snapshot terms = denylist.current();
        return new Overview(reports.available(), reports.pending().size(),
                queue.listContentQueue().size(), queue.listDictionaryContributions().size(),
                reviewCases.countByCaseTypeAndStatus("APPEAL", "OPEN"), sanctions.allActive().size(),
                terms.block().size() + terms.accentSensitiveBlock().size() + terms.review().size());
    }
}
