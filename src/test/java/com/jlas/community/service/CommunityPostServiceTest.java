package com.jlas.community.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.community.dto.CommunityDtos.AuthorDto;
import com.jlas.community.dto.CommunityDtos.CommentDto;
import com.jlas.community.entity.CommunityComment;
import com.jlas.community.entity.CommunityContent;
import com.jlas.community.entity.CommunityPost;
import com.jlas.community.enums.CommunityContentType;
import com.jlas.community.handler.CommunitySource;
import com.jlas.community.repository.CommunityCommentRepository;
import com.jlas.community.repository.CommunityContentRepository;
import com.jlas.community.repository.CommunityPostRepository;
import com.jlas.community.service.CommunityAccessService.Target;
import com.jlas.moderation.service.UserActionPolicyPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** [JLAS2-37] Bài viết cộng đồng: đăng bài, sửa/xoá sai quyền, bình luận trên bài, kiểm tra độ dài. */
class CommunityPostServiceTest {
    private static final long AUTHOR = 7L;
    private static final long OTHER = 9L;
    private static final long POST_ID = 42L;

    private CommunityPostRepository posts;
    private CommunityContentRepository contents;
    private CommunityAccessService access;
    private CommunityFeedService feed;
    private UserActionPolicyPort actionPolicy;
    private CommunityPostService service;

    @BeforeEach
    void setUp() {
        posts = mock(CommunityPostRepository.class);
        contents = mock(CommunityContentRepository.class);
        access = mock(CommunityAccessService.class);
        feed = mock(CommunityFeedService.class);
        actionPolicy = mock(UserActionPolicyPort.class);
        service = new CommunityPostService(posts, contents, access, feed, actionPolicy);
        // fresh(): đọc lại bản mới nhất sau khi khoá; mặc định coi như dòng không đổi.
        when(access.fresh(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Target target(Long ownerId, String status) {
        CommunitySource source = new CommunitySource(CommunityContentType.POST, POST_ID, "Bài", "Bài viết", ownerId,
                null, "/community/posts/" + POST_ID, null, LocalDateTime.now(), true, Map.of("edited", false), null);
        CommunityContent row = CommunityContent.builder().id(5L).contentType(CommunityContentType.POST)
                .contentId(POST_ID).status(status).ratingAvg(BigDecimal.ZERO).ratingCount(0).commentCount(0)
                .reportCount(0).build();
        return new Target(source, row, status);
    }

    private static CommunityPost post(Long authorId) {
        return CommunityPost.builder().id(POST_ID).authorId(authorId).body("Xin chào").build();
    }

    private static void assertCode(ErrorCode code, Executable call) {
        assertEquals(code, assertThrows(ApiException.class, call).getErrorCode());
    }

    @Test
    void createKeepsLineBreaksTrimsAndCreatesCommunityRow() {
        when(posts.saveAndFlush(any())).thenAnswer(inv -> {
            CommunityPost p = inv.getArgument(0);
            p.setId(POST_ID);
            return p;
        });
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(target(AUTHOR, "PUBLISHED"));

        service.create(AUTHOR, "  Dòng 1\r\nDòng 2  ", "n4");

        ArgumentCaptor<CommunityPost> saved = ArgumentCaptor.forClass(CommunityPost.class);
        verify(posts).saveAndFlush(saved.capture());
        assertEquals("Dòng 1\nDòng 2", saved.getValue().getBody());
        assertEquals("N4", saved.getValue().getJlptLevel());
        assertEquals(AUTHOR, saved.getValue().getAuthorId());
        verify(actionPolicy).assertCanAct(AUTHOR);
        verify(contents).ensureRow(CommunityContentType.POST, POST_ID);
    }

    @Test
    void createWithoutLevelMeansGeneral() {
        when(posts.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(AUTHOR, "Chia sẻ", "");
        ArgumentCaptor<CommunityPost> saved = ArgumentCaptor.forClass(CommunityPost.class);
        verify(posts).saveAndFlush(saved.capture());
        assertNull(saved.getValue().getJlptLevel());
    }

    @Test
    void createRejectsBlankTooLongAndBadLevel() {
        assertCode(ErrorCode.BAD_REQUEST, () -> service.create(AUTHOR, "  \n\t ", null));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.create(AUTHOR, null, null));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.create(AUTHOR, "a".repeat(CommunityPost.MAX_LENGTH + 1), null));
        assertCode(ErrorCode.BAD_REQUEST, () -> service.create(AUTHOR, "ok", "N6"));
        verify(posts, never()).saveAndFlush(any());
    }

    @Test
    void createAcceptsExactlyMaxLength() {
        when(posts.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(AUTHOR, "a".repeat(CommunityPost.MAX_LENGTH), null);
        verify(posts).saveAndFlush(any());
    }

    @Test
    void updateByAuthorMarksEdited() {
        CommunityPost post = post(AUTHOR);
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(post));
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(target(AUTHOR, "PUBLISHED"));

        service.update(POST_ID, AUTHOR, "Nội dung mới", "N3");

        assertEquals("Nội dung mới", post.getBody());
        assertEquals("N3", post.getJlptLevel());
        assertNotNull(post.getEditedAt());
        verify(posts).saveAndFlush(post);
    }

    @Test
    void updateByOtherUserIsForbiddenWhenVisibleAndNotFoundWhenHidden() {
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(post(AUTHOR)));
        Target visible = target(AUTHOR, "PUBLISHED");
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(visible);

        when(access.canView(eq(visible), eq(OTHER), anyBoolean())).thenReturn(true);
        assertCode(ErrorCode.FORBIDDEN, () -> service.update(POST_ID, OTHER, "Sửa bài người khác", null));

        when(access.canView(eq(visible), eq(OTHER), anyBoolean())).thenReturn(false);
        assertCode(ErrorCode.NOT_FOUND, () -> service.update(POST_ID, OTHER, "Sửa bài người khác", null));
        verify(posts, never()).saveAndFlush(any());
    }

    @Test
    void updateOfRemovedPostIsRejected() {
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(post(AUTHOR)));
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(target(AUTHOR, "REMOVED"));
        assertCode(ErrorCode.CONFLICT, () -> service.update(POST_ID, AUTHOR, "Sửa", null));
    }

