package com.jlas.dictionary.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.HandwritingRequest;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.handwriting.KanjiStrokeIndex;
import com.jlas.dictionary.handwriting.StrokeMatcher;
import com.jlas.dictionary.handwriting.TestDrawings;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceImplHandwritingTest {

    private static final int CANVAS = 360;
    private static final List<StrokeMatcher.Ref> INDEX = TestDrawings.index("日作休体本木末未三二一川土士目白語");

    @Mock KanjiStrokeIndex kanjiStrokeIndex;
    @Mock KanjiRepository kanjiRepository;
    @Mock KanjiReadingRepository kanjiReadingRepository;
    @Spy DictionaryMapper mapper = new DictionaryMapper();
    @InjectMocks DictionaryServiceImpl service;

    @BeforeEach
    void stubIndex() {
        org.mockito.Mockito.lenient().when(kanjiStrokeIndex.refs()).thenReturn(INDEX);
    }

    /** Nét chuẩn (khung 109) đổi sang toạ độ pixel của canvas 360x360 như FE gửi lên. */
    private static HandwritingRequest requestFor(char kanji) {
        List<List<double[]>> strokes = new ArrayList<>();
        for (List<double[]> stroke : TestDrawings.canonical(kanji)) {
            List<double[]> points = new ArrayList<>();
            for (double[] p : stroke) {
                points.add(new double[]{p[0] * CANVAS / StrokeMatcher.FRAME, p[1] * CANVAS / StrokeMatcher.FRAME});
            }
            strokes.add(points);
        }
        HandwritingRequest request = new HandwritingRequest();
        request.setWidth(CANVAS);
        request.setHeight(CANVAS);
        request.setStrokes(strokes);
        return request;
    }

    private void stubKanjiLookup() {
        when(kanjiRepository.findByCharacterIn(anyList())).thenAnswer(invocation -> {
            List<Kanji> found = new ArrayList<>();
            long id = 1;
            for (Object c : (List<?>) invocation.getArgument(0)) {
                found.add(Kanji.builder().id(id++).character((String) c).strokeCount((byte) 4).build());
            }
            return found;
        });
    }

    @Test
    void scalesCanvasPixelsToKanjiVgSpaceAndReturnsBestCandidateFirst() {
        stubKanjiLookup();

        List<KanjiResponse> candidates = service.recognizeHandwriting(requestFor('日'));

        assertEquals("日", candidates.get(0).getCharacter());
        assertEquals(5, candidates.size());
    }

    @Test
    void keepsRankingOrderEvenIfTheDatabaseReturnsRowsInAnotherOrder() {
        when(kanjiRepository.findByCharacterIn(anyList())).thenAnswer(invocation -> {
            List<String> asked = new ArrayList<>(invocation.getArgument(0));
            java.util.Collections.reverse(asked);
            return asked.stream().map(c -> Kanji.builder().id((long) c.codePointAt(0)).character(c).build()).toList();
        });

        List<KanjiResponse> candidates = service.recognizeHandwriting(requestFor('作'));

        assertEquals("作", candidates.get(0).getCharacter());
    }

    @Test
    void skipsCandidatesMissingFromTheKanjiTable() {
        when(kanjiRepository.findByCharacterIn(anyList())).thenReturn(List.of());

        assertTrue(service.recognizeHandwriting(requestFor('日')).isEmpty());
    }

    @Test
    void noMatchingReferenceMeansNoCandidatesAndNoDatabaseLookup() {
        when(kanjiStrokeIndex.refs()).thenReturn(List.of());

        assertTrue(service.recognizeHandwriting(requestFor('日')).isEmpty());
        verify(kanjiRepository, never()).findByCharacterIn(anyList());
    }

    @Test
    void rejectsPointsOutsideTheCanvas() {
        HandwritingRequest request = requestFor('日');
        request.getStrokes().get(0).set(0, new double[]{CANVAS + 1, 10});

        ApiException error = assertThrows(ApiException.class, () -> service.recognizeHandwriting(request));

        assertEquals(ErrorCode.VALIDATION_FAILED, error.getErrorCode());
        verify(kanjiRepository, never()).findByCharacterIn(anyList());
    }

    @Test
    void rejectsNegativeCoordinates() {
        HandwritingRequest request = requestFor('日');
        request.getStrokes().get(0).set(0, new double[]{-1, 10});

        assertThrows(ApiException.class, () -> service.recognizeHandwriting(request));
    }
}
