package com.jlas.dictionary.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DictionaryEngagementServiceTest {
    @Mock DictionaryService dictionary;
    @Mock DictionaryFavoriteRepository favorites;
    @Mock DictionarySearchHistoryRepository history;
    @Mock VocabularyEntryRepository vocabulary;
    @Mock UserRepository users;
    DictionaryEngagementService service;

    @BeforeEach void setUp() { service = new DictionaryEngagementService(dictionary, favorites, history, vocabulary, users); }

    @Test void inlineLookupRecordsSelectedVocabularyOnlyForAuthenticatedUser() {
        VocabularyResponse word = VocabularyResponse.builder().id(5L).expression("旅行").reading("りょこう")
                .meaning("du lịch").wordType("noun").build();
        when(dictionary.search("旅行", 0, 1, null)).thenReturn(SearchResultResponse.builder()
                .query("旅行").searchMode("KANJI").kanjiResults(List.of())
                .vocabularyResults(PageResponse.<VocabularyResponse>builder().content(List.of(word)).build())
                .total(1).build());
        when(users.getReferenceById(7L)).thenReturn(User.builder().id(7L).build());
        when(vocabulary.getReferenceById(5L)).thenReturn(VocabularyEntry.builder().id(5L).build());

        var response = service.inlineLookup("旅行", 7L);

        assertEquals(5L, response.vocabularyId());
        assertEquals("/dictionary/index.html?q=%E6%97%85%E8%A1%8C", response.detailUrl());
        verify(history).save(argThat(row -> row.getUser().getId().equals(7L)
                && row.getSelectedVocabulary().getId().equals(5L)
                && "旅行".equals(row.getQueryText())
                && "旅行".equals(row.getNormalizedQuery())
                && "KANJI".equals(row.getSearchMode())
                && row.getResultCount() == 1));

        service.inlineLookup("旅行", null);
        verify(history, times(1)).save(any());
    }

    @Test void quickSaveIsIdempotentAndOwned() {
        var entry = VocabularyEntry.builder().id(5L).expression("旅行").build();
        var favorite = DictionaryFavorite.builder()
                .id(new DictionaryFavorite.DictionaryFavoriteId(7L, 5L)).vocabularyEntry(entry).build();
        when(vocabulary.findById(5L)).thenReturn(Optional.of(entry));
        when(favorites.insertIgnore(7L, 5L)).thenReturn(1, 0);
        when(favorites.findById(new DictionaryFavorite.DictionaryFavoriteId(7L, 5L)))
                .thenReturn(Optional.of(favorite));

        assertTrue(service.saveFavorite(7L, 5L).created());
        assertFalse(service.saveFavorite(7L, 5L).created());
        verify(favorites, times(2)).insertIgnore(7L, 5L);
        verify(favorites, never()).save(any());
    }

    @Test void favoritesArePagedByOwner() {
        var entry = VocabularyEntry.builder().id(5L).expression("旅行").reading("りょこう").build();
        var row = DictionaryFavorite.builder().id(new DictionaryFavorite.DictionaryFavoriteId(7L, 5L))
                .vocabularyEntry(entry).build();
        when(favorites.findByUserIdOrderByCreatedAtDesc(eq(7L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));
        var page = service.favorites(7L, 0, 20);
        assertEquals(1, page.getTotalElements());
        assertEquals("旅行", page.getContent().getFirst().expression());
    }

    @Test void historyIsPagedByOwnerAndClampsRequestedSize() {
        var entry = VocabularyEntry.builder().id(5L).expression("旅行").build();
        var row = DictionarySearchHistory.builder().id(11L).queryText("旅行")
                .normalizedQuery("旅行").searchMode("KANJI").resultCount(3)
                .selectedVocabulary(entry).build();
        when(history.findByUserIdOrderBySearchedAtDesc(eq(7L), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    assertEquals(0, pageable.getPageNumber());
                    assertEquals(50, pageable.getPageSize());
                    return new PageImpl<>(List.of(row), pageable, 1);
                });

        var page = service.history(7L, -2, 500);

        assertEquals(1, page.getTotalElements());
        assertEquals(5L, page.getContent().getFirst().getSelectedVocabularyId());
        verify(history).findByUserIdOrderBySearchedAtDesc(eq(7L), any(Pageable.class));
    }
}
