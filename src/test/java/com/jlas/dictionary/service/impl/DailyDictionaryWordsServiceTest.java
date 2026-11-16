package com.jlas.dictionary.service.impl;

import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.UserLearningSurveyRepository;
import com.jlas.dictionary.entity.DictionarySearchHistory;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.DictionarySearchHistoryRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.jlptpath.entity.JpathUserPath;
import com.jlas.jlptpath.enums.JpathPathStatus;
import com.jlas.jlptpath.repository.JpathUserPathRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyDictionaryWordsServiceTest {
    @Mock JpathUserPathRepository paths;
    @Mock UserLearningSurveyRepository surveys;
    @Mock DictionarySearchHistoryRepository history;
    @Mock VocabularyEntryRepository vocabulary;

    private final LocalDate today = LocalDate.of(2026, 11, 15);

    private DailyDictionaryWordsService service() {
        return new DailyDictionaryWordsService(paths, surveys, history, vocabulary);
    }

    @Test
    void japaneseOnlyQueryFilterAcceptsJapaneseScriptsAndRejectsOtherText() {
        assertThat(DailyDictionaryWordsService.isJapaneseOnly("食べるカタカナー々〆ヶ")).isTrue();
        assertThat(DailyDictionaryWordsService.isJapaneseOnly("きょう")).isTrue();
        for (String query : List.of("", "日本語 abc", "ベトナム1", "xin chào", "カタカナ・", "漢字。")) {
            assertThat(DailyDictionaryWordsService.isJapaneseOnly(query)).as(query).isFalse();
        }
        assertThat(DailyDictionaryWordsService.isJapaneseOnly(null)).isFalse();
    }

    @Test
    void targetResolutionPrefersActivePathThenSurveyThenNone() {
        when(paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(7L, JpathPathStatus.ACTIVE))
                .thenReturn(Optional.of(JpathUserPath.builder().targetLevel("n3").build()), Optional.empty(),
                        Optional.of(JpathUserPath.builder().targetLevel("CUSTOM").build()), Optional.empty());
        when(surveys.findById(7L)).thenReturn(Optional.of(UserLearningSurvey.builder().targetLevel(" n4 ").build()),
                Optional.of(UserLearningSurvey.builder().targetLevel("OTHER").build()), Optional.empty());
        assertThat(service().resolveTargetLevel(7L)).isEqualTo("N3");
        assertThat(service().resolveTargetLevel(7L)).isEqualTo("N4");
        assertThat(service().resolveTargetLevel(7L)).isNull();
        assertThat(service().resolveTargetLevel(7L)).isNull();
        verify(surveys, org.mockito.Mockito.times(3)).findById(7L);
    }

    @Test
    void targetLevelReturnsFiveStableWordsWithoutHistoryLookup() {
        when(paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(7L, JpathPathStatus.ACTIVE))
                .thenReturn(Optional.of(JpathUserPath.builder().targetLevel("N3").build()));
        when(vocabulary.findDailyWordsForLevel(eq("N3"), anyLong())).thenReturn(List.of(
                word(1L, "食べる", "たべる", "ăn; ăn uống"), word(2L, "見る", "みる", "nhìn"),
                word(3L, "学校", "がっこう", "trường"), word(4L, "朝", "あさ", "sáng"),
                word(5L, "夜", "よる", "đêm")));
        var result = service().dailyWords(7L, today);
        assertThat(result.source()).isEqualTo("TARGET_LEVEL");
        assertThat(result.level()).isEqualTo("N3");
        assertThat(result.words()).hasSize(5);
        assertThat(result.words().getFirst().meaning()).isEqualTo("ăn");
        service().dailyWords(7L, today);
        service().dailyWords(7L, today.plusDays(1));
        ArgumentCaptor<Long> seeds = ArgumentCaptor.forClass(Long.class);
        verify(vocabulary, org.mockito.Mockito.times(3)).findDailyWordsForLevel(eq("N3"), seeds.capture());
        assertThat(seeds.getAllValues().get(0)).isEqualTo(seeds.getAllValues().get(1));
        assertThat(seeds.getAllValues().get(2)).isNotEqualTo(seeds.getAllValues().get(0));
        verifyNoInteractions(history);
    }

    @Test
    void historyUsesVisibleSelectedEntryThenExactLookupAndDeduplicates() {
        when(paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(7L, JpathPathStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        var selected = word(10L, "日本", "にほん", "Nhật Bản");
        var fallback = word(11L, "学校", "がっこう", "trường học");
        var hidden = word(12L, "学校", "がっこう", "ẩn");
        var englishOnly = word(13L, "最悪", "さいあく", "worst of both worlds");
        hidden.setUsageScope("TEXTBOOK");
        when(history.findByUser_IdOrderBySearchedAtDescIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(search("日本", selected), search("学校", hidden), search("学校", null),
                        search("最悪", englishOnly),
                        search("nihon", selected), search("xin chào", null),
                        DictionarySearchHistory.builder().normalizedQuery("日本").searchMode("ROMAJI")
                                .selectedVocabulary(word(99L, "余分", "よぶん", "extra")).build()));
        when(vocabulary.findLookupByNormalizedExpressionInAndDictionaryStatusOrderByIdAsc(anyCollection(), eq("PUBLISHED")))
                .thenReturn(List.of(fallback));
        when(vocabulary.findLookupByNormalizedReadingInAndDictionaryStatusOrderByIdAsc(anyCollection()))
                .thenReturn(List.of());
        when(vocabulary.findByIdIn(anyList())).thenReturn(List.of(selected, hidden, englishOnly));
        var result = service().dailyWords(7L, today);
        assertThat(result.source()).isEqualTo("HISTORY");
        assertThat(result.level()).isNull();
        assertThat(result.words()).extracting(DailyDictionaryWordsService.Word::id).containsExactlyInAnyOrder(10L, 11L);
        verify(vocabulary).findByIdIn(anyList());
    }

    @Test
    void vietnameseMeaningFilterSkipsEnglishAndDisplaysVietnameseGloss() {
        assertThat(DailyDictionaryWordsService.hasVietnameseMeaning("worst of both worlds")).isFalse();
        assertThat(DailyDictionaryWordsService.hasVietnameseMeaning("ĐẸP")).isTrue();
        assertThat(DailyDictionaryWordsService.hasVietnameseMeaning(null)).isFalse();
        when(paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(7L, JpathPathStatus.ACTIVE))
                .thenReturn(Optional.of(JpathUserPath.builder().targetLevel("N3").build()));
        when(vocabulary.findDailyWordsForLevel(eq("N3"), anyLong())).thenReturn(List.of(
                word(14L, "最悪", "さいあく", "worst of both worlds; tình huống khó xử"),
                word(15L, "失敗", "しっぱい", "failure")));
        assertThat(service().dailyWords(7L, today).words())
                .extracting(DailyDictionaryWordsService.Word::meaning).containsExactly("tình huống khó xử");
    }

    @Test
    void guestAndUnresolvedHistoryReturnNone() {
        assertThat(service().dailyWords(null, today).source()).isEqualTo("NONE");
        verifyNoInteractions(paths, surveys, history, vocabulary);
        when(paths.findFirstByUserIdAndStatusAndArchivedAtIsNullOrderByIdDesc(7L, JpathPathStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        when(history.findByUser_IdOrderBySearchedAtDescIdDesc(eq(7L), any(Pageable.class)))
                .thenReturn(List.of(search("romaji", null)));
        var result = service().dailyWords(7L, today);
        assertThat(result.source()).isEqualTo("NONE");
        assertThat(result.words()).isEmpty();
        verifyNoInteractions(vocabulary);
    }

    private static VocabularyEntry word(Long id, String expression, String reading, String meaning) {
        return VocabularyEntry.builder().id(id).expression(expression).normalizedExpression(expression)
                .reading(reading).normalizedReading(reading).meaning(meaning).jlptLevel("N3")
                .dictionaryStatus("PUBLISHED").usageScope("DICTIONARY").build();
    }

    private static DictionarySearchHistory search(String query, VocabularyEntry selected) {
        return DictionarySearchHistory.builder().normalizedQuery(query).queryText(query)
                .selectedVocabulary(selected).build();
    }
}
