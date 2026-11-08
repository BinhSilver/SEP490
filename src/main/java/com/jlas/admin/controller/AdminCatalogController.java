package com.jlas.admin.controller;

import com.jlas.admin.dto.AdminReasonRequest;
import com.jlas.admin.service.AdminCatalogService;
import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
@RequestMapping("/api/admin/system")
public class AdminCatalogController {
    private final AdminCatalogService catalog;
    @GetMapping("/coupons") public ApiResponse<List<AdminCatalogService.CouponView>> coupons() {
        return ApiResponse.ok("Mã giảm giá", catalog.coupons());
    }
    @PostMapping("/coupons") public ApiResponse<AdminCatalogService.CouponView> createCoupon(
            @RequestBody AdminCatalogService.CouponInput input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã tạo mã", catalog.createCoupon(actor.userId(), input));
    }
    @PutMapping("/coupons/{id}") public ApiResponse<AdminCatalogService.CouponView> updateCoupon(
            @PathVariable Long id, @RequestBody AdminCatalogService.CouponInput input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã cập nhật mã", catalog.updateCoupon(actor.userId(), id, input));
    }
    @PostMapping("/coupons/{id}/activate") public ApiResponse<AdminCatalogService.CouponView> activateCoupon(
            @PathVariable Long id, @Valid @RequestBody AdminReasonRequest input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã kích hoạt mã", catalog.activeCoupon(actor.userId(), id, true, input.getReason()));
    }
    @PostMapping("/coupons/{id}/deactivate") public ApiResponse<AdminCatalogService.CouponView> deactivateCoupon(
            @PathVariable Long id, @Valid @RequestBody AdminReasonRequest input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã vô hiệu hóa mã", catalog.activeCoupon(actor.userId(), id, false, input.getReason()));
    }
    @DeleteMapping("/coupons/{id}") public ApiResponse<Void> deleteCoupon(
            @PathVariable Long id, @Valid @RequestBody AdminReasonRequest input, @AuthenticationPrincipal UserPrincipal actor) {
        catalog.deleteCoupon(actor.userId(), id, input.getReason()); return ApiResponse.ok("Đã xóa mã", null);
    }
    @GetMapping("/credit-packages") public ApiResponse<List<AdminCatalogService.PackageView>> packages() {
        return ApiResponse.ok("Gói CP", catalog.packages());
    }
    @PostMapping("/credit-packages") public ApiResponse<AdminCatalogService.PackageView> createPackage(
            @RequestBody AdminCatalogService.PackageInput input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã tạo gói", catalog.createPackage(actor.userId(), input));
    }
    @PutMapping("/credit-packages/{id}") public ApiResponse<AdminCatalogService.PackageView> updatePackage(
            @PathVariable Long id, @RequestBody AdminCatalogService.PackageInput input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã cập nhật gói", catalog.updatePackage(actor.userId(), id, input));
    }
    @PostMapping("/credit-packages/{id}/activate") public ApiResponse<AdminCatalogService.PackageView> activatePackage(
            @PathVariable Long id, @Valid @RequestBody AdminReasonRequest input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã mở bán", catalog.activePackage(actor.userId(), id, true, input.getReason()));
    }
    @PostMapping("/credit-packages/{id}/deactivate") public ApiResponse<AdminCatalogService.PackageView> deactivatePackage(
            @PathVariable Long id, @Valid @RequestBody AdminReasonRequest input, @AuthenticationPrincipal UserPrincipal actor) {
        return ApiResponse.ok("Đã ngừng bán", catalog.activePackage(actor.userId(), id, false, input.getReason()));
    }
    @DeleteMapping("/credit-packages/{id}") public ApiResponse<Void> deletePackage(
            @PathVariable Long id, @Valid @RequestBody AdminReasonRequest input, @AuthenticationPrincipal UserPrincipal actor) {
        catalog.deletePackage(actor.userId(), id, input.getReason()); return ApiResponse.ok("Đã xóa gói", null);
    }
}
