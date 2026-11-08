package com.jlas.jlptpath.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * [JLAS-W0108] Closes every OPEN report of a target as RESOLVED or REJECTED; {@code unhide} clears report_hidden,
 * {@code markReviewed} sets the content status to REVIEWED.
 */
public record JpathReportResolveRequest(@NotBlank String status, boolean unhide, boolean markReviewed) {
}
