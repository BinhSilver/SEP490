package com.jlas.dictionary.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.jlptpath.util.JpathStrokeSvgIndex;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DictionaryKanjiPrintServiceTest {
    @Mock KanjiRepository kanjis;
    @Mock KanjiReadingRepository readings;
    @Mock VocabularyKanjiRepository vocabulary;
    @Mock JpathStrokeSvgIndex svgIndex;

    private DictionaryKanjiPrintService service() {
        return new DictionaryKanjiPrintService(kanjis, readings, vocabulary, svgIndex);
    }

    @Test
    void buildsOneA4PageWithoutRepeatingTheKanjiOrVocabularyExpression() {
        when(kanjis.findByCharacter("田")).thenReturn(Optional.of(Kanji.builder().id(7L).character("田")
                .hanViet("điền").meaningVi("ruộng").strokeCount((byte) 5).strokeSvgUri("/fallback.svg").build()));
        when(readings.findByKanji_IdOrderByReadingTypeAscReadingTextAsc(7L)).thenReturn(List.of(
                reading("KUN", "た"), reading("NANORI", "だ"), reading("ON", "デン")));
        when(svgIndex.exists("田".codePointAt(0))).thenReturn(true);
        when(vocabulary.findPrintableVocabularyByKanjiCharacter("田", PageRequest.of(0, 6))).thenReturn(List.of(
                word("田", "ruộng", "PUBLISHED", "DICTIONARY"),
                word("田ぼ", "ruộng", "PUBLISHED", "BOTH"),
                word("田ぼ", "ruộng lúa", "PUBLISHED", "DICTIONARY"),
                word("水田", "ruộng nước", "PUBLISHED", "DICTIONARY"),
                word("田園", "fields; đồng quê", "PUBLISHED", "DICTIONARY"),
                word("田中", "trong ruộng", "PUBLISHED", "BOTH")));

        var sheet = service().sheet("田");

        assertThat(sheet.title()).isEqualTo("Luyện viết 田");
        assertThat(sheet.dateVi()).matches("\\d{2}/\\d{2}/\\d{4}");
        assertThat(sheet.pages()).hasSize(1);
        var rows = sheet.pages().getFirst();
        assertThat(rows).hasSize(6);
        assertThat(rows).extracting(row -> row.kind() + ":" + row.text())
                .containsExactly("KANJI:田", "KANJI:田", "KANJI:田", "VOCAB:田ぼ", "VOCAB:水田", "VOCAB:田園");
        assertThat(rows.getFirst().hanViet()).isEqualTo("ĐIỀN");
        assertThat(rows.getFirst().reading()).isEqualTo("デン・た");
        assertThat(rows.getFirst().meaning()).isEqualTo("ruộng");
        assertThat(rows.getFirst().strokeSvgUrl()).isEqualTo("/assets/kanjivg/kanji/07530.svg");
        assertThat(rows.getFirst().strokeCount()).isEqualTo(5);
        assertThat(rows.getFirst().cells().units()).isEqualTo(1);
        assertThat(rows.get(4).cells().units()).isEqualTo(2);
        assertThat(rows.get(4).strokeSvgUrl()).isNull();
        assertThat(rows.get(5).meaning()).isEqualTo("đồng quê");
        verify(vocabulary).findPrintableVocabularyByKanjiCharacter("田", PageRequest.of(0, 6));
    }

    @Test
    void excludesWordsOutsideDictionaryLookupAndFallsBackToStoredSvg() {
        when(kanjis.findByCharacter("田")).thenReturn(Optional.of(Kanji.builder().id(7L).character("田")
                .strokeSvgUri("/fallback.svg").build()));
        when(readings.findByKanji_IdOrderByReadingTypeAscReadingTextAsc(7L)).thenReturn(List.of());
        when(vocabulary.findPrintableVocabularyByKanjiCharacter("田", PageRequest.of(0, 6))).thenReturn(List.of(
                word("田中", "field", "PUBLISHED", "DICTIONARY"),
                word("田畑", "ruộng vườn", "DRAFT", "DICTIONARY"),
                word("田家", "nhà nông", "PUBLISHED", "TEXTBOOK"),
                word("田地", "đất ruộng", "PUBLISHED", "DICTIONARY")));

        var rows = service().sheet("田").pages().getFirst();

        assertThat(rows).hasSize(4);
        assertThat(rows.getFirst().strokeSvgUrl()).isEqualTo("/fallback.svg");
        assertThat(rows.get(3).text()).isEqualTo("田地");
    }

    @Test
    void rejectsInvalidOrUnknownCharacter() {
        assertThatThrownBy(() -> service().sheet("田中")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().sheet("a")).isInstanceOf(ApiException.class);
        verifyNoInteractions(kanjis, readings, vocabulary, svgIndex);

        when(kanjis.findByCharacter("田")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().sheet("田")).isInstanceOf(ApiException.class);
        verify(kanjis).findByCharacter("田");
        verifyNoInteractions(readings, vocabulary, svgIndex);
    }

    private static KanjiReading reading(String type, String text) {
        return KanjiReading.builder().readingType(type).readingText(text).build();
    }

    private static VocabularyEntry word(String expression, String meaning, String status, String scope) {
        return VocabularyEntry.builder().expression(expression).reading("でん")
                .meaning(meaning).dictionaryStatus(status).usageScope(scope).build();
    }
}
