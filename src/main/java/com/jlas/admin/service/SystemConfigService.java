package com.jlas.admin.service;

import com.jlas.admin.entity.SystemConfig;
import com.jlas.admin.enums.SystemConfigKey;
import com.jlas.admin.repository.SystemConfigRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * [JLAS2-51] Runtime settings ({@link SystemConfigKey}) with an in-memory cache, plus a read-only view of which
 * integration secrets are configured.
 *
 * <p>Outcome of an update or reset:
 * <table>
 *   <tr><th>Case</th><th>Status</th><th>Audit</th></tr>
 *   <tr><td>Reason missing, shorter than 5 or longer than 500</td><td>400</td><td>none</td></tr>
 *   <tr><td>Version missing</td><td>400</td><td>none</td></tr>
 *   <tr><td>Key not in {@link SystemConfigKey}</td><td>404</td><td>none</td></tr>
 *   <tr><td>Value unchanged (reset: already the default)</td><td>400</td><td>none</td></tr>
 *   <tr><td>Wrong type, out of range, not an allowed value</td><td>400</td><td>REJECTED</td></tr>
 *   <tr><td>Version differs from the stored one</td><td>409</td><td>REJECTED</td></tr>
 *   <tr><td>Saved</td><td>200</td><td>SUCCESS {before, after}</td></tr>
 * </table>
 * REJECTED rows are written outside the write transaction (after its rollback): the audit chain head is
 * locked by the SUCCESS row of that transaction, see {@link AdminAuditService}.
 *
 * <p>The version clients see is 0 for a key that was never set and the row's JPA version + 1 otherwise; rows are
 * never deleted (a reset stores null), so the number only grows.
 */
@Service
@RequiredArgsConstructor
public class SystemConfigService {
    static final String TARGET = "SYSTEM_CONFIG";
    static final String MASK = "***";
    private static final String STALE = "Cấu hình vừa được người khác thay đổi. Vui lòng tải lại.";

    /** Integration secrets, by the property name the code actually reads. Values are never returned. */
    private static final List<Integration> INTEGRATIONS = List.of(
            new Integration("payos.client-id", "PayOS · Client ID"),
            new Integration("payos.api-key", "PayOS · API key"),
            new Integration("payos.checksum-key", "PayOS · Checksum key"),
            new Integration("gemini-api-key", "Gemini · API key"),
            new Integration("openrouter.api.key", "OpenRouter · API key"),
            new Integration("deepgram.api.key", "Deepgram · API key"),
            new Integration("azure.speech.pronunciation.key", "Azure Speech · chấm phát âm"),
            new Integration("azure.speech.tts.key", "Azure Speech · đọc văn bản"),
            new Integration("fish.audio.api.key", "Fish Audio · API key"),
            new Integration("game.voice.api-key", "Voice chat game · API key"),
            new Integration("game.voice.api-secret", "Voice chat game · API secret"),
            new Integration("jlas.auth.jwt-secret", "JWT · khóa ký"),
            new Integration("spring.mail.password", "Email SMTP · mật khẩu"));

    private final SystemConfigRepository configs;
    private final UserRepository users;
    private final AdminAuditService audit;
    private final PlatformTransactionManager transactionManager;
    private final Environment environment;
    /** Override per key (empty = default). Removed after commit, never before, so a rollback leaves it valid. */
    private final ConcurrentHashMap<SystemConfigKey, Optional<String>> cache = new ConcurrentHashMap<>();

    private record Integration(String property, String label) {
    }

    public record ConfigView(String key, String group, String label, String description, String type, String value,
                             String defaultValue, Integer min, Integer max, List<String> allowedValues, boolean secret,
                             boolean overridden, long version, String updatedBy, LocalDateTime updatedAt) {
    }

    public record IntegrationView(String key, String label, boolean configured, String value) {
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    public String value(SystemConfigKey key) {
        return cache.computeIfAbsent(key, this::loadOverride).orElse(key.defaultValue());
    }

    public int intValue(SystemConfigKey key) {
        return Integer.parseInt(value(key));
    }

