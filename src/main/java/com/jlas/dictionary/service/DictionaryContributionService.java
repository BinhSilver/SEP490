package com.jlas.dictionary.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.entity.ContentComment;
import com.jlas.moderation.entity.ContentCommentVote;
import com.jlas.moderation.model.ModerationReviewQueue;
import com.jlas.moderation.repository.ContentCommentRepository;
import com.jlas.moderation.repository.ContentCommentVoteRepository;
import com.jlas.moderation.repository.ModerationReviewQueueRepository;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.moderation.service.UserActionPolicyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class DictionaryContributionService {
    private static final String VOCABULARY = "VOCABULARY";
    private static final String KANJI = "KANJI";
    private static final String MODERATION_TARGET = "DICTIONARY_COMMENT";

    private final VocabularyEntryRepository vocabulary;
    private final KanjiRepository kanji;
    private final UserRepository users;
    private final ContentCommentRepository comments;
    private final ContentCommentVoteRepository votes;
    private final ModerationReviewQueueRepository reviewQueue;
    private final ContentModerationPort moderation;
    private final UserActionPolicyPort actionPolicy;

    @Transactional(readOnly = true)
    public PageResponse<ContributionResponse> vocabularyComments(Long vocabularyId, int page, int size, Long userId) {
        requireVocabulary(vocabularyId);
        return list(VOCABULARY, vocabularyId, page, size, userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ContributionResponse> kanjiComments(String character, int page, int size, Long userId) {
        return list(KANJI, requireKanji(character), page, size, userId);
    }

    public ContributionResponse addVocabulary(Long vocabularyId, ContributionRequest request, Long userId) {
        requireVocabulary(vocabularyId);
        return add(VOCABULARY, vocabularyId, request, userId);
    }

    public ContributionResponse addKanji(String character, ContributionRequest request, Long userId) {
        return add(KANJI, requireKanji(character), request, userId);
    }

    public ContributionResponse vote(Long commentId, int value, Long userId) {
        Long voter = login(userId);
        if (value != -1 && value != 0 && value != 1) throw new ApiException(ErrorCode.BAD_REQUEST, "Lá phiếu không hợp lệ");
        ContentComment comment = dictionaryComment(commentId);
        if (Boolean.TRUE.equals(comment.getIsHidden())) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ý kiến đóng góp");
        ContentCommentVote.Id id = new ContentCommentVote.Id(commentId, voter);
        if (value == 0) votes.deleteById(id);
        else votes.save(ContentCommentVote.builder().id(id).vote((byte) value).build());
        votes.flush();
        ContentCommentVoteRepository.VoteCounts counts = votes.countsForComments(List.of(commentId), voter)
                .stream().findFirst().orElse(null);
        return response(comment, counts, voter);
    }

    public void delete(Long commentId, Long userId) {
        ContentComment comment = dictionaryComment(commentId);
        if (!comment.getUser().getId().equals(login(userId))) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Không có quyền xóa ý kiến đóng góp");
        }
        comments.delete(comment);
    }

    public void report(Long commentId, String reason, Long userId) {
        Long reporter = login(userId);
        ContentComment comment = comments.findForUpdateById(commentId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ý kiến đóng góp"));
        if ((!VOCABULARY.equals(comment.getTargetType()) && !KANJI.equals(comment.getTargetType()))
                || Boolean.TRUE.equals(comment.getIsHidden())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ý kiến đóng góp");
        }
        if (comment.getUser().getId().equals(reporter)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không thể báo cáo ý kiến của chính bạn");
        }
        actionPolicy.assertCanAct(reporter);
        comment.setIsHidden(true);
        comments.save(comment);
        if (reviewQueue.findFirstByTargetTypeAndTargetIdAndStatus(MODERATION_TARGET, commentId, "OPEN").isEmpty()) {
            String detail = StringUtils.hasText(reason) ? ": " + reason.trim() : "";
            reviewQueue.save(ModerationReviewQueue.builder()
                    .targetType(MODERATION_TARGET).targetId(commentId)
                    .requesterUserId(reporter).ownerUserId(comment.getUser().getId())
                    .titleSnapshot("Báo cáo ý kiến đóng góp từ điển")
                    .summary("Người dùng báo cáo" + detail).build());
        }
    }

    private PageResponse<ContributionResponse> list(String type, Long targetId, int page, int size, Long userId) {
        Page<Long> ids = comments.findVisibleIdsByLikes(type, targetId,
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
        List<Long> order = ids.getContent();
        Map<Long, ContentComment> byId = new HashMap<>();
        Map<Long, ContentCommentVoteRepository.VoteCounts> counts = new HashMap<>();
        if (!order.isEmpty()) {
            comments.findWithAuthorByIdIn(order).forEach(comment -> byId.put(comment.getId(), comment));
            votes.countsForComments(order, userId).forEach(row -> counts.put(row.getCommentId(), row));
        }
        return PageResponse.<ContributionResponse>builder()
                .content(order.stream().map(id -> response(byId.get(id), counts.get(id), userId)).toList())
                .page(ids.getNumber()).size(ids.getSize()).totalElements(ids.getTotalElements())
                .last(ids.isLast()).build();
    }

    private ContributionResponse add(String type, Long targetId, ContributionRequest request, Long userId) {
        Long author = login(userId);
        actionPolicy.assertCanAct(author);
        String content = request == null ? null : request.getContent();
        if (!StringUtils.hasText(content) || content.length() > 2000) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Nội dung phải có từ 1 đến 2000 ký tự");
        }
        content = content.trim();
        moderation.checkComment(String.valueOf(author));
        ModerationResult result = moderation.scanText(content);
        if (result.isBlocked()) {
            moderation.persistLog(MODERATION_TARGET, targetId, author, "COMMENT", content, result);
            throw new ApiException(ErrorCode.CONTENT_BLOCKED, result.getSummary());
        }
        User user = users.findById(author)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy người dùng"));
        ContentComment comment = comments.save(ContentComment.builder().targetType(type).targetId(targetId)
                .user(user).content(content).isHidden(result.needsReview()).build());
        Long logId = moderation.persistLog(MODERATION_TARGET, comment.getId(), author, "COMMENT", content, result);
        if (result.needsReview()) moderation.enqueueReview(logId, MODERATION_TARGET, comment.getId(), author,
                "Ý kiến đóng góp từ điển", result.getSummary());
        return response(comment, null, author);
    }

    private ContributionResponse response(ContentComment comment, ContentCommentVoteRepository.VoteCounts counts, Long userId) {
        User author = comment.getUser();
        String name = StringUtils.hasText(author.getFullName()) ? author.getFullName() : author.getUsername();
        return ContributionResponse.builder().id(comment.getId()).content(comment.getContent())
                .authorName(name).likes(counts == null ? 0 : counts.getLikes())
                .dislikes(counts == null ? 0 : counts.getDislikes())
                .myVote(counts == null ? 0 : counts.getMyVote())
                .mine(userId != null && userId.equals(author.getId()))
                .createdAt(comment.getCreatedAt()).build();
    }

    private ContentComment dictionaryComment(Long id) {
        ContentComment comment = comments.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ý kiến đóng góp"));
        if (!VOCABULARY.equals(comment.getTargetType()) && !KANJI.equals(comment.getTargetType())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy ý kiến đóng góp");
        }
        return comment;
    }

    private void requireVocabulary(Long id) {
        if (!vocabulary.existsById(id)) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng");
    }

    private Long requireKanji(String character) {
        return kanji.findByCharacter(character).orElseThrow(
                () -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy Kanji")).getId();
    }

    private Long login(Long id) {
        if (id == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "Vui lòng đăng nhập");
        return id;
    }
}
