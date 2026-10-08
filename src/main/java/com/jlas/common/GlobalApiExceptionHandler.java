package com.jlas.common;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
                errors.putIfAbsent(error.getField(), vietnamese(error.getDefaultMessage())));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                errors.putIfAbsent(error.getObjectName(), vietnamese(error.getDefaultMessage())));
        String message = errors.values().stream().findFirst().orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(message, "VALIDATION_ERROR", errors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiResponse<Void>> status(ResponseStatusException exception) {
        String message = vietnamese(exception.getReason() == null ? "Request failed" : exception.getReason());
        return ResponseEntity.status(exception.getStatusCode())
                .body(ApiResponse.fail(message, codeFor(message), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("Dữ liệu gửi lên không đúng định dạng.", "MALFORMED_REQUEST", null));
    }

    /** File vượt spring.servlet.multipart.max-file-size / max-request-size: bị chặn trước khi tới controller. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail("File tải lên vượt quá dung lượng cho phép.", "PAYLOAD_TOO_LARGE", null));
    }

    private static String codeFor(String message) {
        String normalized = message.toLowerCase();
        if (normalized.contains("not verified")) return "ACCOUNT_UNVERIFIED";
        if (normalized.contains("expired")) return "EXPIRED_TOKEN";
        if (normalized.contains("invalid")) return "INVALID_REQUEST";
        return null;
    }

    private static String vietnamese(String message) {
        if (message == null || message.isBlank()) return "Yêu cầu không hợp lệ.";
        String normalized = message.toLowerCase();
        if (normalized.contains("invalid email or password")) return "Email hoặc mật khẩu không chính xác.";
        if (normalized.equals("forbidden") || normalized.contains("access denied")) return "Bạn không có quyền thực hiện thao tác này.";
        if (normalized.contains("unauthorized")) return "Vui lòng đăng nhập để tiếp tục.";
        if (normalized.contains("invalid otp") || normalized.contains("otp must")) return "Mã OTP phải gồm đúng 6 chữ số.";
        if (normalized.contains("otp") && normalized.contains("expired")) return "Mã OTP không đúng hoặc đã hết hạn.";
        if (normalized.contains("must not be blank") || normalized.contains("must not be null")) return "Vui lòng điền đầy đủ thông tin bắt buộc.";
        if (normalized.contains("email must") || normalized.contains("well-formed email")) return "Email chưa đúng định dạng.";
        if (normalized.contains("password confirmation")) return "Xác nhận mật khẩu chưa khớp.";
        if (normalized.equals("request failed")) return "Yêu cầu không thành công.";
        return message;
    }
}
