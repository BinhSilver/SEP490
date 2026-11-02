package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathReportRequest;
import com.jlas.jlptpath.dto.JpathReportResponse;
import com.jlas.jlptpath.dto.JpathReportTargetRef;
import com.jlas.jlptpath.entity.JpathContentEdit;
import com.jlas.jlptpath.entity.JpathContentReport;
import com.jlas.jlptpath.enums.JpathReportError;
import com.jlas.jlptpath.enums.JpathReportTarget;
import com.jlas.jlptpath.repository.JpathContentEditRepository;
import com.jlas.jlptpath.repository.JpathContentReportRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathReportService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] Learner reports (plan decision 19, policy #22): at most {@link #DAILY_LIMIT} per learner per study day,
 * one OPEN report per learner and target, and authored content with OPEN reports from {@link #HIDE_REPORTERS}
 * distinct learners is hidden at once (logged as a system edit, editor 0). Kana are never hidden. The response never
 * tells whether the report hid the content.
 */
@Service
public class JpathReportServiceImpl implements JpathReportService {

    static final int DAILY_LIMIT = 20;
    static final int HIDE_REPORTERS = 3;
    static final int NOTE_MAX = 500;
    private static final String OPEN = "OPEN";
    private static final long SYSTEM_EDITOR = 0L;
    private static final String HIDDEN_FIELD = "report_hidden";
    private static final String DAILY_LIMIT_MESSAGE = "Bạn đã gửi 20 báo lỗi hôm nay. Hãy thử lại vào ngày mai.";

    private final JpathContentReportRepository reports;
    private final JpathContentEditRepository edits;
    private final JpathReportTargets targets;
    private final JpathClock clock;

    public JpathReportServiceImpl(JpathContentReportRepository reports, JpathContentEditRepository edits,
                                  JpathReportTargets targets, JpathClock clock) {
        this.reports = reports;
        this.edits = edits;
        this.targets = targets;
        this.clock = clock;
    }

    @Override
    @Transactional
    public JpathReportResponse report(Long userId, JpathReportRequest request) {
        JpathReportError error = errorType(request.errorType());
        String note = note(request.note());
        JpathReportTargetRef ref = targets.resolve(targetType(request.targetType()), trim(request.targetCode()))
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));

        if (reports.countByReporterUserIdAndCreatedAtGreaterThanEqual(userId, dayStart(userId)) >= DAILY_LIMIT) {
            throw new ApiException(ErrorCode.TOO_MANY_REQUESTS, DAILY_LIMIT_MESSAGE);
        }
        if (reports.existsByReporterUserIdAndTargetTypeAndTargetCodeAndStatus(userId, ref.type(), ref.code(), OPEN)) {
            throw new ApiException(ErrorCode.JPATH_REPORT_DUPLICATE);
        }

        JpathContentReport saved;
        try {
            saved = reports.saveAndFlush(JpathContentReport.builder().reporterUserId(userId).targetType(ref.type())
                    .targetCode(ref.code()).itemType(ref.itemType()).itemCode(ref.itemCode()).level(ref.level())
                    .errorType(error).note(note).pageContext(request.pageContext()).status(OPEN).build());
        } catch (DataIntegrityViolationException e) {
            // a second tab or a double click won the race on uk_jpath_report_open
            throw new ApiException(ErrorCode.JPATH_REPORT_DUPLICATE);
        }

        if (ref.authored() && !ref.hidden()
                && reports.countDistinctOpenReporters(ref.type(), ref.code()) >= HIDE_REPORTERS) {
            targets.setHidden(ref, true);
            edits.save(JpathContentEdit.builder().editorUserId(SYSTEM_EDITOR).targetType(ref.type())
                    .targetCode(ref.code()).level(ref.level()).fieldName(HIDDEN_FIELD).oldValue("0").newValue("1")
                    .reportId(saved.getId()).build());
        }
        return new JpathReportResponse(saved.getId());
    }

    /** Start of the learner's study day, as server time (the clock of created_at). */
    private LocalDateTime dayStart(Long userId) {
        Duration sinceMidnight = Duration.between(clock.today(userId).atStartOfDay(clock.zone(userId)),
                clock.zonedNow(userId));
        return clock.now().minus(sinceMidnight);
    }

    private static JpathReportTarget targetType(String raw) {
        try {
            return raw == null ? null : JpathReportTarget.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // reads as an unknown target
        }
    }

    private static JpathReportError errorType(String raw) {
        try {
            return JpathReportError.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Loại lỗi không hợp lệ.");
        }
    }

    private static String note(String raw) {
        String note = trim(raw);
        if (note == null || note.isEmpty()) {
            return null;
        }
        if (note.length() > NOTE_MAX) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Ghi chú tối đa 500 ký tự.");
        }
        return note;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
