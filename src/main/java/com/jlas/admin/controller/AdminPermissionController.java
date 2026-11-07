package com.jlas.admin.controller;

import com.jlas.admin.service.AdminPermissionMatrix;
import com.jlas.auth.dto.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** [JLAS2-51] Read-only role × area matrix; roles are changed on the Users page. */
@RestController
@RequestMapping("/api/admin/system/permissions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPermissionController {
    @GetMapping
    public ApiResponse<AdminPermissionMatrix.Matrix> matrix() {
        return ApiResponse.ok("Phân quyền", AdminPermissionMatrix.matrix());
    }
}
