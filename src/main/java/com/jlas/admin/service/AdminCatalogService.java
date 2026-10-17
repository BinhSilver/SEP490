package com.jlas.admin.service;

import com.jlas.admin.repository.AdminUserRepository;
import com.jlas.billing.entity.Coupon;
import com.jlas.billing.entity.CreditPackage;
import com.jlas.billing.repository.BillingPaymentRepository;
import com.jlas.billing.repository.CouponRepository;
import com.jlas.billing.repository.CreditPackageRepository;
import com.jlas.billing.service.CouponService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class AdminCatalogService {
    private final CouponRepository coupons;
    private final CreditPackageRepository packages;
    private final BillingPaymentRepository payments;
    private final AdminUserRepository users;
    private final AdminAuditService audit;

    public record CouponInput(String code, String description, String discountType, BigDecimal discountValue,
                              BigDecimal maxDiscount, BigDecimal minOrderAmount, String appliesTo,
                              LocalDateTime startsAt, LocalDateTime endsAt, Integer usageLimit,
                              Integer perUserLimit, String reason) { }
    public record PackageInput(String name, String description, Integer creditAmount, BigDecimal price, String reason) { }
    public record CouponView(Long id, String code, String description, String discountType, BigDecimal discountValue,
                             BigDecimal maxDiscount, BigDecimal minOrderAmount, String appliesTo,
                             LocalDateTime startsAt, LocalDateTime endsAt, Integer usageLimit, Integer perUserLimit,
                             boolean active, long usedCount, long reservedCount) { }
    public record PackageView(Long id, String name, String description, int creditAmount, BigDecimal price,
                              boolean active, long soldCount) { }

    public List<CouponView> coupons() { return coupons.findAll(Sort.by("id")).stream().map(this::view).toList(); }
    public List<PackageView> packages() { return packages.findAll(Sort.by("id")).stream().map(this::view).toList(); }
    private CouponView view(Coupon c) {
        long used = payments.countByCoupon_IdAndStatus(c.getId(), "PAID");
        long reserved = payments.countCouponUses(c.getId(), LocalDateTime.now()) - used;
        return new CouponView(c.getId(), c.getCode(), c.getDescription(), c.getDiscountType(), c.getDiscountValue(),
                c.getMaxDiscount(), c.getMinOrderAmount(), c.getAppliesTo(), c.getStartsAt(), c.getEndsAt(),
                c.getUsageLimit(), c.getPerUserLimit(), Boolean.TRUE.equals(c.getIsActive()), used, reserved);
    }
    private PackageView view(CreditPackage p) {
        return new PackageView(p.getId(), p.getName(), p.getDescription(), p.getCreditAmount(), p.getPrice(),
                Boolean.TRUE.equals(p.getIsActive()), payments.countByCreditPackage_IdAndStatus(p.getId(), "PAID"));
    }
    private static Map<String, Object> snapshot(Coupon c) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", c.getCode()); value.put("description", c.getDescription());
        value.put("discountType", c.getDiscountType()); value.put("discountValue", c.getDiscountValue());
        value.put("maxDiscount", c.getMaxDiscount()); value.put("minOrderAmount", c.getMinOrderAmount());
        value.put("appliesTo", c.getAppliesTo());
        value.put("startsAt", c.getStartsAt() == null ? null : c.getStartsAt().toString());
        value.put("endsAt", c.getEndsAt() == null ? null : c.getEndsAt().toString());
        value.put("usageLimit", c.getUsageLimit()); value.put("perUserLimit", c.getPerUserLimit());
        value.put("active", c.getIsActive()); return value;
    }
    private static Map<String, Object> snapshot(CreditPackage p) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", p.getName()); value.put("description", p.getDescription());
        value.put("creditAmount", p.getCreditAmount()); value.put("price", p.getPrice());
        value.put("active", p.getIsActive()); return value;
    }
    private static void reason(String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() < 5 || reason.length() > 500)
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng nhập lý do từ 5 đến 500 ký tự.");
    }
    private void reject(Long actor, String action, String target, Long id, String reason, String message) {
        audit.recordRejected(actor, action, target, id == null ? null : id.toString(), reason,
                Map.of("rejection", message));
        throw new ApiException(ErrorCode.CONFLICT, message);
    }
    private static boolean positive(BigDecimal x) { return x != null && x.signum() > 0 && x.scale() <= 2 && x.compareTo(new BigDecimal("9999999999.99")) <= 0; }
    private static boolean wholeVnd(BigDecimal x) { return x.stripTrailingZeros().scale() <= 0; }
    private void validate(Long actor, Long id, CouponInput r, String action) {
        reason(r.reason());
        String code;
        try { code = CouponService.normalize(r.code()); }
        catch (ApiException e) { reject(actor, action, "COUPON", id, r.reason(), "Mã giảm giá phải có 3-40 ký tự A-Z, 0-9, _ hoặc -."); return; }
        if (code == null || r.discountType() == null || !Set.of("PERCENT", "FIXED").contains(r.discountType()) || !positive(r.discountValue())
                || "FIXED".equals(r.discountType()) && !wholeVnd(r.discountValue())
                || "PERCENT".equals(r.discountType()) && (r.discountValue().compareTo(BigDecimal.ONE) < 0
                    || r.discountValue().compareTo(new BigDecimal("100")) > 0)
                || r.maxDiscount() != null && (!positive(r.maxDiscount()) || !wholeVnd(r.maxDiscount()) || !"PERCENT".equals(r.discountType()))
                || r.description() != null && r.description().length() > 500
                || r.minOrderAmount() != null && (r.minOrderAmount().signum() < 0 || r.minOrderAmount().scale() > 2
                    || !wholeVnd(r.minOrderAmount())
                    || r.minOrderAmount().compareTo(new BigDecimal("9999999999.99")) > 0)
                || r.appliesTo() == null || !Set.of("ALL", "PREMIUM", "CREDIT").contains(r.appliesTo())
                || r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())
                || r.usageLimit() != null && r.usageLimit() <= 0 || r.perUserLimit() != null && r.perUserLimit() <= 0)
            reject(actor, action, "COUPON", id, r.reason(), "Thông tin mã giảm giá không hợp lệ.");
        if (coupons.findByCode(code).filter(c -> !Objects.equals(c.getId(), id)).isPresent())
            reject(actor, action, "COUPON", id, r.reason(), "Mã giảm giá đã tồn tại.");
    }
    private void fill(Coupon c, CouponInput r) {
        c.setCode(CouponService.normalize(r.code())); c.setDescription(r.description());
        c.setDiscountType(r.discountType()); c.setDiscountValue(r.discountValue());
        c.setMaxDiscount(r.maxDiscount()); c.setMinOrderAmount(r.minOrderAmount()); c.setAppliesTo(r.appliesTo());
        c.setStartsAt(r.startsAt()); c.setEndsAt(r.endsAt()); c.setUsageLimit(r.usageLimit()); c.setPerUserLimit(r.perUserLimit());
    }
    @Transactional public CouponView createCoupon(Long actor, CouponInput r) {
        reason(r.reason()); users.lockAdminRole(); validate(actor, null, r, "COUPON_CREATE");
        Coupon c = new Coupon(); fill(c, r); c.setIsActive(true); coupons.saveAndFlush(c);
        audit.record(actor, "COUPON_CREATE", "COUPON", c.getId().toString(), r.reason(), "SUCCESS", Map.of("code", c.getCode()));
        return view(c);
    }
    @Transactional public CouponView updateCoupon(Long actor, Long id, CouponInput r) {
        reason(r.reason()); users.lockAdminRole();
        Coupon c = coupons.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mã giảm giá."));
        validate(actor, id, r, "COUPON_UPDATE");
        if (!c.getCode().equals(CouponService.normalize(r.code())) && payments.countByCoupon_Id(id) > 0)
            reject(actor, "COUPON_UPDATE", "COUPON", id, r.reason(), "Mã đã được dùng, không thể đổi mã. Hãy tạo mã mới.");
        Map<String, Object> before = snapshot(c); fill(c, r);
        audit.record(actor, "COUPON_UPDATE", "COUPON", id.toString(), r.reason(), "SUCCESS", Map.of("before", before, "after", snapshot(c)));
        return view(c);
    }
    @Transactional public CouponView activeCoupon(Long actor, Long id, boolean active, String reason) {
        reason(reason); users.lockAdminRole();
        Coupon c = coupons.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mã giảm giá."));
        if (Boolean.TRUE.equals(c.getIsActive()) == active) reject(actor, "COUPON_ACTIVE", "COUPON", id, reason, "Trạng thái mã không thay đổi.");
        c.setIsActive(active);
        audit.record(actor, "COUPON_ACTIVE", "COUPON", id.toString(), reason, "SUCCESS", Map.of("active", active));
        return view(c);
    }
    @Transactional public void deleteCoupon(Long actor, Long id, String reason) {
        reason(reason); users.lockAdminRole();
        Coupon c = coupons.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy mã giảm giá."));
        if (payments.countByCoupon_Id(id) > 0) reject(actor, "COUPON_DELETE", "COUPON", id, reason, "Mã đã có đơn hàng. Hãy vô hiệu hóa thay vì xóa.");
        coupons.delete(c); audit.record(actor, "COUPON_DELETE", "COUPON", id.toString(), reason, "SUCCESS", Map.of("code", c.getCode()));
    }
    private void validate(Long actor, Long id, PackageInput r, String action) {
        reason(r.reason());
        if (r.name() == null || r.name().isBlank() || r.name().length() > 100 || r.description() != null && r.description().length() > 500
                || r.creditAmount() == null || r.creditAmount() <= 0 || !positive(r.price())
                || r.price().stripTrailingZeros().scale() > 0 || r.price().compareTo(CouponService.MIN_PAYABLE) < 0)
            reject(actor, action, "CREDIT_PACKAGE", id, r.reason(), "Thông tin gói CP không hợp lệ.");
        if (packages.findAll().stream().anyMatch(p -> !Objects.equals(p.getId(), id) && p.getName().equalsIgnoreCase(r.name().trim())))
            reject(actor, action, "CREDIT_PACKAGE", id, r.reason(), "Tên gói CP đã tồn tại.");
    }
    private void fill(CreditPackage p, PackageInput r) {
        p.setName(r.name().trim()); p.setDescription(r.description()); p.setCreditAmount(r.creditAmount()); p.setPrice(r.price());
    }
    @Transactional public PackageView createPackage(Long actor, PackageInput r) {
        reason(r.reason()); users.lockAdminRole(); validate(actor, null, r, "CREDIT_PACKAGE_CREATE");
        CreditPackage p = new CreditPackage(); fill(p, r); p.setIsActive(true); packages.saveAndFlush(p);
        audit.record(actor, "CREDIT_PACKAGE_CREATE", "CREDIT_PACKAGE", p.getId().toString(), r.reason(), "SUCCESS", Map.of("name", p.getName()));
        return view(p);
    }
    @Transactional public PackageView updatePackage(Long actor, Long id, PackageInput r) {
        reason(r.reason()); users.lockAdminRole();
        CreditPackage p = packages.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy gói CP."));
        validate(actor, id, r, "CREDIT_PACKAGE_UPDATE"); Map<String, Object> before = snapshot(p); fill(p, r);
        audit.record(actor, "CREDIT_PACKAGE_UPDATE", "CREDIT_PACKAGE", id.toString(), r.reason(), "SUCCESS", Map.of("before", before, "after", snapshot(p)));
        return view(p);
    }
    @Transactional public PackageView activePackage(Long actor, Long id, boolean active, String reason) {
        reason(reason); users.lockAdminRole();
        CreditPackage p = packages.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy gói CP."));
        if (Boolean.TRUE.equals(p.getIsActive()) == active) reject(actor, "CREDIT_PACKAGE_ACTIVE", "CREDIT_PACKAGE", id, reason, "Trạng thái gói không thay đổi.");
        p.setIsActive(active);
        audit.record(actor, "CREDIT_PACKAGE_ACTIVE", "CREDIT_PACKAGE", id.toString(), reason, "SUCCESS", Map.of("active", active));
        return view(p);
    }
    @Transactional public void deletePackage(Long actor, Long id, String reason) {
        reason(reason); users.lockAdminRole();
        CreditPackage p = packages.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy gói CP."));
        if (payments.countByCreditPackage_Id(id) > 0) reject(actor, "CREDIT_PACKAGE_DELETE", "CREDIT_PACKAGE", id, reason, "Gói đã có đơn hàng. Hãy ngừng bán thay vì xóa.");
        packages.delete(p); audit.record(actor, "CREDIT_PACKAGE_DELETE", "CREDIT_PACKAGE", id.toString(), reason, "SUCCESS", Map.of("name", p.getName()));
    }
}
