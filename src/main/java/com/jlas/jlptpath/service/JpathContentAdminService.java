package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathAdminItemView;
import com.jlas.jlptpath.dto.JpathContentEditRequest;
import com.jlas.jlptpath.dto.JpathEditExport;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathReportGroupView;
import com.jlas.jlptpath.dto.JpathReportQueueFilter;
import com.jlas.jlptpath.dto.JpathReportResolveRequest;
import com.jlas.jlptpath.enums.JpathContentStatus;

/**
 * [JLAS-W0108] The content creator's "Báo sai" queue: grouped learner reports, in-place fixes of the reported content
 * (every changed column logged in jpath_content_edit), closing reports, and exporting the fixes for the seed. Target
 * types are VOCAB, KANJI, GRAMMAR, KANA, EXAMPLE and QUESTION (case-insensitive); an unknown type or code →
 * JPATH_ITEM_NOT_FOUND; bad input → VALIDATION_FAILED.
 */
public interface JpathContentAdminService {

    /** Response message of a resolve that asked for REVIEWED on a HOLD item (the status is kept). */
    String HOLD_KEPT_MESSAGE = "Mục đang ngừng hiển thị — trạng thái giữ nguyên; đổi trạng thái bằng chỉnh sửa.";

    /** Reported targets of the filter, most reported first, then most recently reported. */
    JpathPage<JpathReportGroupView> queue(JpathReportQueueFilter filter);

    /** The target with its editable fields, its reports and its last edits; HOLD rows included. */
    JpathAdminItemView item(String targetType, String code);

    /**
     * Applies the field edits (all checked before any is applied); each column whose value really changes writes one
     * edit row. Kana and columns outside the whitelist → VALIDATION_FAILED.
     */
    JpathAdminItemView edit(Long editorId, String targetType, String code, JpathContentEditRequest request);

    /**
     * Closes every OPEN report of the target as RESOLVED or REJECTED; optionally clears report_hidden and marks the
     * content REVIEWED, each change logged as one edit row. A HOLD item keeps HOLD: releasing it needs an explicit
     * status edit.
     */
    JpathAdminItemView resolve(Long editorId, String targetType, String code, JpathReportResolveRequest request);

    /**
     * Every edit of {@code level} (cumulative) collapsed to the last value per (target, column), without report_hidden;
     * edits not yet exported are then marked exported ({@code newCount}).
     */
    JpathEditExport exportEdits(String level);

    /** Message for the resolve response: {@link #HOLD_KEPT_MESSAGE} when REVIEWED was asked on a HOLD item. */
    static String resolveMessage(JpathReportResolveRequest request, JpathAdminItemView view) {
        return request.markReviewed() && JpathContentStatus.HOLD.name().equals(view.status())
                ? HOLD_KEPT_MESSAGE : "Đã xử lý báo lỗi";
    }
}
