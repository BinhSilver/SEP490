package com.jlas.dictionary.controller;

import com.jlas.auth.security.JwtAuthenticationFilter;
import com.jlas.config.SecurityConfig;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.dictionary.service.AzureTtsService;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.LessonImportService;
import com.jlas.dictionary.service.QuickLookupAllowance;
import com.jlas.dictionary.service.TextbookAccessPolicy;
import com.jlas.dictionary.service.impl.DailyDictionaryWordsService;
import com.jlas.dictionary.service.impl.DictionaryKanjiPrintService;
import com.jlas.dictionary.service.impl.GrammarDictionaryService;
import com.jlas.flashcard.repository.FlashcardTextbookLinks;
import com.jlas.moderation.security.BanEnforcementFilter;
import com.jlas.moderation.service.SanctionStatusService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;

@WebMvcTest({DictionaryPageController.class, DictionaryController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, BanEnforcementFilter.class,
        DictionaryPageController.class, DictionaryController.class})
@MockitoBean(types = {LessonRepository.class, TextbookRepository.class, TextbookAccessPolicy.class,
        FlashcardTextbookLinks.class, DictionaryKanjiPrintService.class, DictionaryService.class,
        HandwritingRateLimiter.class, AzureTtsService.class, DictionaryEngagementService.class,
        ContentAdminService.class, LessonImportService.class, QuickLookupAllowance.class,
        GrammarDictionaryService.class, DailyDictionaryWordsService.class,
        UserDetailsService.class, SanctionStatusService.class})
class DictionaryGuestSecurityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }

    @Autowired MockMvc mvc;
    @Autowired DictionaryService dictionaryService;
    @Autowired HandwritingRateLimiter handwritingRateLimiter;

    @Test
    void anonymousCanOpenLookupPage() throws Exception {
        mvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/dictionary/index.html"));
    }

    @Test
    void anonymousCanSearch() throws Exception {
        mvc.perform(get("/api/dictionary/search").param("q", "日本語"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        verify(dictionaryService).search("日本語", 0, 20, null);
    }

    @Test
    void anonymousCanLoadDictionaryStaticPage() throws Exception {
        mvc.perform(get("/dictionary/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void notebookPageStillRequiresLogin() throws Exception {
        mvc.perform(get("/dictionary/words.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/login?redirect=*"));
    }

    @Test
    void anonymousCanReachHandwritingValidation() throws Exception {
        mvc.perform(post("/api/dictionary/handwriting/recognize"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anonymousHandwritingUsesRemoteAddressLimit() throws Exception {
        mvc.perform(post("/api/dictionary/handwriting/recognize")
                        .with(request -> { request.setRemoteAddr("198.51.100.7"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":100,\"height\":100,\"strokes\":[[[1,2],[3,4]]]}"))
                .andExpect(status().isOk());
        verify(handwritingRateLimiter).check("ip:198.51.100.7");
    }
}
