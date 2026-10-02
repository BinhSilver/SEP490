package com.jlas.dictionary.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import com.jlas.dictionary.util.DictionaryUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class DictionaryEngagementService {
    private final DictionaryService dictionary;
    private final DictionaryFavoriteRepository favorites;
    private final DictionarySearchHistoryRepository history;
    private final VocabularyEntryRepository vocabulary;
    private final UserRepository users;

    @Transactional
    public InlineLookupResponse inlineLookup(String query, Long userId) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cụm từ tra cứu không hợp lệ.");
        }
        SearchResultResponse result = dictionary.search(trimmed, 0, 1, null);
        List<VocabularyResponse> content = result.getVocabularyResults() == null
                ? List.of() : result.getVocabularyResults().getContent();
        if (content == null || content.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng phù hợp.");
        }
        VocabularyResponse word = content.getFirst();
        if (userId != null) {
            history.save(DictionarySearchHistory.builder().user(users.getReferenceById(userId))
                    .queryText(trimmed).normalizedQuery(DictionaryUtil.normalizeText(trimmed))
                    .searchMode(result.getSearchMode() == null ? "AUTO" : result.getSearchMode())
                    .selectedVocabulary(vocabulary.getReferenceById(word.getId())).resultCount(result.getTotal()).build());
        }
        return new InlineLookupResponse(word.getId(), word.getExpression(), word.getReading(),
                word.getMeaning(), word.getWordType(), "/dictionary/index.html?q="
                + URLEncoder.encode(word.getExpression(), StandardCharsets.UTF_8));
    }

    @Transactional
    public DictionaryFavoriteResponse saveFavorite(Long userId, Long vocabularyId) {
        var id = new DictionaryFavorite.DictionaryFavoriteId(userId, vocabularyId);
        var existing = favorites.findById(id);
        if (existing.isPresent()) return favorite(existing.get(), false);
        VocabularyEntry entry = vocabulary.findById(vocabularyId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng."));
        DictionaryFavorite saved = favorites.save(DictionaryFavorite.builder().id(id)
                .user(users.getReferenceById(userId)).vocabularyEntry(entry).build());
        return favorite(saved, true);
    }

    @Transactional
    public void deleteFavorite(Long userId, Long vocabularyId) {
        favorites.deleteById(new DictionaryFavorite.DictionaryFavoriteId(userId, vocabularyId));
    }

    @Transactional(readOnly = true)
    public Page<DictionaryFavoriteResponse> favorites(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
        return favorites.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(row -> favorite(row, false));
    }

    @Transactional(readOnly = true)
    public Page<SearchHistoryResponse> history(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
        return history.findByUserIdOrderBySearchedAtDesc(userId, pageable).map(row ->
                SearchHistoryResponse.builder().id(row.getId()).queryText(row.getQueryText())
                        .searchMode(row.getSearchMode()).resultCount(row.getResultCount())
                        .searchedAt(row.getSearchedAt()).selectedVocabularyId(row.getSelectedVocabulary() == null
                                ? null : row.getSelectedVocabulary().getId()).build());
    }

    private static DictionaryFavoriteResponse favorite(DictionaryFavorite row, boolean created) {
        return new DictionaryFavoriteResponse(row.getVocabularyEntry().getId(),
                row.getVocabularyEntry().getExpression(), row.getVocabularyEntry().getReading(),
                row.getNote(), row.getCreatedAt(), created);
    }
}
