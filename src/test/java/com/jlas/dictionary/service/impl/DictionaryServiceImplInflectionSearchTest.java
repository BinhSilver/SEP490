package com.jlas.dictionary.service.impl;

import com.jlas.dictionary.dto.SearchResultResponse;
import com.jlas.dictionary.dto.VocabularyResponse;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository.PartOfSpeechRow;
import com.jlas.dictionary.repository.VocabularyGlossRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.repository.VocabularyReadingRepository;
import com.jlas.dictionary.repository.VocabularySenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplInflectionSearchTest {

    @Mock VocabularyEntryRepository vocabularyEntryRepository;
    @Mock VocabularyKanjiRepository vocabularyKanjiRepository;
    @Mock VocabularySenseRepository vocabularySenseRepository;
    @Mock VocabularyGlossRepository vocabularyGlossRepository;
    @Mock VocabularyReadingRepository vocabularyReadingRepository;
    @Mock KanjiRepository kanjiRepository;
    @Mock KanjiReadingRepository kanjiReadingRepository;
    @Mock DictionarySearchHistoryRepository searchHistoryRepository;
    @Spy DictionaryMapper mapper = new DictionaryMapper();
    @InjectMocks DictionaryServiceImpl service;

    private static VocabularyEntry word(long id, String expression, String reading) {
        return VocabularyEntry.builder().id(id).expression(expression).normalizedExpression(expression)
                .reading(reading).normalizedReading(reading).build();
    }

    private static PartOfSpeechRow row(long id, String term, String partOfSpeech) {
        return new PartOfSpeechRow() {
            public Long getVocabularyId() { return id; }
            public String getTerm() { return term; }
            public String getPartOfSpeech() { return partOfSpeech; }
        };
    }

    private static List<String> expressions(SearchResultResponse result) {
        return result.getVocabularyResults().getContent().stream().map(VocabularyResponse::getExpression).toList();
    }

    @Test
    void stemShowsItsOwnNounFirstThenTheVerb() {
        VocabularyEntry noun = word(1, "行き", "いき");
        VocabularyEntry verb = word(2, "行く", "いく");
        VocabularyEntry compound = word(3, "行き先", "いきさき");
        when(vocabularyEntryRepository.findFirstByNormalizedExpressionAndDictionaryStatus("行き", "PUBLISHED"))
                .thenReturn(Optional.of(noun));
        when(vocabularyEntryRepository.findPartsOfSpeechByExpressionIn(argThat(terms -> terms.contains("行く"))))
                .thenReturn(List.of(row(2, "行く", "v5k-s"), row(2, "行く", "vi")));
        when(vocabularyEntryRepository.findByIdIn(List.of(2L))).thenReturn(List.of(verb));
        when(vocabularyEntryRepository.findByNormalizedExpressionStartingWithAndDictionaryStatusOrderByIdAsc(
                eq("行き"), eq("PUBLISHED"), any()))
                .thenReturn(List.of(noun, compound));

        assertEquals(List.of("行き", "行く", "行き先"), expressions(service.search("行き", 0, 20, null)));
    }

    @Test
    void politeFormFindsVerbButNotEntriesWithOtherPartsOfSpeech() {
        // 食べます -> 食べる (v1). Mục 食べる giả định có từ loại danh từ thì không được nhận.
        when(vocabularyEntryRepository.findPartsOfSpeechByExpressionIn(argThat(terms -> terms.contains("食べる"))))
                .thenReturn(List.of(row(7, "食べる", "v1"), row(8, "食べる", "n")));
        when(vocabularyEntryRepository.findByIdIn(List.of(7L))).thenReturn(List.of(word(7, "食べる", "たべる")));

        assertEquals(List.of("食べる"), expressions(service.search("食べます", 0, 20, null)));
        // Đã có kết quả nên không tách thành 食 + べ + ます nữa.
        verify(vocabularyEntryRepository, never())
                .findByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(anyCollection(), anyString());
    }

    @Test
    void kanaQueryIsDeinflectedOnlyWithoutExactReading() {
        when(vocabularyEntryRepository.findPartsOfSpeechByReadingIn(argThat(terms -> terms.contains("いく"))))
                .thenReturn(List.of(row(2, "いく", "v5k-s")));
        when(vocabularyEntryRepository.findByIdIn(List.of(2L))).thenReturn(List.of(word(2, "行く", "いく")));

        assertEquals(List.of("行く"), expressions(service.search("いきます", 0, 20, null)));

        when(vocabularyEntryRepository.findFirstByNormalizedReadingAndDictionaryStatus("まえ", "PUBLISHED"))
                .thenReturn(Optional.of(word(4, "前", "まえ")));
        service.search("まえ", 0, 20, null);
        verify(vocabularyEntryRepository, never()).findPartsOfSpeechByReadingIn(argThat(terms -> terms.contains("まう")));
    }

    @Test
    void singleKanjiIsNotDeinflected() {
        service.search("行", 0, 20, null);
        verify(vocabularyEntryRepository, never()).findPartsOfSpeechByExpressionIn(anyCollection());
        verify(vocabularyEntryRepository, never()).findByIdIn(anyList());
    }
}