    @Test
    void deleteByAuthorIsSoftDelete() {
        CommunityPost post = post(AUTHOR);
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(post));
        service.delete(POST_ID, AUTHOR);
        assertNotNull(post.getDeletedAt());
        verify(posts).saveAndFlush(post);
        verify(posts, never()).delete(any());
    }

    @Test
    void deleteOfPostHiddenForReviewIsRejectedSoModeratorsKeepTheContent() {
        CommunityPost post = post(AUTHOR);
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(post));
        when(contents.lockBySource(CommunityContentType.POST, POST_ID))
                .thenReturn(Optional.of(target(AUTHOR, "TEMPORARILY_HIDDEN").row()));
        assertCode(ErrorCode.CONFLICT, () -> service.delete(POST_ID, AUTHOR));
        assertNull(post.getDeletedAt());
        verify(posts, never()).saveAndFlush(any());

        // Đã có dòng trạng thái nhưng đang công khai (hoặc đã gỡ): tác giả vẫn xoá được.
        when(contents.lockBySource(CommunityContentType.POST, POST_ID))
                .thenReturn(Optional.of(target(AUTHOR, "PUBLISHED").row()));
        service.delete(POST_ID, AUTHOR);
        assertNotNull(post.getDeletedAt());
    }

    @Test
    void deleteByOtherUserIsForbiddenAndDeletedPostIsNotFound() {
        Target visible = target(AUTHOR, "PUBLISHED");
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(post(AUTHOR)));
        when(access.load(CommunityContentType.POST, POST_ID)).thenReturn(visible);
        when(access.canView(eq(visible), eq(OTHER), anyBoolean())).thenReturn(true);
        assertCode(ErrorCode.FORBIDDEN, () -> service.delete(POST_ID, OTHER));

        CommunityPost deleted = post(AUTHOR);
        deleted.setDeletedAt(LocalDateTime.now());
        when(posts.lockById(POST_ID)).thenReturn(Optional.of(deleted));
        assertCode(ErrorCode.NOT_FOUND, () -> service.delete(POST_ID, AUTHOR));
        verify(posts, never()).saveAndFlush(any());
    }

    // ── Bình luận trên bài viết: dùng chung CommunityCommentService ─────────────
    private CommunityCommentService commentService(CommunityCommentRepository comments, CommunityNotifier notifier) {
        return new CommunityCommentService(access, contents, comments, actionPolicy, notifier);
    }

    @Test
    void commentOnPostNotifiesPostAuthor() {
        CommunityCommentRepository comments = mock(CommunityCommentRepository.class);
        CommunityNotifier notifier = mock(CommunityNotifier.class);
        Target target = target(AUTHOR, "PUBLISHED");
        when(access.requireViewable(CommunityContentType.POST, POST_ID, OTHER)).thenReturn(target);
        when(access.lockInteractable(target)).thenReturn(target);
        when(comments.saveAndFlush(any())).thenAnswer(inv -> {
            CommunityComment c = inv.getArgument(0);
            c.setId(100L);
            c.setCreatedAt(LocalDateTime.now());
            c.setUpdatedAt(c.getCreatedAt());
            return c;
        });
        when(access.author(OTHER)).thenReturn(new AuthorDto(OTHER, "Người học", null));

        CommentDto created = commentService(comments, notifier)
                .create(CommunityContentType.POST, POST_ID, OTHER, "  Hay quá!  ");

        assertEquals("Hay quá!", created.body());
        verify(access).refreshCommentCount(target.row());
        verify(notifier).newComment(eq(target.source()), eq(100L), eq(OTHER), eq("Người học"));
    }

    @Test
    void commentLengthIsValidated() {
        CommunityCommentService comments = commentService(mock(CommunityCommentRepository.class), mock(CommunityNotifier.class));
        assertCode(ErrorCode.BAD_REQUEST, () -> comments.create(CommunityContentType.POST, POST_ID, OTHER, "   "));
        assertCode(ErrorCode.BAD_REQUEST,
                () -> comments.create(CommunityContentType.POST, POST_ID, OTHER, "x".repeat(CommunityComment.MAX_LENGTH + 1)));
        verify(access, never()).requireViewable(anyString(), anyLong(), anyLong());
    }

    @Test
    void commentByOtherUserCannotBeDeletedByStranger() {
        CommunityCommentRepository comments = mock(CommunityCommentRepository.class);
        CommunityComment comment = CommunityComment.builder().id(100L).communityContentId(5L).userId(OTHER)
                .body("bình luận").status("PUBLISHED").reportCount(0).build();
        when(comments.findById(100L)).thenReturn(Optional.of(comment));
        when(comments.lockById(100L)).thenReturn(Optional.of(comment));
        when(contents.lockById(5L)).thenReturn(Optional.of(target(AUTHOR, "PUBLISHED").row()));

        assertCode(ErrorCode.FORBIDDEN, () -> commentService(comments, mock(CommunityNotifier.class)).delete(100L, AUTHOR));
        assertNull(comment.getDeletedAt());
    }

    @Test
    void excerptIsSingleLineAndClipped() {
        assertEquals("a b", com.jlas.community.handler.CommunityPostHandler.excerpt(" a\n\nb "));
        String clipped = com.jlas.community.handler.CommunityPostHandler.excerpt("x".repeat(200));
        assertEquals(com.jlas.community.handler.CommunityPostHandler.TITLE_LENGTH + 1, clipped.length());
    }

    @Test
    void excerptNeverSplitsAnEmojiAtTheCut() {
        int max = com.jlas.community.handler.CommunityPostHandler.TITLE_LENGTH;
        // Emoji (cặp surrogate) đúng ở vị trí cắt: bỏ cả emoji, không để nửa cặp lẻ (driver ghi thành '?').
        String clipped = com.jlas.community.handler.CommunityPostHandler.excerpt("x".repeat(max - 1) + "😀 tiếp theo");
        assertEquals("x".repeat(max - 1) + "…", clipped);
        // Emoji nằm gọn trước vị trí cắt thì giữ nguyên.
        String kept = com.jlas.community.handler.CommunityPostHandler.excerpt("x".repeat(max - 2) + "😀 tiếp theo");
        assertEquals("x".repeat(max - 2) + "😀…", kept);
        for (String s : new String[] {clipped, kept}) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean paired = Character.isHighSurrogate(c) ? i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))
                        : !Character.isLowSurrogate(c) || (i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                assertEquals(true, paired, "nửa cặp surrogate lẻ ở vị trí " + i);
            }
        }
    }
}
