package com.jlas.moderation.service;

import com.jlas.common.exception.ApiException;
import com.jlas.moderation.entity.UserBulkViolation;
import com.jlas.moderation.repository.UserBulkViolationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkContentGuardServiceTest {

    private static final long MINUTE = 60_000L;
    private static final long T0 = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, 86_400_000L);

    private final UserBulkViolationRepository violations = mock(UserBulkViolationRepository.class);
    private final UserSanctionService sanctions = mock(UserSanctionService.class);
    private final ModerationNotifier notifier = mock(ModerationNotifier.class);
    private final ModerationAuditService audit = mock(ModerationAuditService.class);
    private final BulkContentGuardService guard = new BulkContentGuardService(violations, sanctions, notifier, audit);

    @BeforeEach
    void settings() {
        ReflectionTestUtils.setField(guard, "enabled", true);
        ReflectionTestUtils.setField(guard, "downloadPerMinute", 5);
        ReflectionTestUtils.setField(guard, "downloadPerHour", 1000);
        ReflectionTestUtils.setField(guard, "uploadPer5Minutes", 3);
        ReflectionTestUtils.setField(guard, "uploadPerDay", 40);
        ReflectionTestUtils.setField(guard, "suspendHours", 24);
        ReflectionTestUtils.setField(guard, "strikeDays", 30);
        ReflectionTestUtils.setField(guard, "violationCooldownMinutes", 60);
        when(violations.save(any())).thenAnswer(invocation -> {
            UserBulkViolation v = invocation.getArgument(0);
            if (v.getId() == null) v.setId(7L);
            return v;
        });
    }

    @Test
    void classifiesContentReadsAndContentCreation() {
        assertThat(guard.classify("GET", "/api/dictionary/search")).isEqualTo(BulkContentGuardService.Kind.DOWNLOAD);
        assertThat(guard.classify("GET", "/audio/n5/lesson1.mp3")).isEqualTo(BulkContentGuardService.Kind.DOWNLOAD);
        assertThat(guard.classify("POST", "/api/community/posts")).isEqualTo(BulkContentGuardService.Kind.UPLOAD);
        assertThat(guard.classify("POST", "/api/community/contents/POST/12/comments"))
                .isEqualTo(BulkContentGuardService.Kind.UPLOAD);
        assertThat(guard.classify("POST", "/api/flashcards/9/clone")).isEqualTo(BulkContentGuardService.Kind.UPLOAD);
        assertThat(guard.classify("GET", "/api/community/posts")).isNull();
        assertThat(guard.classify("POST", "/api/dictionary/search")).isNull();
        assertThat(guard.classify("GET", "/api/notifications")).isNull();
    }

    @Test
    void disabledGuardCountsNothing() {
        ReflectionTestUtils.setField(guard, "enabled", false);
        assertThat(guard.classify("GET", "/api/dictionary/search")).isNull();
    }

    @Test
    void blocksAboveTheLimitAndRecordsOneViolationPerWindow() {
        for (int i = 0; i < 5; i++) {
            assertThat(guard.hit("u:1", BulkContentGuardService.Kind.DOWNLOAD, T0 + i).blocked()).isFalse();
        }
        BulkContentGuardService.Hit sixth = guard.hit("u:1", BulkContentGuardService.Kind.DOWNLOAD, T0 + 10);
        assertThat(sixth.blocked()).isTrue();
        assertThat(sixth.newViolation()).isTrue();
        assertThat(sixth.count()).isEqualTo(6);
        assertThat(sixth.limit().windowSeconds()).isEqualTo(60);
        assertThat(sixth.retryAfterSeconds()).isBetween(1L, 60L);

        BulkContentGuardService.Hit seventh = guard.hit("u:1", BulkContentGuardService.Kind.DOWNLOAD, T0 + 20);
        assertThat(seventh.blocked()).isTrue();
        assertThat(seventh.newViolation()).isFalse();

        // Cửa sổ mới: lại được tải.
        assertThat(guard.hit("u:1", BulkContentGuardService.Kind.DOWNLOAD, T0 + MINUTE).blocked()).isFalse();
        // Người khác không bị ảnh hưởng.
        assertThat(guard.hit("u:2", BulkContentGuardService.Kind.DOWNLOAD, T0 + 30).blocked()).isFalse();
    }

    @Test
    void secondViolationWaitsForTheCooldown() {
        exceed("u:1", T0);
        // Phút sau vượt tiếp: vẫn 429 nhưng chưa ghi vi phạm mới (chưa qua 60 phút).
        BulkContentGuardService.Hit again = exceed("u:1", T0 + MINUTE);
        assertThat(again.blocked()).isTrue();
        assertThat(again.newViolation()).isFalse();
        // Sau 60 phút: ghi được vi phạm tiếp theo.
        assertThat(exceed("u:1", T0 + 61 * MINUTE).newViolation()).isTrue();
    }

    @Test
    void firstViolationOnlyWarns() {
        when(violations.countActiveSince(eq(1L), any())).thenReturn(0L);
        BulkContentGuardService.Action action = guard.recordViolation(1L, exceed("u:1", T0), "/api/dictionary/search");
        assertThat(action).isEqualTo(BulkContentGuardService.Action.WARN);
        verify(notifier).bulkWarning(1L, 7L, false, 24);
        verifyNoInteractions(sanctions);
    }

    @Test
    void secondViolationSuspendsForConfiguredHours() {
        when(violations.countActiveSince(eq(1L), any())).thenReturn(1L);
        when(sanctions.applyAutomaticBan(eq(1L), any(), anyString())).thenReturn(view(55L));
        LocalDateTime before = LocalDateTime.now();
        BulkContentGuardService.Action action = guard.recordViolation(1L, exceed("u:1", T0), "/api/learning/lessons/3");
        assertThat(action).isEqualTo(BulkContentGuardService.Action.SUSPEND);
        verify(sanctions).applyAutomaticBan(eq(1L),
                argThat(endsAt -> endsAt != null && !endsAt.isBefore(before.plusHours(24))
                        && endsAt.isBefore(before.plusHours(24).plusMinutes(1))),
                contains("Tạm ngưng 24 giờ"));
        verify(violations, atLeastOnce()).save(argThat(v -> Long.valueOf(55L).equals(v.getSanctionId())
                && "SUSPEND".equals(v.getActionTaken())));
    }

    @Test
    void thirdViolationLocksForever() {
        when(violations.countActiveSince(eq(1L), any())).thenReturn(2L);
        when(sanctions.applyAutomaticBan(eq(1L), any(), anyString())).thenReturn(view(56L));
        BulkContentGuardService.Hit hit = guard.hit("u:1", BulkContentGuardService.Kind.UPLOAD, T0);
        for (int i = 1; i <= 3; i++) hit = guard.hit("u:1", BulkContentGuardService.Kind.UPLOAD, T0 + i);
        assertThat(hit.newViolation()).isTrue();
        assertThat(guard.recordViolation(1L, hit, "/api/community/posts")).isEqualTo(BulkContentGuardService.Action.LOCK);
        verify(sanctions).applyAutomaticBan(eq(1L), isNull(), contains("Khoá tài khoản"));
    }

    @Test
    void adminClearsAViolationOnce() {
        UserBulkViolation stored = UserBulkViolation.builder().id(7L).userId(1L).kind("DOWNLOAD").requestCount(6)
                .requestLimit(5).windowSeconds(60).actionTaken("WARN").build();
        when(violations.findById(7L)).thenReturn(Optional.of(stored));
        BulkContentGuardService.ViolationView cleared = guard.clear(7L, 99L, "Học viên ôn thi, không phải cào dữ liệu");
        assertThat(cleared.clearedAt()).isNotNull();
        verify(audit).record(eq(99L), argThat(e -> "BULK_VIOLATION_CLEAR".equals(e.action())
                && "USER".equals(e.targetType()) && Long.valueOf(1L).equals(e.targetId())));
        assertThatThrownBy(() -> guard.clear(7L, 99L, "lần nữa")).isInstanceOf(ApiException.class);
    }

    private BulkContentGuardService.Hit exceed(String subject, long start) {
        BulkContentGuardService.Hit hit = null;
        for (int i = 0; i < 6; i++) hit = guard.hit(subject, BulkContentGuardService.Kind.DOWNLOAD, start + i);
        return hit;
    }

    private static UserSanctionService.SanctionView view(Long id) {
        return new UserSanctionService.SanctionView(id, 1L, "BAN", "auto", LocalDateTime.now(), null, null, true);
    }
}
