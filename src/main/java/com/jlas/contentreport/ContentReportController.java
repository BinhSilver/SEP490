package com.jlas.contentreport;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.jlas.contentreport.ContentReportDtos.*;

@RestController @RequiredArgsConstructor
public class ContentReportController {
    private final ContentReportService service;
    @PostMapping("/api/learning-content-reports")
    public ResponseEntity<ApiResponse<Saved>> create(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody Request request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Đã gửi báo cáo",service.create(id(user),request)));
    }
    @GetMapping("/api/admin/learning-content-reports")
    public ApiResponse<ResultPage> list(@RequestParam(defaultValue="OPEN") Status status,
            @RequestParam(required=false) Target type,@RequestParam(defaultValue="0") int page) {
        return ApiResponse.ok("OK",service.list(status,type,page));
    }
    @GetMapping("/api/admin/learning-content-reports/{id}")
    public ApiResponse<Row> detail(@PathVariable Long id) {return ApiResponse.ok("OK",service.detail(id));}
    @PatchMapping("/api/admin/learning-content-reports/{id}")
    public ApiResponse<Row> resolve(@PathVariable Long id,@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody Resolution request) {
        return ApiResponse.ok("Đã cập nhật báo cáo",service.resolve(id,id(user),request));
    }
    private static Long id(UserPrincipal user){if(user==null)throw new ApiException(ErrorCode.UNAUTHORIZED);return user.userId();}
}
