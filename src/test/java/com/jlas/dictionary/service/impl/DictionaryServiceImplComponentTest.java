package com.jlas.dictionary.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.ComponentSearchResponse;
import com.jlas.dictionary.dto.KanjiComponentResponse;
import com.jlas.dictionary.entity.KanjiComponent;
import com.jlas.dictionary.entity.Radical;
import com.jlas.dictionary.repository.KanjiComponentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplComponentTest {

    @Mock KanjiComponentRepository kanjiComponentRepository;
    @InjectMocks DictionaryServiceImpl service;

    private static KanjiComponentRepository.ComponentKanjiRow row(String character, int strokes, String hanViet) {
        return new KanjiComponentRepository.ComponentKanjiRow() {
            public String getKanjiCharacter() { return character; }
            public Integer getStrokeCount() { return strokes; }
            public String getHanViet() { return hanViet; }
            public String getJlptLevel() { return null; }
        };
    }

    @Test
    void listsComponentsWithVietnameseRadicalNames() {
        Radical person = Radical.builder().character("人").nameVi("Nhân").build();
        when(kanjiComponentRepository.findAllForPicker()).thenReturn(List.of(
                KanjiComponent.builder().id((short) 1).character("一").strokeCount((byte) 1).radical(null).build(),
                KanjiComponent.builder().id((short) 12).character("亻").strokeCount((byte) 2).radical(person).build()));

        List<KanjiComponentResponse> components = service.listKanjiComponents();

        assertEquals(List.of("一", "亻"), components.stream().map(KanjiComponentResponse::getCharacter).toList());
        assertNull(components.get(0).getNameVi());
        assertEquals("Nhân", components.get(1).getNameVi());
        assertEquals(2, components.get(1).getStrokeCount());
    }

    @Test
    void returnsKanjiContainingAllSelectedComponentsAndTheComponentsStillAvailable() {
        List<Short> ids = List.of((short) 12, (short) 40);
        when(kanjiComponentRepository.countKanjiWithAll(ids, 2)).thenReturn(41L);
        when(kanjiComponentRepository.findKanjiWithAll(ids, 2, 300))
                .thenReturn(List.of(row("休", 6, "HƯU"), row("体", 7, "BỔN, THỂ")));
        when(kanjiComponentRepository.findCooccurringComponentIds(ids, 2))
                .thenReturn(List.<Number>of(40L, 12L, 3L));

        // Trùng lặp và null bị bỏ qua, thứ tự chọn được giữ.
        ComponentSearchResponse result = service.searchByComponents(java.util.Arrays.asList(12, 40, 12, null));

        assertEquals(41, result.getTotal());
        assertEquals(List.of("休", "体"), result.getKanji().stream().map(ComponentSearchResponse.Item::getCharacter).toList());
        assertEquals("HƯU", result.getKanji().get(0).getHanViet());
        assertEquals(List.of(3, 12, 40), result.getAvailableComponentIds());
    }

    @Test
    void noMatchSkipsTheListQueries() {
        List<Short> ids = List.of((short) 1, (short) 2);
        when(kanjiComponentRepository.countKanjiWithAll(ids, 2)).thenReturn(0L);

        ComponentSearchResponse result = service.searchByComponents(List.of(1, 2));

        assertEquals(0, result.getTotal());
        assertTrue(result.getKanji().isEmpty());
        assertTrue(result.getAvailableComponentIds().isEmpty());
        verify(kanjiComponentRepository, never()).findKanjiWithAll(anyList(), anyInt(), anyInt());
    }

    @Test
    void emptySelectionReturnsNothingWithoutQuerying() {
        ComponentSearchResponse result = service.searchByComponents(List.of());

        assertEquals(0, result.getTotal());
        verifyNoInteractions(kanjiComponentRepository);
    }

    @Test
    void tooManyComponentsIsAValidationError() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.searchByComponents(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        verifyNoInteractions(kanjiComponentRepository);
    }
}
