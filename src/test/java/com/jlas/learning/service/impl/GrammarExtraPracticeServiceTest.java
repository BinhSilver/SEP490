package com.jlas.learning.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.learning.ai.AiJsonGenerator;
import com.jlas.learning.dto.GrammarExtraPractice;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityState;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Owner 2026-10-21: three optional AI practice sentences per pattern; only sentences that really use the pattern. */
class GrammarExtraPracticeServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void keepsOnlyNewSentencesThatUseThePatternAndCutsThemIntoTiles() throws Exception {
        JsonNode list = json.readTree("""
            [{"ja":"おはようございます。パスポートと予約番号をお願いします。","vi":"lặp lại ví dụ"},
             {"ja":"お水を一杯お願いします。","vi":"Cho tôi xin một cốc nước."},
             {"ja":"これは関係ない文です。","vi":"không có mẫu"},
             {"ja":"窓側の席をお願いします。","vi":"Cho tôi xin ghế cạnh cửa sổ."},
             {"ja":"領収書をお願いします。","vi":"Cho tôi xin hóa đơn."},
             {"ja":"毛布をもう一枚お願いします。","vi":"Cho tôi thêm một cái chăn."}]""");

        List<GrammarExtraPractice.Item> items = GrammarExtraPracticeService.accept(list, "〜をお願いします",
                "おはようございます。パスポートと予約番号をお願いします。");

        assertEquals(3, items.size(), "three sentences, the example and the off-pattern one dropped");
        assertEquals("お水を一杯お願いします。", items.get(0).japanese());
        for (var item : items) {
            assertTrue(item.parts().size() >= 3, "tiles: " + item.parts());
            assertEquals(item.japanese(), String.join("", item.parts()));
            assertTrue(item.prompt().contains("_____"));
            assertTrue(item.answer().contains("お願いします"), item.answer());
        }
    }

    @Test void generatesOncePerPatternForAnOwnedUnlockedActivityAndRefusesOthers() throws Exception {
        var items = mock(UserLearningPathItemRepository.class);
        var members = mock(LearningActivityMemberRepository.class);
        var points = mock(GrammarPointRepository.class);
        var ai = mock(AiJsonGenerator.class);
        var activity = UserLearningPathItem.builder().id(1L).activityType(LearningActivityType.GRAMMAR)
                .activityState(LearningActivityState.STUDYING).build();
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(LearningActivityMember.builder()
                .memberType(LearningMemberType.GRAMMAR_POINT).canonicalId(50L).build()));
        GrammarPoint point = new GrammarPoint();
        point.setPattern("〜をお願いします");
        point.setMeaning("Cho tôi xin ~");
        when(points.findById(50L)).thenReturn(Optional.of(point));
        when(ai.generateJson(anyString(), anyString())).thenReturn(json.readTree(
                "{\"items\":[{\"ja\":\"お水を一杯お願いします。\",\"vi\":\"Cho tôi xin một cốc nước.\"}]}"));
        var service = new GrammarExtraPracticeService(items, members, points, ai);

        assertEquals(1, service.generate(7L, 1L, 50L).items().size());
        assertEquals(GrammarExtraPracticeService.LABEL, service.generate(7L, 1L, 50L).label());
        verify(ai, times(1)).generateJson(anyString(), anyString());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> service.generate(7L, 1L, 99L)).getErrorCode());
        assertEquals(ErrorCode.NOT_FOUND, assertThrows(ApiException.class, () -> service.generate(8L, 1L, 50L)).getErrorCode());
    }

    @Test void saysTheAiIsUnavailableInsteadOfReturningAnEmptyList() {
        var items = mock(UserLearningPathItemRepository.class);
        var members = mock(LearningActivityMemberRepository.class);
        var points = mock(GrammarPointRepository.class);
        var activity = UserLearningPathItem.builder().id(1L).activityType(LearningActivityType.GRAMMAR)
                .activityState(LearningActivityState.STUDYING).build();
        when(items.findByIdAndUserId(1L, 7L)).thenReturn(Optional.of(activity));
        when(members.findByActivityIdOrderByOrderIndex(1L)).thenReturn(List.of(LearningActivityMember.builder()
                .memberType(LearningMemberType.GRAMMAR_POINT).canonicalId(50L).build()));
        GrammarPoint point = new GrammarPoint();
        point.setPattern("〜をお願いします");
        when(points.findById(50L)).thenReturn(Optional.of(point));
        var service = new GrammarExtraPracticeService(items, members, points, mock(AiJsonGenerator.class));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, assertThrows(ApiException.class, () -> service.generate(7L, 1L, 50L)).getErrorCode());
    }
}
