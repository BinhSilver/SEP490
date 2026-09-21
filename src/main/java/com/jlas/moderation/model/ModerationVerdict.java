package com.jlas.moderation.model;

/**
 * Kết quả kiểm duyệt nội dung.
 * NOT_SCANNED = chưa quét (dữ liệu cũ hoặc private).
 */
public enum ModerationVerdict {
    NOT_SCANNED,
    SAFE,
    REVIEW,
    BLOCKED,
    ERROR
}