    /**
     * Read in a fresh transaction: a caller's older snapshot must not put a value changed since then into the cache.
     * A stored value that no longer passes validation (the range changed) falls back to the default.
     */
    private Optional<String> loadOverride(SystemConfigKey key) {
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setReadOnly(true);
        Optional<String> stored = read.execute(status -> configs.findById(key.key()).map(SystemConfig::getConfigValue));
        try {
            return stored.map(key::normalize);
        } catch (IllegalArgumentException outdated) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<ConfigView> list() {
        Map<String, SystemConfig> rows = configs.findAllBy().stream()
                .collect(Collectors.toMap(SystemConfig::getConfigKey, Function.identity()));
        return Arrays.stream(SystemConfigKey.values()).map(key -> view(key, rows.get(key.key()))).toList();
    }

    public List<IntegrationView> integrations() {
        return INTEGRATIONS.stream().map(item -> {
            String value = environment.getProperty(item.property());
            // Gemini also reads GEMINI_API_KEY when gemini-api-key is not set (see GeminiClient).
            if (!StringUtils.hasText(value) && item.property().equals("gemini-api-key")) {
                value = environment.getProperty("GEMINI_API_KEY");
            }
            boolean configured = StringUtils.hasText(value);
            return new IntegrationView(item.property(), item.label(), configured, configured ? "********" : null);
        }).toList();
    }

    // ── Writing ──────────────────────────────────────────────────────────────

    public ConfigView update(Long actorId, String rawKey, Object rawValue, Long version, String reason) {
        String why = validateReason(reason);
        requireVersion(version);
        SystemConfigKey key = find(rawKey);
        String value;
        try {
            value = key.normalize(rawValue);
        } catch (IllegalArgumentException invalid) {
            audit.recordRejected(actorId, "CONFIG_UPDATE", TARGET, key.key(), why,
                    rejection(key, invalid.getMessage(), rawValue == null ? null : rawValue.toString()));
            throw new ApiException(ErrorCode.BAD_REQUEST, invalid.getMessage());
        }
        return write(actorId, key, value, version, why, "CONFIG_UPDATE");
    }

    public ConfigView reset(Long actorId, String rawKey, Long version, String reason) {
        String why = validateReason(reason);
        requireVersion(version);
        return write(actorId, find(rawKey), null, version, why, "CONFIG_RESET");
    }

    /** {@code value} null = back to the default. */
    private ConfigView write(Long actorId, SystemConfigKey key, String value, long version, String reason,
                             String action) {
        try {
            return new TransactionTemplate(transactionManager)
                    .execute(status -> apply(actorId, key, value, version, reason, action));
        } catch (StaleVersion | ObjectOptimisticLockingFailureException | DataIntegrityViolationException conflict) {
            // A concurrent save shows up as a stale version here, or at flush/commit as an optimistic lock failure
            // (update) or a duplicate key (two first overrides). Logged after the rollback released the chain head.
            audit.recordRejected(actorId, action, TARGET, key.key(), reason,
                    rejection(key, STALE, value == null ? key.defaultValue() : value));
            throw new ApiException(ErrorCode.CONFLICT, STALE);
        }
    }

    private ConfigView apply(Long actorId, SystemConfigKey key, String value, long version, String reason,
                             String action) {
        SystemConfig row = configs.findById(key.key()).orElse(null);
        if (clientVersion(row) != version) throw new StaleVersion();
        String stored = row == null ? null : row.getConfigValue();
        String before = stored == null ? key.defaultValue() : stored;
        String after = value == null ? key.defaultValue() : value;
        boolean unchanged = value == null ? stored == null : before.equals(after);
        if (unchanged) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không có thay đổi nào để lưu.");
        }
        if (row == null) row = new SystemConfig(key.key());
        row.setConfigValue(value);
        row.setUpdatedBy(users.getReferenceById(actorId));
        row.setUpdatedAt(LocalDateTime.now());
        // Flush first: a version conflict surfaces here, before the audit row takes the chain head lock.
        row = configs.saveAndFlush(row);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("before", shown(key, before));
        changes.put("after", shown(key, after));
        audit.record(actorId, action, TARGET, key.key(), reason, "SUCCESS", changes);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.remove(key);
            }
        });
        return view(key, row);
    }

    private ConfigView view(SystemConfigKey key, SystemConfig row) {
        String stored = row == null ? null : row.getConfigValue();
        return new ConfigView(key.key(), key.group(), key.label(), key.description(), key.type().name(),
                shown(key, stored == null ? key.defaultValue() : stored), shown(key, key.defaultValue()),
                key.min(), key.max(), key.allowedValues(), key.secret(), stored != null, clientVersion(row),
                row == null || row.getUpdatedBy() == null ? null : row.getUpdatedBy().getEmail(),
                row == null ? null : row.getUpdatedAt());
    }

    private static long clientVersion(SystemConfig row) {
        return row == null ? 0 : row.getVersion() + 1;
    }

    private static String shown(SystemConfigKey key, String value) {
        return key.secret() ? MASK : value;
    }

    private static Map<String, Object> rejection(SystemConfigKey key, String message, String attempted) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("rejection", message);
        changes.put("attempted", attempted == null ? null : shown(key, attempted));
        return changes;
    }

    private static SystemConfigKey find(String rawKey) {
        return SystemConfigKey.find(rawKey)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy cấu hình."));
    }

    private static void requireVersion(Long version) {
        if (version == null || version < 0) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thiếu phiên bản cấu hình, hãy tải lại trang.");
        }
    }

    private static String validateReason(String reason) {
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Lý do phải có từ 5 đến 500 ký tự.");
        }
        return reason.trim();
    }

    /** The version the client sent is not the stored one. */
    private static final class StaleVersion extends RuntimeException {
        StaleVersion() {
            super(null, null, false, false);
        }
    }
}
