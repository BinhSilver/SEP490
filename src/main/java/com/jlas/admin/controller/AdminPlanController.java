package com.jlas.admin.controller;

import com.jlas.admin.dto.AdminPlanOption;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.repository.PremiumPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Premium plans as seen by System Admin; plan management (create/edit/stop selling) builds on this. */
@RestController
@RequestMapping("/api/admin/system/premium-plans")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminPlanController {
    private final PremiumPlanRepository plans;

    @GetMapping
    public ApiResponse<List<AdminPlanOption>> list() {
        return ApiResponse.ok("Gói Premium", plans.findAll(Sort.by("price", "id")).stream()
                .map(AdminPlanOption::from).toList());
    }
}
