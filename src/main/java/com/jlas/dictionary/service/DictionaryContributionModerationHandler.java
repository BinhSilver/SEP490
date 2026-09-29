package com.jlas.dictionary.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.moderation.dto.ContributionDetail;
import com.jlas.moderation.dto.ModerationTargetSnapshot;
import com.jlas.moderation.service.ContributionDetailPort;
import com.jlas.moderation.service.ModerationTargetHandler;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dictionary side of contribution review (JLAS-W0250): approving publishes the entry,
 * rejecting keeps it hidden. The review case itself is handled by moderation.
 */
@Component
@RequiredArgsConstructor
public class DictionaryContributionModerationHandler implements ModerationTargetHandler, ContributionDetailPort {
    static final String TARGET = "WORD_CONTRIBUTION";
    static final String PUBLISHED = "PUBLISHED";
    static final String HIDDEN = "HIDDEN";

    private final VocabularyEntryRepository entries;

    @Override
    public boolean supports(String targetType) {
        return TARGET.equals(targetType);
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationTargetSnapshot snapshot(String targetType, Long targetId) {
        return entries.findById(targetId)
                .map(entry -> new ModerationTargetSnapshot(
                        entry.getExpression() + " 【" + entry.getReading() + "】 " + nullToEmpty(entry.getMeaning()),
                        null))
                .orElseGet(ModerationTargetSnapshot::empty);
    }

    @Override
    @Transactional
    public void resolve(String targetType, Long targetId, boolean approve, String note) {
        VocabularyEntry entry = entries.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Từ vựng đóng góp không còn tồn tại"));
        if (PUBLISHED.equals(entry.getDictionaryStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Từ vựng này đã được công khai trước đó");
        }
        entry.setDictionaryStatus(approve ? PUBLISHED : HIDDEN);
        entries.save(entry);
    }

    /** A revision may take a published word back out of the dictionary, so no "already published" guard. */
    @Override
    @Transactional
    public void revise(String targetType, Long targetId, boolean approve, String note) {
        VocabularyEntry entry = entries.findById(targetId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Từ vựng đóng góp không còn tồn tại"));
        entry.setDictionaryStatus(approve ? PUBLISHED : HIDDEN);
        entries.save(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContributionDetail> detail(Long vocabularyId) {
        return entries.findById(vocabularyId).map(entry -> new ContributionDetail(entry.getId(),
                entry.getExpression(), entry.getReading(), entry.getMeaning(), entry.getWordType(),
                entry.getJlptLevel(), entry.getExampleJa(), entry.getExampleVi(), entry.getDictionaryStatus(),
                entries.findTop5ByNormalizedExpressionAndDictionaryStatusAndIdNot(
                                entry.getNormalizedExpression(), PUBLISHED, entry.getId()).stream()
                        .map(e -> new ContributionDetail.Existing(e.getId(), e.getExpression(), e.getReading(),
                                e.getMeaning()))
                        .toList()));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
