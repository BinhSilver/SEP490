package com.jlas.jlptpath.dto;

import java.util.List;
import java.util.Map;

/**
 * [JLAS-W0108] A reported target in the editor screen. {@code fields} maps every editable column of the target
 * (snake_case, see JpathEditableFields) to its current value; for a grammar question this includes
 * {@code answer_index}, {@code answer_text} and {@code accept_json}. The rule "answers never reach the API before the
 * question is locked" covers learner APIs only: this view is served solely by the role-gated admin API (CONTENT_CREATOR,
 * ADMIN), which is exempt (pre-flight ruling D6). {@code context} is the owner line of an example or question and the
 * item's own line otherwise; {@code status} is null for kana; {@code reports} are the newest 50, {@code edits} the
 * newest 20.
 */
public record JpathAdminItemView(String targetType, String targetCode, String itemType, String itemCode, String level,
                                 String status, boolean reportHidden, boolean authored, Map<String, String> fields,
                                 String context, String audioUrl, List<JpathReportRowView> reports,
                                 List<JpathContentEditView> edits) {
}
