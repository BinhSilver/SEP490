package com.jlas.dictionary.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.entity.ContentComment;
import com.jlas.moderation.repository.ContentCommentRepository;
import com.jlas.moderation.service.ModerationTargetHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class DictionaryCommentModerationTargetHandler implements ModerationTargetHandler {
    private final ContentCommentRepository comments;
    private final VocabularyEntryRepository vocabulary;
    private final KanjiRepository kanji;

    @Override
    public boolean supports(String targetType) { return "DICTIONARY_COMMENT".equals(targetType); }

    @Override
    @Transactional(readOnly = true)
    public ModerationTargetSnapshot snapshot(String targetType, Long targetId) {
        return comments.findById(targetId)
                .filter(comment -> "VOCABULARY".equals(comment.getTargetType()) || "KANJI".equals(comment.getTargetType()))
                .map(comment -> new ModerationTargetSnapshot(comment.getContent(), targetUrl(comment)))
                .orElseGet(ModerationTargetSnapshot::empty);
    }

    @Override
    @Transactional
    public void resolve(String targetType, Long targetId, boolean approve, String note) {
        ContentComment comment = comments.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Ý kiến đóng góp không còn tồn tại"));
        if (!"VOCABULARY".equals(comment.getTargetType()) && !"KANJI".equals(comment.getTargetType())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Ý kiến đóng góp không còn tồn tại");
        }
        comment.setIsHidden(!approve);
        comments.save(comment);
    }

    private String targetUrl(ContentComment comment) {
        String value = "VOCABULARY".equals(comment.getTargetType())
                ? vocabulary.findById(comment.getTargetId()).map(entry -> entry.getExpression()).orElse("")
                : kanji.findById(comment.getTargetId()).map(entry -> entry.getCharacter()).orElse("");
        String tab = "VOCABULARY".equals(comment.getTargetType()) ? "word" : "kanji";
        return "/search/" + tab + "/" + UriUtils.encodePathSegment(value, StandardCharsets.UTF_8);
    }
}
