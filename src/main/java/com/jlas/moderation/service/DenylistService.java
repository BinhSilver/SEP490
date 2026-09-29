package com.jlas.moderation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.entity.ModerationPolicy;
import com.jlas.moderation.repository.ModerationPolicyRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Denylist stored in moderation_policy (policy_type = TERM), cached in memory.
 * The cache is rebuilt after each committed change, so edits apply immediately.
 */
@Service
@RequiredArgsConstructor
public class DenylistService {
    static final String TERM = "TERM";
    static final String LANGUAGE = "ALL";
    public static final String BLOCK = "BLOCK";
    public static final String REVIEW = "REVIEW";

    private final ModerationPolicyRepository policies;
    private final UserRepository users;
    private final ModerationAuditService audit;
    private final ObjectMapper objectMapper;

    private volatile Snapshot snapshot;

    /** Active terms split by how they are matched; version changes whenever the set changes. */
    public record Snapshot(List<String> block, List<String> accentSensitiveBlock, List<String> review,
                           String version) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of(), List.of(), "0");
    }

    public record TermView(Long id, String term, String action, boolean accentSensitive, boolean active,
                           Long updatedBy, LocalDateTime updatedAt) {
    }

    public Snapshot current() {
        Snapshot value = snapshot;
        if (value == null) {
            value = load();
            snapshot = value;
        }
        return value;
    }

    @Transactional(readOnly = true)
    public List<TermView> list() {
        return policies.findByPolicyTypeOrderByPolicyKeyAsc(TERM).stream().map(this::view).toList();
    }

    @Transactional
    public TermView create(String term, String action, boolean accentSensitive, String reason, Long actorId) {
        String key = requireTerm(term);
        String normalizedAction = requireAction(action);
        String why = ModerationAuditService.requireReason(reason);
        if (policies.findByPolicyTypeAndPolicyKeyAndLanguage(TERM, key, LANGUAGE).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, "Từ khoá \"" + key + "\" đã có trong danh sách");
        }
        ModerationPolicy policy = policies.save(ModerationPolicy.builder()
                .policyType(TERM).policyKey(key).language(LANGUAGE).action(normalizedAction)
                .configJson(config(accentSensitive)).isActive(true).updatedBy(actor(actorId)).build());
        audit.record(actorId, new ModerationAuditService.Entry("DENYLIST_CREATE", "DENYLIST_TERM",
                policy.getId(), null, normalizedAction, why + " | " + key, null));
        refreshAfterCommit();
        return view(policy);
    }

    @Transactional
    public TermView update(Long id, String action, boolean accentSensitive, boolean active, String reason,
                           Long actorId) {
        ModerationPolicy policy = require(id);
        String normalizedAction = requireAction(action);
        String why = ModerationAuditService.requireReason(reason);
        String before = state(policy);
        policy.setAction(normalizedAction);
        policy.setConfigJson(config(accentSensitive));
        policy.setIsActive(active);
        policy.setUpdatedBy(actor(actorId));
        audit.record(actorId, new ModerationAuditService.Entry(active ? "DENYLIST_UPDATE" : "DENYLIST_DISABLE",
                "DENYLIST_TERM", policy.getId(), before, state(policy), why + " | " + policy.getPolicyKey(), null));
        refreshAfterCommit();
        return view(policy);
    }

    private Snapshot load() {
        List<ModerationPolicy> active = policies.findByPolicyTypeAndIsActiveTrue(TERM).stream()
                .sorted(Comparator.comparing(ModerationPolicy::getPolicyKey)).toList();
        if (active.isEmpty()) return Snapshot.EMPTY;
        List<String> block = new ArrayList<>();
        List<String> accent = new ArrayList<>();
        List<String> review = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for (ModerationPolicy policy : active) {
            boolean accentSensitive = accentSensitive(policy.getConfigJson());
            if (REVIEW.equals(policy.getAction())) review.add(policy.getPolicyKey());
            else if (accentSensitive) accent.add(policy.getPolicyKey());
            else block.add(policy.getPolicyKey());
            signature.append(policy.getAction()).append(accentSensitive ? '!' : ':')
                    .append(policy.getPolicyKey()).append('\n');
        }
        return new Snapshot(List.copyOf(block), List.copyOf(accent), List.copyOf(review),
                Integer.toHexString(signature.toString().hashCode()));
    }

    private void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            snapshot = null;
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                snapshot = null;
            }
        });
    }

    private TermView view(ModerationPolicy policy) {
        return new TermView(policy.getId(), policy.getPolicyKey(), policy.getAction(),
                accentSensitive(policy.getConfigJson()), Boolean.TRUE.equals(policy.getIsActive()),
                policy.getUpdatedBy() == null ? null : policy.getUpdatedBy().getId(), policy.getUpdatedAt());
    }

    private static String state(ModerationPolicy policy) {
        return (Boolean.TRUE.equals(policy.getIsActive()) ? "" : "OFF:") + policy.getAction();
    }

    private boolean accentSensitive(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("accentSensitive").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    private static String config(boolean accentSensitive) {
        return accentSensitive ? "{\"accentSensitive\": true}" : null;
    }

    private ModerationPolicy require(Long id) {
        return policies.findById(id).filter(p -> TERM.equals(p.getPolicyType()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ khoá"));
    }

    private User actor(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
    }

    private static String requireTerm(String term) {
        String value = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Vui lòng nhập từ khoá");
        if (value.length() > 255) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Từ khoá tối đa 255 ký tự");
        return value;
    }

    private static String requireAction(String action) {
        String value = action == null ? "" : action.trim().toUpperCase(Locale.ROOT);
        if (!BLOCK.equals(value) && !REVIEW.equals(value)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Hành động chỉ là Chặn hoặc Cần xem lại");
        }
        return value;
    }
}
