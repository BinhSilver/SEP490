package com.jlas.common;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> api(ApiException exception) {
        return ResponseEntity.status(exception.getErrorCode().getStatus())
                .body(ApiResponse.fail(exception.getMessage(), exception.getErrorCode().name(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        String message = errors.values().stream().findFirst().orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(message, "VALIDATION_ERROR", errors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> status(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "Request failed" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiResponse.fail(message, codeFor(message), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("Malformed JSON request", "MALFORMED_REQUEST", null));
    }

    private static String codeFor(String message) {
        String normalized = message.toLowerCase();
        if (normalized.contains("not verified")) return "ACCOUNT_UNVERIFIED";
        if (normalized.contains("expired")) return "EXPIRED_TOKEN";
        if (normalized.contains("invalid")) return "INVALID_REQUEST";
        return null;
    }
}
