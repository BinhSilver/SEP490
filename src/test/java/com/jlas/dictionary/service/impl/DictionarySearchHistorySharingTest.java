package com.jlas.dictionary.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jlas.dictionary.entity.DictionarySearchHistory;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.util.DictionaryUtil;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [JLAS-W0145] QA evidence (characterization of existing behaviour, written after the code): a handwriting
 * recognition only proposes candidates; choosing one runs the normal search, which records the lookup in the same
 * single history stream as a typed search — no separate handwriting history.
 */
@ExtendWith(MockitoExtension.class)
class DictionarySearchHistorySharingTest {
    @Mock DictionarySearchHistoryRepository searchHistoryRepository;
    @Mock com.jlas.auth.repository.UserRepository userRepository;
    @Mock com.jlas.dictionary.repository.KanjiRepository kanjiRepository;
    @Mock com.jlas.dictionary.repository.VocabularyEntryRepository vocabularyEntryRepository;
    @Mock com.jlas.dictionary.repository.VocabularyKanjiRepository vocabularyKanjiRepository;
    @Mock com.jlas.dictionary.repository.VocabularySenseRepository vocabularySenseRepository;
    @Mock com.jlas.dictionary.repository.VocabularyGlossRepository vocabularyGlossRepository;
    @Mock com.jlas.dictionary.repository.VocabularyReadingRepository vocabularyReadingRepository;
    @Mock com.jlas.dictionary.repository.KanjiReadingRepository kanjiReadingRepository;
    @Mock com.jlas.dictionary.repository.SensePartOfSpeechRepository sensePartOfSpeechRepository;
    @Mock com.jlas.dictionary.repository.SenseDomainRepository senseDomainRepository;
    @Mock com.jlas.dictionary.mapper.DictionaryMapper mapper;
    @InjectMocks DictionaryServiceImpl service;

    @Test
    void searchingARecognizedKanjiRecordsOneRowInTheSharedHistory() {
        service.search("学", 0, 20, 7L);

        var saved = ArgumentCaptor.forClass(DictionarySearchHistory.class);
        verify(searchHistoryRepository, times(1)).save(saved.capture());
        assertEquals("学", saved.getValue().getQueryText());
        assertEquals(DictionaryUtil.classify(DictionaryUtil.normalizeText("学")).name(), saved.getValue().getSearchMode());
    }

    @Test
    void laterPagesAndAnonymousLookupsAddNoHistory() {
        service.search("学", 1, 20, 7L);
        service.search("学", 0, 20, null);

        verify(searchHistoryRepository, never()).save(any());
    }

    @Test
    void recognitionItselfCannotWriteHistoryBecauseItCarriesNoUser() {
        Method recognize = Arrays.stream(DictionaryServiceImpl.class.getMethods())
                .filter(m -> m.getName().equals("recognizeHandwriting")).findFirst().orElseThrow();
        assertEquals(1, recognize.getParameterCount(), "recognition must stay user-free; history is written by search");
    }
}
