package com.jlas.dictionary.service.impl;

import com.jlas.dictionary.dto.VocabularyResponse;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.repository.VocabularySenseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DictionaryLookupScopeTest {
    @Mock VocabularyEntryRepository entries;
    @Mock VocabularySenseRepository senses;
    @Mock VocabularyKanjiRepository vocabularyKanji;
    @Mock KanjiRepository kanji;
    @Mock DictionaryMapper mapper;
    @InjectMocks DictionaryServiceImpl dictionary;

    @Test
    void searchExcludesTextbookOnlyFragmentAndReturnsItsDictionaryBaseForm() {
        var fragment = VocabularyEntry.builder().id(1L).expression("寒く")
                .normalizedExpression("寒く").usageScope("TEXTBOOK").dictionaryStatus("PUBLISHED").build();
        var base = VocabularyEntry.builder().id(2L).expression("寒い")
                .normalizedExpression("寒い").usageScope("BOTH").dictionaryStatus("PUBLISHED").build();
        // The exact lookup query excludes the published textbook-only row.
        when(entries.findFirstByNormalizedExpressionAndDictionaryStatus("寒く", "PUBLISHED"))
                .thenReturn(List.of(fragment).stream()
                        .filter(entry -> !"TEXTBOOK".equals(entry.getUsageScope())).findFirst());
        var part = mock(VocabularyEntryRepository.PartOfSpeechRow.class);
        when(part.getTerm()).thenReturn("寒い");
        when(part.getPartOfSpeech()).thenReturn("adj-i");
        when(part.getVocabularyId()).thenReturn(base.getId());
        when(entries.findPartsOfSpeechByExpressionIn(argThat(terms -> terms.contains("寒い"))))
                .thenReturn(List.of(part));
        when(entries.findByIdIn(anyList())).thenReturn(List.of(base));
        when(mapper.toVocabularyResponse(eq(base), anyList(), anyList()))
                .thenReturn(VocabularyResponse.builder().id(base.getId()).expression(base.getExpression()).build());

        var result = dictionary.search("寒く", 0, 20, null);

        assertThat(result.getVocabularyResults().getContent())
                .extracting(VocabularyResponse::getExpression).containsExactly("寒い");
        verify(entries).findFirstByNormalizedExpressionAndDictionaryStatus("寒く", "PUBLISHED");
    }
}
