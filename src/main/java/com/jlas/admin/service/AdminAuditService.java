package com.jlas.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.admin.entity.AdminAuditLog;
import com.jlas.admin.dto.AdminAuditLogView;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import com.jlas.admin.repository.AdminAuditLogRepository;
import com.jlas.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.util.ArrayList;

@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private static final Pattern SENSITIVE = Pattern.compile(
            "password|secret|token|otp|api[_-]?key|credential|hash", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminAuditLogRepository logs;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public Page<AdminAuditLogView> list(Long actorId, String action, String targetType, String targetId,
                                       String result, LocalDateTime from, LocalDateTime to, int page, int size) {
        if (result != null && !List.of("SUCCESS", "REJECTED").contains(result)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Kết quả phải là SUCCESS hoặc REJECTED.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Khoảng thời gian không hợp lệ.");
        }
        return logs.findAll((root, query, cb) -> {
            var filters = new ArrayList<Predicate>();
            if (actorId != null) filters.add(cb.equal(root.get("actor").get("id"), actorId));
            if (action != null) filters.add(cb.equal(root.get("action"), action));
            if (targetType != null) filters.add(cb.equal(root.get("targetType"), targetType));
            if (targetId != null) filters.add(cb.equal(root.get("targetId"), targetId));
            if (result != null) filters.add(cb.equal(root.get("result"), result));
            if (from != null) filters.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            if (to != null) filters.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            return cb.and(filters.toArray(Predicate[]::new));
        }, AdminUserService.pageRequest(page, size)).map(AdminAuditLogView::from);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actorId, String action, String targetType, String targetId,
                       String reason, String result, Map<String, Object> changes) {
        String json;
        try {
            json = changes == null ? null : JSON.writeValueAsString(mask(changes));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Không thể ghi nhật ký quản trị.", exception);
        }
        logs.save(AdminAuditLog.builder().actor(users.getReferenceById(actorId))
                .action(action).targetType(targetType).targetId(targetId).reason(reason)
                .result(result).changesJson(json).build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(Long actorId, String action, String targetType, String targetId,
                               String reason, Map<String, Object> changes) {
        record(actorId, action, targetType, targetId, reason, "REJECTED", changes);
    }

    static Object mask(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                masked.put(name, SENSITIVE.matcher(name).find() ? "***" : mask(item));
            });
            return masked;
        }
        if (value instanceof List<?> list) return list.stream().map(AdminAuditService::mask).toList();
        return value;
    }
}
