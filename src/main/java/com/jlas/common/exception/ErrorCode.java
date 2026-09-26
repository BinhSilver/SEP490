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
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "External service error"),
    TARGET_NOT_AVAILABLE(HttpStatus.UNPROCESSABLE_ENTITY, "Mục tiêu này đang được phát triển"),
    QUIZ_ATTEMPT_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Bạn đã dùng hết lượt quiz hôm nay, hãy thử lại vào ngày mai"),
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
