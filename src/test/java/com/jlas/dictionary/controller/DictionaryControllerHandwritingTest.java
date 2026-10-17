package com.jlas.dictionary.controller;

import com.jlas.common.GlobalApiExceptionHandler;
import com.jlas.dictionary.dto.HandwritingRequest;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.handwriting.HandwritingRateLimiter;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.DictionaryService;
import com.jlas.dictionary.service.DictionaryEngagementService;
import com.jlas.dictionary.service.LessonImportService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kiểm tra hợp đồng HTTP + validation của POST /api/dictionary/handwriting/recognize. */
class DictionaryControllerHandwritingTest {

    private static final String URL = "/api/dictionary/handwriting/recognize";

    private DictionaryService service;
    private HandwritingRateLimiter limiter;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DictionaryService.class);
        limiter = mock(HandwritingRateLimiter.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(
                        new DictionaryController(service, limiter, mock(DictionaryEngagementService.class),
                                mock(ContentAdminService.class), mock(LessonImportService.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalApiExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private static String strokes(int strokeCount, int pointsPerStroke) {
        StringBuilder stroke = new StringBuilder("[");
        for (int i = 0; i < pointsPerStroke; i++) stroke.append(i == 0 ? "" : ",").append("[10,20]");
        stroke.append("]");
        StringBuilder all = new StringBuilder("[");
        for (int i = 0; i < strokeCount; i++) all.append(i == 0 ? "" : ",").append(stroke);
        return all.append("]").toString();
    }

    @Test
    void validRequestReturnsCandidatesInRankingOrder() throws Exception {
        when(service.recognizeHandwriting(any(HandwritingRequest.class))).thenReturn(List.of(
                KanjiResponse.builder().character("作").strokeCount(7).build(),
                KanjiResponse.builder().character("昨").strokeCount(9).build()));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(2, 3) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].character").value("作"))
                .andExpect(jsonPath("$.data[1].character").value("昨"));
    }

    @Test
    void noCandidateFoundIsAnEmptyListNotAnError() throws Exception {
        when(service.recognizeHandwriting(any(HandwritingRequest.class))).thenReturn(List.of());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(1, 2) + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void emptyStrokesAreRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void strokeWithoutPointsIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":[[]]}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void pointWithWrongArityIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":[[[1,2,3]]]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":[[[1]]]}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void nullPointsAndNullStrokesAreRejectedWith400NotAServerError() throws Exception {
        for (String strokes : new String[]{"[[null]]", "[null]", "[[[1,2],null]]", "null"}) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes + "}"))
                    .andExpect(status().isBadRequest());
        }
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void tooManyStrokesAreRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(31, 2) + "}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void oversizedStrokeIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(1, 501) + "}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void limitsAreInclusive() throws Exception {
        when(service.recognizeHandwriting(any(HandwritingRequest.class))).thenReturn(List.of());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(30, 500) + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidCanvasSizeIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":0,\"height\":360,\"strokes\":" + strokes(1, 2) + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":999999,\"strokes\":" + strokes(1, 2) + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strokes\":" + strokes(1, 2) + "}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).recognizeHandwriting(any());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rateLimitIsCheckedPerAuthenticatedUser() throws Exception {
        UserPrincipal principal = new UserPrincipal(7L, "learner", "x", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(service.recognizeHandwriting(any(HandwritingRequest.class))).thenReturn(List.of());

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(1, 2) + "}"))
                .andExpect(status().isOk());

        verify(limiter).check(7L);
    }

    @Test
    void tooManyRequestsIsReturnedAs429AndRecognitionIsSkipped() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(ErrorCode.TOO_MANY_REQUESTS, "Bạn vẽ quá nhanh."))
                .when(limiter).check(any());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new UserPrincipal(7L, "learner", "x", List.of()), null, List.of()));

        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":" + strokes(1, 2) + "}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        verify(service, never()).recognizeHandwriting(any());
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"width\":360,\"strokes\":[[[\"a\",\"b\"]]]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"width\":360,\"height\":360,\"strokes\":[[[\"a\",\"b\"]]]}"))
                .andExpect(status().isBadRequest());
    }
}
