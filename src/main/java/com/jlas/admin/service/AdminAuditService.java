package com.jlas.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.admin.entity.AdminAuditChain;
import com.jlas.admin.entity.AdminAuditLog;
import com.jlas.admin.dto.AdminAuditLogView;
import com.jlas.admin.repository.AdminAuditChainRepository;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.util.ArrayList;

/**
 * Append-only System Admin log. [JLAS2-51] Every row stores the actor's role and request IP and is chained
 * to the previous hashed row by SHA-256, so editing or deleting a row (including the last one, which is
 * compared with {@link AdminAuditChain}) is detected by {@link #verifyIntegrity()}.
 *
 * <p><b>Ordering rule:</b> {@link #record} locks the chain head until the caller's transaction ends, and
 * {@link #recordRejected} runs in its own transaction that needs the same lock. Within one transaction,
 * write every REJECTED row before any SUCCESS row (or after the rollback, as {@code AdminCreditService}
 * does); a REJECTED write after a SUCCESS one would wait on the outer transaction forever.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {
    private static final Pattern SENSITIVE = Pattern.compile(
            "password|secret|token|otp|api[_-]?key|credential|hash|checksum|private|jwt", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Fixed width, so the hashed text does not depend on how many trailing zeros the time has. */
    private static final DateTimeFormatter HASH_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    static final int MAX_PAGE_SIZE = 100;
    static final Set<String> TARGET_TYPES = Set.of("USER", "PREMIUM_PLAN", "COUPON", "CREDIT_PACKAGE", "SYSTEM_CONFIG");
    static final Set<String> ACTOR_ROLES = Set.of("LEARNER", "MODERATOR", "ADMIN", "CONTENT_CREATOR");
    private final AdminAuditLogRepository logs;
    private final AdminAuditChainRepository chain;
    private final UserRepository users;

    /** Result of {@link #verifyIntegrity()}; {@code firstBrokenId} is the first row that fails, or the deleted last row. */
    public record Integrity(long checked, long legacyUnhashed, boolean valid, Long firstBrokenId, String reason) {
    }

    @Transactional(readOnly = true)
    public Page<AdminAuditLogView> list(Long actorId, String q, String actorRole, String action, String targetType,
                                       String targetId, String result, LocalDateTime from, LocalDateTime to,
                                       int page, int size) {
        if (result != null && !List.of("SUCCESS", "REJECTED").contains(result)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Kết quả phải là SUCCESS hoặc REJECTED.");
        }
        if (actorRole != null && !ACTOR_ROLES.contains(actorRole)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Vai trò không hợp lệ.");
        }
        if (targetType != null && !TARGET_TYPES.contains(targetType)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Loại đối tượng không hợp lệ.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Khoảng thời gian không hợp lệ.");
        }
        // Other admin lists cap the size silently; the audit log rejects it so an export never looks complete when it is not.
        if (size > MAX_PAGE_SIZE) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Mỗi trang tối đa " + MAX_PAGE_SIZE + " bản ghi.");
        }
        return logs.findAll((root, query, cb) -> {
            var filters = new ArrayList<Predicate>();
            if (actorId != null) filters.add(cb.equal(root.get("actor").get("id"), actorId));
            if (q != null && !q.isBlank()) {
                String term = "%" + q.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                filters.add(cb.or(cb.like(cb.lower(root.get("actor").get("email")), term, '\\'),
                        cb.like(cb.lower(root.get("reason")), term, '\\')));
            }
            if (actorRole != null) filters.add(cb.equal(root.get("actorRole"), actorRole));
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
        String actorRole = users.findById(actorId).map(user -> user.getRole().getName()).orElse(null);
        String ip = requestIp();
        // Truncated like the DATETIME(6) column, so the time read back hashes the same.
        LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        AdminAuditChain head = chain.lockHead().orElseGet(() -> chain.saveAndFlush(AdminAuditChain.head()));
        String rowHash = hash(head.getLastHash(), actorId, actorRole, action, targetType, targetId, reason, result,
                json, createdAt, ip);
        AdminAuditLog log = logs.save(AdminAuditLog.builder().actor(users.getReferenceById(actorId))
                .action(action).targetType(targetType).targetId(targetId).reason(reason)
                .result(result).changesJson(json).createdAt(createdAt).actorRole(actorRole).ipAddress(ip)
                .prevHash(head.getLastHash()).rowHash(rowHash).build());
        head.setLastLogId(log.getId());
        head.setLastHash(rowHash);
    }

    /** Runs in its own transaction so the rejection stays logged after the caller rolls back. See the ordering rule above. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(Long actorId, String action, String targetType, String targetId,
                               String reason, Map<String, Object> changes) {
        record(actorId, action, targetType, targetId, reason, "REJECTED", changes);
    }

    /**
     * Walks the log in id order. Rows without a hash before the first hashed row predate the chain and count as
     * legacy; after it, a missing hash is tampering. Detects an edited row (hash differs), a deleted middle row
     * (the next row no longer links to its predecessor) and a deleted last row (the chain head points past the end).
     */
    @Transactional(readOnly = true)
    public Integrity verifyIntegrity() {
        // One snapshot for the head and the rows: a write committing mid-check cannot look like a deleted tail.
        AdminAuditChain head = chain.findById(AdminAuditChain.HEAD_ID).orElse(null);
        long checked = 0;
        long legacy = 0;
        boolean hashedSeen = false;
        String previous = null;
        Long lastId = null;
        long after = 0;
        List<AdminAuditLog> batch;
        while (!(batch = logs.findTop500ByIdGreaterThanOrderByIdAsc(after)).isEmpty()) {
            for (AdminAuditLog row : batch) {
                checked++;
                after = row.getId();
                if (row.getRowHash() == null) {
                    if (!hashedSeen) {
                        legacy++;
                        continue;
                    }
                    return broken(checked, legacy, row.getId(), "Bản ghi không có mã băm nằm sau chuỗi đã băm.");
                }
                hashedSeen = true;
                if (!Objects.equals(text(row.getPrevHash()), text(previous))) {
                    return broken(checked, legacy, row.getId(),
                            "Bản ghi không nối với bản ghi liền trước (có bản ghi bị xóa hoặc sửa).");
                }
                String expected = hash(row.getPrevHash(), row.getActor().getId(), row.getActorRole(), row.getAction(),
                        row.getTargetType(), row.getTargetId(), row.getReason(), row.getResult(), row.getChangesJson(),
                        row.getCreatedAt(), row.getIpAddress());
                if (!expected.equals(row.getRowHash())) {
                    return broken(checked, legacy, row.getId(), "Nội dung bản ghi đã bị sửa.");
                }
                previous = row.getRowHash();
                lastId = row.getId();
            }
        }
        Long headId = head == null ? null : head.getLastLogId();
        if (!Objects.equals(headId, lastId) || headId != null && !Objects.equals(head.getLastHash(), previous)) {
            return broken(checked, legacy, headId != null ? headId : lastId,
                    "Bản ghi cuối của chuỗi đã bị xóa hoặc thay đổi.");
        }
        return new Integrity(checked, legacy, true, null, null);
    }

    private static Integrity broken(long checked, long legacy, Long id, String reason) {
        return new Integrity(checked, legacy, false, id, reason);
    }

    /**
     * SHA-256 over the fields in a fixed order. Each value is written as {@code length:value|} with null as an
     * empty string, so a "|" inside a reason cannot shift text from one field into the next.
     */
    static String hash(String prevHash, Long actorId, String actorRole, String action, String targetType,
                       String targetId, String reason, String result, String changesJson,
                       LocalDateTime createdAt, String ip) {
        StringBuilder text = new StringBuilder();
        for (String value : new String[]{prevHash, actorId == null ? null : actorId.toString(), actorRole, action,
                targetType, targetId, reason, result, changesJson,
                createdAt == null ? null : HASH_TIME.format(createdAt), ip}) {
            String field = text(value);
            text.append(field.length()).append(':').append(field).append('|');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every JDK.", exception);
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * The connecting address only. Forwarded headers are not trusted: forward-headers-strategy is none,
     * so any client could set X-Forwarded-For. Null outside a web request (jobs, tests without MockMvc).
     */
    private static String requestIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String ip = attributes.getRequest().getRemoteAddr();
        return ip == null ? null : ip.length() > 64 ? ip.substring(0, 64) : ip;
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
