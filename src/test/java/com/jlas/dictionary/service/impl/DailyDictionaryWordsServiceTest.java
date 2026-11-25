package com.jlas.dictionary.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.dictionary.dto.KanjiCompositionResponse;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.entity.VocabularyKanji;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyDictionaryWordsServiceTest {
    @Test
    void singleKanjiLevelsAreFetchedOnceForDailyAndMatchDetail() {
        JpathUserPathRepository paths = mock(JpathUserPathRepository.class);
        UserLearningSurveyRepository surveys = mock(UserLearningSurveyRepository.class);
        VocabularyEntryRepository vocabulary = mock(VocabularyEntryRepository.class);
        VocabularyKanjiRepository composition = mock(VocabularyKanjiRepository.class);
        DailyDictionaryWordsService service = new DailyDictionaryWordsService(paths, surveys,
                mock(DictionarySearchHistoryRepository.class), vocabulary, composition);
        VocabularyEntry entry = VocabularyEntry.builder().id(11L).expression("俎").reading("まないた")
                .meaning("cái thớt").build();
        VocabularyEntry second = VocabularyEntry.builder().id(12L).expression("亜").reading("あ")
                .meaning("châu Á").build();
        Kanji kanji = Kanji.builder().jlptLevel("N1").build();
        VocabularyKanji link = VocabularyKanji.builder().vocabularyEntry(entry).kanji(kanji).build();
        VocabularyKanji secondLink = VocabularyKanji.builder().vocabularyEntry(second)
                .kanji(Kanji.builder().jlptLevel("N2").build()).build();
        when(paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(eq(7L), any())).thenReturn(Optional.empty());
        when(surveys.findById(7L)).thenReturn(Optional.of(UserLearningSurvey.builder().targetLevel("N5").build()));
        when(vocabulary.findDailyWordsForLevel(eq("N5"), anyLong())).thenReturn(List.of(entry, second));
        when(composition.findCompositionByVocabularyIds(List.of(11L, 12L))).thenReturn(List.of(link, secondLink));

        DailyDictionaryWordsService.Result result = service.dailyWords(7L, LocalDate.of(2026, 11, 25));

        assertEquals("N1", result.words().get(0).jlptLevel());
        assertEquals("N2", result.words().get(1).jlptLevel());
        assertEquals("N1", new DictionaryMapper().toVocabularyResponse(entry, List.of(),
                List.of(KanjiCompositionResponse.builder().character("俎").jlptLevel("N1").build())).getJlptLevel());
        verify(composition).findCompositionByVocabularyIds(List.of(11L, 12L));
    }
}
