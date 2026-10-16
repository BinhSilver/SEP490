package com.jlas.dictionary.controller;

import com.jlas.common.GlobalApiExceptionHandler;
import com.jlas.dictionary.dto.ComponentSearchResponse;
import com.jlas.dictionary.dto.KanjiComponentResponse;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.DictionaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DictionaryControllerComponentTest {

    private DictionaryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DictionaryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DictionaryController(service, mock(HandwritingRateLimiter.class)))
                .setControllerAdvice(new GlobalApiExceptionHandler())
                .build();
    }

    @Test
    void listsComponents() throws Exception {
        when(service.listKanjiComponents()).thenReturn(List.of(
                KanjiComponentResponse.builder().id(12).character("亻").strokeCount(2).nameVi("Nhân").build()));

        mvc.perform(get("/api/dictionary/components"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].character").value("亻"))
                .andExpect(jsonPath("$.data[0].nameVi").value("Nhân"));
    }

    @Test
    void searchAcceptsCommaSeparatedIds() throws Exception {
        when(service.searchByComponents(List.of(12, 40))).thenReturn(ComponentSearchResponse.builder()
                .total(1)
                .kanji(List.of(ComponentSearchResponse.Item.builder().character("休").strokeCount(6).build()))
                .availableComponentIds(List.of(12, 40))
                .build());

        mvc.perform(get("/api/dictionary/components/search").param("ids", "12,40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.kanji[0].character").value("休"))
                .andExpect(jsonPath("$.data.availableComponentIds[1]").value(40));
        verify(service).searchByComponents(List.of(12, 40));
    }
}
