package com.jlas.dictionary.service.impl;

import com.jlas.dictionary.dto.SearchResultResponse;
import com.jlas.dictionary.dto.VocabularyResponse;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyGlossRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.repository.VocabularySenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplCompoundSearchTest {

    @Mock VocabularyEntryRepository vocabularyEntryRepository;
    @Mock VocabularyKanjiRepository vocabularyKanjiRepository;
    @Mock VocabularySenseRepository vocabularySenseRepository;
    @Mock VocabularyGlossRepository vocabularyGlossRepository;
    @Mock KanjiRepository kanjiRepository;
    @Mock KanjiReadingRepository kanjiReadingRepository;
    @Mock DictionarySearchHistoryRepository searchHistoryRepository;
    @Spy DictionaryMapper mapper = new DictionaryMapper();
    @InjectMocks DictionaryServiceImpl service;

    private static VocabularyEntry word(long id, String expression) {
        return VocabularyEntry.builder().id(id).expression(expression).normalizedExpression(expression)
                .reading(expression).normalizedReading(expression).build();
    }

    private static List<String> expressions(SearchResultResponse result) {
        return result.getVocabularyResults().getContent().stream().map(VocabularyResponse::getExpression).toList();
    }

    @Test
    void compoundMissingFromDictionaryIsSplitIntoItsLongestWords() {
        when(vocabularyEntryRepository.findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(
                anyCollection(), eq("PUBLISHED")))
                .thenReturn(List.of(word(5, "剤"), word(9, "防"), word(12, "防錆")));

        SearchResultResponse result = service.search("防錆剤", 0, 20, null);

        // 防錆 (khớp dài nhất) đứng trước, 防 lẻ bị bỏ vì đã nằm trong 防錆, rồi tới 剤.
        assertEquals(List.of("防錆", "剤"), expressions(result));
        verify(vocabularyEntryRepository).findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(
                eq(Set.of("防", "錆", "剤", "防錆", "錆剤", "防錆剤")), eq("PUBLISHED"));
    }

    @Test
    void exactMatchDoesNotTriggerSplitting() {
        when(vocabularyEntryRepository.findFirstByNormalizedExpressionAndDictionaryStatus("学校", "PUBLISHED"))
                .thenReturn(Optional.of(word(1, "学校")));

        SearchResultResponse result = service.search("学校", 0, 20, null);

        assertEquals(List.of("学校"), expressions(result));
        verify(vocabularyEntryRepository, never())
                .findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(anyCollection(), anyString());
    }
}
