package com.jlas.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
    EMAIL_UNVERIFIED(HttpStatus.UNAUTHORIZED, "Email is not verified"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "Conflict"),
    PENDING_VERIFICATION(HttpStatus.CONFLICT, "Email is pending verification"),
    CONTENT_BLOCKED(HttpStatus.BAD_REQUEST, "Content does not meet community guidelines"),
    CONTENT_PENDING_REVIEW(HttpStatus.BAD_REQUEST, "Content requires review before publishing"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INSUFFICIENT_CREDIT(HttpStatus.PAYMENT_REQUIRED, "Not enough credits"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload quá lớn"),
    /** [JLAS2-XX] Ảnh tải lên sai định dạng hoặc nội dung không phải ảnh thật (magic bytes không khớp). */
    INVALID_IMAGE(HttpStatus.BAD_REQUEST, "Ảnh chỉ nhận định dạng JPG, PNG hoặc WEBP"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "External service error"),
    TARGET_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Mục tiêu này đang được phát triển"),
    TARGET_LEVEL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_ENTITY,
            "Mục tiêu vượt quá khoảng cách phù hợp với trình độ hiện tại"),
    QUIZ_ATTEMPT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Bạn đã dùng hết lượt quiz hôm nay, hãy thử lại vào ngày mai"),
    /** [JLAS-W0108] P4/P5 — học liệu/đề chưa đủ để phát quiz (BLOCKED_CONTENT); không phải người học trượt. */
    QUIZ_NOT_READY(HttpStatus.UNPROCESSABLE_ENTITY, "Chưa có đủ học liệu để tạo bài kiểm tra"),
    /** [JLAS-W0108] P5 — chưa hoàn thành hoạt động bắt buộc trước đó (gate/prerequisite); không phải trượt quiz. */
    PREREQUISITE_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, "Bạn cần hoàn thành các hoạt động trước đó trước"),
    /** [JLAS-W0108] P6 — cấp trong lộ trình chưa đủ học liệu đã duyệt (readiness BLOCKED_CONTENT);
     *  bị chặn minh bạch, KHÔNG fallback demo và KHÔNG tính là người học trượt. */
    BLOCKED_CONTENT(HttpStatus.UNPROCESSABLE_ENTITY, "Chưa có đủ học liệu cho cấp độ này"),
    /** [JLAS-W0108] A legacy issued quiz has no immutable passage/audio snapshot and cannot be replayed safely. */
    SOURCE_SNAPSHOT_UNAVAILABLE(HttpStatus.CONFLICT, "Lượt học cũ không có bản nguồn để ôn lại an toàn"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() { return status; }
    public String getDefaultMessage() { return defaultMessage; }
}
