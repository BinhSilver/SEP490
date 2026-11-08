package com.jlas.jlptpath.enums;

/**
 * [JLAS-W0108] What the path knows about the learner's latest JLPT mock attempt of a level (Plan 3 Task 17).
 * NONE: no usable attempt row (today always, until the Mock team writes jlpt_mock_attempt); PENDING: a usable attempt
 * not applied yet ("Có kết quả mới — Cập nhật"); APPLIED: the attempt is in jpath_mock_result.
 */
public enum JpathMockResultState {
    NONE, PENDING, APPLIED
}
