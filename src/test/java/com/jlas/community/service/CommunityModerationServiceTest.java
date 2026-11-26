package com.jlas.community.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityPost;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.handler.CommunityContentTypeHandler;
import com.jlas.community.handler.CommunityPostHandler;
import com.jlas.community.handler.CommunitySource;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.repository.CommunityPostRepository;
import com.jlas.community.repository.CommunityReportRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.report.ReportDecision;
import com.jlas.moderation.service.ModerationAuditService;
import com.jlas.notification.service.UserNotificationPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [JLAS2-37] Gỡ trực tiếp bài viết / bình luận (MODERATOR/ADMIN), quyền xem bài đã gỡ, thông báo cộng đồng. */
class CommunityModerationServiceTest {
    private static final long AUTHOR = 7L;
    private static final long LEARNER = 9L;
    private static final long MODERATOR = 3L;
    private static final long POST_ID = 42L;
    private static final long ROW_ID = 5L;

    private CommunityAccessService access;
    private CommunityContentRepository contents;
    private CommunityCommentRepository comments;
    private CommunityReportedContentPort reportedContent;
    private ModerationAuditService audit;
    private CommunityNotifier notifier;
    private CommunityModerationService service;

    @BeforeEach
    void setUp() {
        access = mock(CommunityAccessService.class);
        contents = mock(CommunityContentRepository.class);
        comments = mock(CommunityCommentRepository.class);
        reportedContent = mock(CommunityReportedContentPort.class);
        audit = mock(ModerationAuditService.class);
        notifier = mock(CommunityNotifier.class);
        service = new CommunityModerationService(access, contents, comments, reportedContent, audit, notifier,
                mock(CommunityFeedService.class));
        // fresh(): đọc lại bản mới nhất sau khi khoá; mặc định coi như dòng không đổi.
        when(access.fresh(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(long userId, String role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = new UserPrincipal(userId, "u" + userId, "x", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private static CommunitySource postSource() {
        return new CommunitySource(CommunityContentType.POST, POST_ID, "Mẹo học kanji", "Mẹo học kanji mỗi ngày", AUTHOR,
                null, "/community/posts/" + POST_ID, null, LocalDateTime.now(), true, Map.of("edited", false), null);
    }

    private static CommunityContent row(String status) {
        return CommunityContent.builder().id(ROW_ID).contentType(CommunityContentType.POST).contentId(POST_ID)
                .status(status).ratingAvg(BigDecimal.ZERO).ratingCount(0).commentCount(0).reportCount(0).build();
    }

    private static void assertCode(ErrorCode code, Executable call) {
        assertEquals(code, assertThrows(ApiException.class, call).getErrorCode());
    }

    // ── Gỡ bài ──────────────────────────────────────────────────────────────
    @Test
    void moderatorRemovesPostClosesReportsAuditsAndNotifiesAuthor() {
        loginAs(MODERATOR, "MODERATOR");
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(new Target(postSource(), row("PUBLISHED"), "PUBLISHED"));
        when(contents.lockBySource(CommunityContentType.POST, POST_ID)).thenReturn(Optional.of(row("PUBLISHED")));

        service.removePost(POST_ID, MODERATOR, "  Quảng cáo không liên quan  ");

        ArgumentCaptor<ReportDecision> decision = ArgumentCaptor.forClass(ReportDecision.class);
        verify(reportedContent).resolve(eq(CommunityReportedContentPort.CONTENT), eq(ROW_ID), decision.capture());
        assertEquals("REMOVED", decision.getValue().newState());
        assertTrue(decision.getValue().valid());
        ArgumentCaptor<ModerationAuditService.Entry> entry = ArgumentCaptor.forClass(ModerationAuditService.Entry.class);
        verify(audit).record(eq(MODERATOR), entry.capture());
        assertEquals(CommunityModerationService.ACTION_REMOVE, entry.getValue().action());
        assertEquals("PUBLISHED", entry.getValue().fromState());
        assertEquals("REMOVED", entry.getValue().toState());
        assertEquals("Quảng cáo không liên quan", entry.getValue().reason());
        verify(notifier).removedByModerator(eq(AUTHOR), any(CommunitySource.class), eq(null), eq("Quảng cáo không liên quan"),
                eq(true));
    }

    @Test
    void removalChecksTheStatusReadAgainUnderTheLock() {
        // Dòng trong persistence context còn PUBLISHED nhưng kiểm duyệt viên khác vừa gỡ: bản đọc lại có khoá là REMOVED.
        loginAs(MODERATOR, "MODERATOR");
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(new Target(postSource(), row("PUBLISHED"), "PUBLISHED"));
        when(contents.lockBySource(CommunityContentType.POST, POST_ID)).thenReturn(Optional.of(row("PUBLISHED")));
        when(access.fresh(any())).thenAnswer(inv -> {
            CommunityContent locked = inv.getArgument(0);
            locked.setStatus("REMOVED");
            return locked;
        });
        assertCode(ErrorCode.CONFLICT, () -> service.removePost(POST_ID, MODERATOR, "Spam"));
        verify(reportedContent, never()).resolve(anyString(), anyLong(), any());
        verify(audit, never()).record(anyLong(), any());
    }

    @Test
    void adminCanRemoveToo() {
        loginAs(MODERATOR, "ADMIN");
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(new Target(postSource(), row("TEMPORARILY_HIDDEN"), "TEMPORARILY_HIDDEN"));
        when(contents.lockBySource(CommunityContentType.POST, POST_ID)).thenReturn(Optional.of(row("TEMPORARILY_HIDDEN")));
        service.removePost(POST_ID, MODERATOR, "Spam");
        verify(reportedContent).resolve(eq(CommunityReportedContentPort.CONTENT), eq(ROW_ID), any());
    }

    @Test
    void contentCreatorCanRemoveAnyPost() {
        // Content admin (CONTENT_CREATOR) gỡ thẳng từ menu "…" mà không cần đủ báo cáo.
        loginAs(MODERATOR, "CONTENT_CREATOR");
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(new Target(postSource(), row("PUBLISHED"), "PUBLISHED"));
        when(contents.lockBySource(CommunityContentType.POST, POST_ID)).thenReturn(Optional.of(row("PUBLISHED")));
        service.removePost(POST_ID, MODERATOR, "Spam");
        verify(reportedContent).resolve(eq(CommunityReportedContentPort.CONTENT), eq(ROW_ID), any());
        verify(notifier).removedByModerator(eq(AUTHOR), any(CommunitySource.class), eq(null), eq("Spam"), eq(true));
    }

    @Test
    void removeContentByRowIdUsesTheSameFlow() {
        loginAs(MODERATOR, "CONTENT_CREATOR");
        CommunityContent hidden = row("TEMPORARILY_HIDDEN");
        when(contents.lockById(ROW_ID)).thenReturn(Optional.of(hidden));
        when(access.load(hidden)).thenReturn(new Target(postSource(), hidden, "TEMPORARILY_HIDDEN"));
        service.removeContent(ROW_ID, MODERATOR, "Spam");
        verify(reportedContent).resolve(eq(CommunityReportedContentPort.CONTENT), eq(ROW_ID), any());
        ArgumentCaptor<ModerationAuditService.Entry> entry = ArgumentCaptor.forClass(ModerationAuditService.Entry.class);
        verify(audit).record(eq(MODERATOR), entry.capture());
        assertEquals("TEMPORARILY_HIDDEN", entry.getValue().fromState());
    }

    @Test
    void learnerCannotRemove() {
        loginAs(LEARNER, "LEARNER");
        assertCode(ErrorCode.FORBIDDEN, () -> service.removePost(POST_ID, LEARNER, "Spam"));
        assertCode(ErrorCode.FORBIDDEN, () -> service.removeComment(100L, LEARNER, "Spam"));
        assertCode(ErrorCode.FORBIDDEN, () -> service.removeContent(ROW_ID, LEARNER, "Spam"));
        SecurityContextHolder.clearContext();
        assertCode(ErrorCode.FORBIDDEN, () -> service.removePost(POST_ID, LEARNER, "Spam"));
        verify(reportedContent, never()).resolve(anyString(), anyLong(), any());
        verify(audit, never()).record(anyLong(), any());
    }

    @Test
    void reasonIsRequiredAndAtMost500Chars() {
        loginAs(MODERATOR, "MODERATOR");
        assertCode(ErrorCode.BAD_REQUEST, () -> service.removePost(POST_ID, MODERATOR, "   "));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.removePost(POST_ID, MODERATOR, null));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.removePost(POST_ID, MODERATOR, "x".repeat(501)));
        assertEquals("x".repeat(500), CommunityModerationService.reason("x".repeat(500)));
        verify(reportedContent, never()).resolve(anyString(), anyLong(), any());
    }

    @Test
    void removingTwiceIsConflict() {
        loginAs(MODERATOR, "MODERATOR");
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(new Target(postSource(), row("REMOVED"), "REMOVED"));
        when(contents.lockBySource(CommunityContentType.POST, POST_ID)).thenReturn(Optional.of(row("REMOVED")));
        assertCode(ErrorCode.CONFLICT, () -> service.removePost(POST_ID, MODERATOR, "Spam"));
        verify(reportedContent, never()).resolve(anyString(), anyLong(), any());
    }

    private CommunityComment stubComment(String status, String parentStatus, boolean commenterSeesParent) {
        CommunityComment comment = CommunityComment.builder().id(100L).communityContentId(ROW_ID).userId(LEARNER)
                .body("spam link").status(status).reportCount(0).build();
        when(comments.findById(100L)).thenReturn(Optional.of(comment));
        when(contents.lockById(ROW_ID)).thenReturn(Optional.of(row(parentStatus)));
        when(comments.lockById(100L)).thenReturn(Optional.of(comment));
        Target parent = new Target(postSource(), row(parentStatus), parentStatus);
        when(access.load(any(CommunityContent.class))).thenReturn(parent);
        when(access.canView(parent, LEARNER, false)).thenReturn(commenterSeesParent);
        return comment;
    }

    @Test
    void moderatorRemovesCommentAndNotifiesCommentAuthor() {
        loginAs(MODERATOR, "MODERATOR");
        stubComment("PUBLISHED", "PUBLISHED", true);

        service.removeComment(100L, MODERATOR, "Spam");

        verify(reportedContent).resolve(eq(CommunityReportedContentPort.COMMENT), eq(100L), any());
        ArgumentCaptor<ModerationAuditService.Entry> entry = ArgumentCaptor.forClass(ModerationAuditService.Entry.class);
        verify(audit).record(eq(MODERATOR), entry.capture());
        assertEquals(CommunityReportedContentPort.COMMENT, entry.getValue().targetType());
        verify(notifier).removedByModerator(eq(LEARNER), any(CommunitySource.class), eq(100L), eq("Spam"), eq(true));
    }

    @Test
    void removingACommentOnAHiddenPostDoesNotQuoteOrLinkThePost() {
        loginAs(MODERATOR, "MODERATOR");
        stubComment("PUBLISHED", "TEMPORARILY_HIDDEN", false);
        service.removeComment(100L, MODERATOR, "Spam");
        verify(notifier).removedByModerator(eq(LEARNER), any(CommunitySource.class), eq(100L), eq("Spam"), eq(false));
    }

    @Test
    void removingACommentAlreadyRemovedUnderTheLockIsConflict() {
        loginAs(MODERATOR, "MODERATOR");
        stubComment("REMOVED", "PUBLISHED", true);
        assertCode(ErrorCode.CONFLICT, () -> service.removeComment(100L, MODERATOR, "Spam"));
        verify(reportedContent, never()).resolve(anyString(), anyLong(), any());
    }

    // ── Hàng chờ báo cáo: không hồi sinh / gỡ lại nội dung đã bị gỡ; ghi chú vừa cột 500 ─────────────
    @Test
    void portRefusesToChangeARemovedTargetAndClipsTheNote() {
        CommunityReportRepository reports = mock(CommunityReportRepository.class);
        CommunityReportedContentPort port = new CommunityReportedContentPort(access, contents, comments, reports);
        when(contents.lockById(ROW_ID)).thenReturn(Optional.of(row("REMOVED")));
        assertCode(ErrorCode.CONFLICT, () -> port.resolve(CommunityReportedContentPort.CONTENT, ROW_ID,
                new ReportDecision(false, "PUBLISHED", MODERATOR, "Báo cáo sai", LocalDateTime.now())));
        verify(reports, never()).lockOpen(anyString(), anyLong());

        assertEquals(CommunityReportedContentPort.MAX_NOTE, CommunityReportedContentPort.note("x".repeat(1000)).length());
        assertEquals("ngắn", CommunityReportedContentPort.note("ngắn"));
    }

    // ── Bài đã gỡ: người học 404, tác giả + kiểm duyệt viên vẫn xem được ─────────────
    @Test
    void removedPostIsNotFoundForLearnersButVisibleToAuthorAndModerator() {
        CommunityPostRepository posts = mock(CommunityPostRepository.class);
        when(posts.findById(POST_ID)).thenReturn(Optional.of(CommunityPost.builder().id(POST_ID).authorId(AUTHOR)
                .body("Mẹo học kanji").createdAt(LocalDateTime.now()).build()));
        CommunityContentRepository rows = mock(CommunityContentRepository.class);
        when(rows.findByContentTypeAndContentId(CommunityContentType.POST, POST_ID)).thenReturn(Optional.of(row("REMOVED")));
        List<CommunityContentTypeHandler> handlers = List.of(new CommunityPostHandler(posts));
        CommunityAccessService realAccess = new CommunityAccessService(handlers, rows,
                mock(CommunityCommentRepository.class), mock(UserRepository.class));

        loginAs(LEARNER, "LEARNER");
        assertCode(ErrorCode.NOT_FOUND, () -> realAccess.requireViewable(CommunityContentType.POST, POST_ID, LEARNER));
        loginAs(AUTHOR, "LEARNER");
        assertEquals("REMOVED", realAccess.requireViewable(CommunityContentType.POST, POST_ID, AUTHOR).status());
        loginAs(MODERATOR, "MODERATOR");
        assertEquals("REMOVED", realAccess.requireViewable(CommunityContentType.POST, POST_ID, MODERATOR).status());
    }

    @Test
    void removedSharedContentStaysModeratorOnlyEvenForOwner() {
        CommunityContentTypeHandler flashcards = mock(CommunityContentTypeHandler.class);
        when(flashcards.supports(CommunityContentType.FLASHCARD_SET)).thenReturn(true);
        when(flashcards.contentType()).thenReturn(CommunityContentType.FLASHCARD_SET);
        when(flashcards.ownerSeesRemoved()).thenReturn(false);
        CommunitySource source = new CommunitySource(CommunityContentType.FLASHCARD_SET, 1L, "Bộ thẻ", null, AUTHOR,
                null, "/flashcard/study.html?setId=1", "N5", LocalDateTime.now(), true, Map.of(), null);
        CommunityAccessService realAccess = new CommunityAccessService(List.of(flashcards),
                mock(CommunityContentRepository.class), mock(CommunityCommentRepository.class), mock(UserRepository.class));
        Target removed = new Target(source, row("REMOVED"), "REMOVED");
        assertEquals(false, realAccess.canView(removed, AUTHOR, false));
        assertEquals(true, realAccess.canView(removed, AUTHOR, true));
    }

    // ── Thông báo ───────────────────────────────────────────────────────────
    @Test
    void commentOnPostUsesCommunityCommentTypeAndFlashcardKeepsItsType() {
        UserNotificationPort port = mock(UserNotificationPort.class);
        CommunityNotifier real = new CommunityNotifier(port);
        real.newComment(postSource(), 100L, LEARNER, "Người học");
        verify(port).notifyUser(eq(AUTHOR), eq(CommunityNotifier.TYPE_COMMENT), anyString(), anyString(),
                eq("/community/posts/" + POST_ID), eq("COMMUNITY_COMMENT_100"));

        CommunitySource set = new CommunitySource(CommunityContentType.FLASHCARD_SET, 1L, "Bộ thẻ N5", null, AUTHOR,
                null, "/flashcard/study.html?setId=1", "N5", LocalDateTime.now(), true, Map.of(), null);
        real.newComment(set, 101L, LEARNER, "Người học");
        verify(port).notifyUser(eq(AUTHOR), eq("FLASHCARD_COMMENT"), anyString(), anyString(),
                eq("/community/FLASHCARD_SET/1"), eq("COMMUNITY_COMMENT_101"));
        // Chủ bài tự bình luận: không báo.
        real.newComment(postSource(), 102L, AUTHOR, "Tác giả");
        verify(port, never()).notifyUser(anyLong(), anyString(), anyString(), anyString(), anyString(), eq("COMMUNITY_COMMENT_102"));
    }

    @Test
    void removalNotificationCarriesReasonAndLink() {
        UserNotificationPort port = mock(UserNotificationPort.class);
        CommunityNotifier real = new CommunityNotifier(port);
        real.removedByModerator(AUTHOR, postSource(), null, "Quảng cáo.", true);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(port).notifyUser(eq(AUTHOR), eq(CommunityNotifier.TYPE_REMOVED), eq("Bài viết của bạn đã bị gỡ"),
                message.capture(), eq("/community/posts/" + POST_ID), eq("COMMUNITY_REMOVED_POST_" + POST_ID));
        assertTrue(message.getValue().contains("Lý do: Quảng cáo."), message.getValue());

        real.removedByModerator(LEARNER, postSource(), 100L, "Spam", true);
        verify(port).notifyUser(eq(LEARNER), eq(CommunityNotifier.TYPE_REMOVED), eq("Bình luận của bạn đã bị gỡ"),
                anyString(), eq("/community/posts/" + POST_ID), eq("COMMUNITY_REMOVED_COMMENT_100"));
    }

    @Test
    void removalNotificationOnAPostTheCommenterCannotSeeHidesItsTextAndLink() {
        UserNotificationPort port = mock(UserNotificationPort.class);
        new CommunityNotifier(port).removedByModerator(LEARNER, postSource(), 101L, "Spam", false);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(port).notifyUser(eq(LEARNER), eq(CommunityNotifier.TYPE_REMOVED), eq("Bình luận của bạn đã bị gỡ"),
                message.capture(), eq(CommunityNotifier.COMMUNITY_HOME), eq("COMMUNITY_REMOVED_COMMENT_101"));
        assertTrue(!message.getValue().contains("Mẹo học kanji"), message.getValue());
        assertTrue(message.getValue().contains("Lý do: Spam"), message.getValue());
    }
}
