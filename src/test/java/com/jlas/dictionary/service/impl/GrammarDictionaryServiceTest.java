package com.jlas.dictionary.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.ApiException;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.entity.JpathGrammar;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.repository.JpathExampleRepository;
import com.jlas.jlptpath.repository.JpathGrammarRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GrammarDictionaryServiceTest {
    @Mock JpathGrammarRepository grammars;
    @Mock JpathExampleRepository examples;
    @Mock GrammarPointRepository textbookGrammar;

    private GrammarDictionaryService service() {
        return new GrammarDictionaryService(grammars, examples, textbookGrammar, new ObjectMapper());
    }

    @Test
    void detailMapsVisibleExamplesRelatedAndSpacedTextbookPattern() {
        var grammar = JpathGrammar.builder().code("JG-main").pattern("〜ために").familyKey("ために")
                .level("N4").meaningVi("để làm gì").status(JpathContentStatus.HOLD)
                .patternVariantsJson("[\"〜ためには\"]").relatedCodesJson("[\"JG-related\",\"JG-hidden\"]").build();
        var related = JpathGrammar.builder().code("JG-related").pattern("〜ように").level("N4")
                .status(JpathContentStatus.REVIEWED).build();
        var hidden = JpathGrammar.builder().code("JG-hidden").status(JpathContentStatus.DRAFT).build();
        var example = JpathExample.builder().ja("車を買うために").jaFurigana("車(くるま)を買(か)うために")
                .vi("Để mua xe").audioPath("learning-path/N4/example.mp3").status(JpathContentStatus.HOLD).build();
        var book = Textbook.builder().id(3L).title("Minna").jlptLevel("N4").status("PUBLISHED").build();
        var unit = Unit.builder().textbook(book).build();
        var lesson = Lesson.builder().id(7L).unit(unit).title("Bài 1").orderIndex(1).status("PUBLISHED").build();
        var valid = GrammarPoint.builder().lesson(lesson).pattern("〜た め に").meaning("để\nchỉ mục đích")
                .example("車を買うために | Để mua xe").orderIndex(1).build();
        var corrupt = GrammarPoint.builder().lesson(lesson).pattern("〜ために。Đây là câu giải thích dài")
                .meaning("bad").build();
        when(grammars.findById("JG-main")).thenReturn(Optional.of(grammar));
        when(grammars.findById("JG-related")).thenReturn(Optional.of(related));
        when(grammars.findById("JG-hidden")).thenReturn(Optional.of(hidden));
        when(examples.findByOwnerTypeAndOwnerCodeAndStatusInAndReportHiddenFalseOrderBySortOrder(
                eq(JpathItemType.GRAMMAR), eq("JG-main"), anyList())).thenReturn(List.of(example));
        when(textbookGrammar.findPatternCandidates("%た%め%に%")).thenReturn(List.of(corrupt, valid));

        var detail = service().detail("JG-main");

        assertThat(detail.unreviewed()).isTrue();
        assertThat(detail.variants()).containsExactly("〜ためには");
        assertThat(detail.examples()).singleElement().satisfies(mapped -> {
            assertThat(mapped.tokens()).hasSize(4);
            assertThat(mapped.audioUrl()).isEqualTo("/uploads/content-audio/learning-path/N4/example.mp3");
        });
        assertThat(detail.related()).extracting(GrammarDictionaryService.Summary::code).containsExactly("JG-related");
        assertThat(detail.textbookOccurrences()).singleElement().satisfies(mapped -> {
            assertThat(mapped.url()).isEqualTo("/textbooks/3/lessons/7");
            assertThat(mapped.meaning()).isEqualTo("để");
            assertThat(mapped.explanation()).isEqualTo("chỉ mục đích");
            assertThat(mapped.exampleVi()).isEqualTo("Để mua xe");
        });
    }

    @Test
    void unknownOrHiddenGrammarIsNotFound() {
        var draft = JpathGrammar.builder().code("JG-draft").status(JpathContentStatus.DRAFT).build();
        var hidden = JpathGrammar.builder().code("JG-hidden").status(JpathContentStatus.REVIEWED)
                .reportHidden(true).build();
        when(grammars.findById("JG-draft")).thenReturn(Optional.of(draft));
        when(grammars.findById("JG-hidden")).thenReturn(Optional.of(hidden));
        assertThatThrownBy(() -> service().detail("JG-draft")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service().detail("JG-hidden")).isInstanceOf(ApiException.class);
        verifyNoInteractions(examples, textbookGrammar);
    }
}
