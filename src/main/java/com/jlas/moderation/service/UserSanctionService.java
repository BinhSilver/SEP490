package com.jlas.moderation.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.moderation.entity.UserSanction;
import com.jlas.moderation.repository.UserSanctionRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserSanctionService implements UserActionPolicyPort {
    private static final List<Integer> ALLOWED_MUTE_DAYS = List.of(3, 7, 30);

    private final UserSanctionRepository sanctions;
    private final UserRepository users;

    public enum SanctionType { MUTE, BAN }

    public record SanctionView(Long id, Long userId, String type, String reason,
                               LocalDateTime startsAt, LocalDateTime endsAt,
                               LocalDateTime revokedAt) {
    }

    @Transactional
    public SanctionView apply(Long userId, Long actorId, SanctionType type,
                              Integer durationDays, String reason) {
        if (userId.equals(actorId)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không thể tự áp dụng chế tài");
        }
        int days = duration(type, durationDays);
        User user = requiredUser(userId);
        User actor = requiredUser(actorId);
        LocalDateTime now = LocalDateTime.now();

        sanctions.findActiveByUserIdAndType(userId, type.name(), now)
                .ifPresent(existing -> existing.setRevokedAt(now));

        UserSanction sanction = UserSanction.builder()
                .user(user)
                .createdBy(actor)
                .sanctionType(type.name())
                .reason(trimToNull(reason))
                .endsAt(type == SanctionType.BAN && durationDays == null ? null : now.plusDays(days))
                .build();
        return view(sanctions.save(sanction));
    }

    @Transactional
    public SanctionView revoke(Long sanctionId, Long actorId) {
        requiredUser(actorId);
        UserSanction sanction = sanctions.findById(sanctionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
        if (sanction.getRevokedAt() == null) {
            sanction.setRevokedAt(LocalDateTime.now());
        }
        return view(sanctions.save(sanction));
    }

    @Transactional(readOnly = true)
    public List<SanctionView> activeFor(Long userId) {
        return sanctions.findActiveByUserId(userId, LocalDateTime.now()).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    @Override
    public void assertCanAct(Long userId) {
        sanctions.findLatestActiveByUserId(userId, LocalDateTime.now()).ifPresent(sanction -> {
            String until = sanction.getEndsAt() == null ? "vô thời hạn" : "đến " + sanction.getEndsAt();
            throw new ApiException(ErrorCode.FORBIDDEN,
                    "Tài khoản đang bị " + sanction.getSanctionType().toLowerCase() + " " + until);
        });
    }

    @Transactional(readOnly = true)
    public UserSanction activeSanction(Long sanctionId, Long userId) {
        UserSanction sanction = sanctions.findById(sanctionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy chế tài"));
        boolean active = sanction.getRevokedAt() == null
                && (sanction.getEndsAt() == null || sanction.getEndsAt().isAfter(LocalDateTime.now()));
        if (!sanction.getUser().getId().equals(userId) || !active) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chế tài không còn hiệu lực hoặc không thuộc tài khoản");
        }
        return sanction;
    }

    private int duration(SanctionType type, Integer days) {
        if (type == SanctionType.BAN && days == null) {
            return 0;
        }
        if (days == null || !ALLOWED_MUTE_DAYS.contains(days)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Thời hạn chỉ hỗ trợ 3, 7 hoặc 30 ngày");
        }
        return days;
    }

    private User requiredUser(Long id) {
        return users.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
    }

    private SanctionView view(UserSanction value) {
        return new SanctionView(value.getId(), value.getUser().getId(), value.getSanctionType(),
                value.getReason(), value.getStartsAt(), value.getEndsAt(), value.getRevokedAt());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
