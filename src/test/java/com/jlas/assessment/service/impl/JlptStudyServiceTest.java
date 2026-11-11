package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptExamResponse.*;
import com.jlas.assessment.service.JlptService;
import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JlptStudyServiceTest {
    final JlptService exams = mock(JlptService.class);
    final VocabularyEntryRepository vocabulary = mock(VocabularyEntryRepository.class);
    final VocabularySenseRepository senses = mock(VocabularySenseRepository.class);
    final VocabularyGlossRepository glosses = mock(VocabularyGlossRepository.class);
    final GrammarPointRepository grammar = mock(GrammarPointRepository.class);
    final JlptCuratedStudy curated = mock(JlptCuratedStudy.class);
    final JlptStudyService service = new JlptStudyService(exams, vocabulary, senses, glosses, grammar, curated);
    Question q(long id, String type, String text, String html, Integer correct, String... options) {
        var choices = java.util.stream.IntStream.range(0, options.length).mapToObj(i -> new Option(i+1,options[i],null,null)).toList();
        return new Question(id,(int)id,text,null,html,type,correct,null,null,null,null,null,null,choices,List.of(),null);
    }
    void exam(Question... questions) {
        when(exams.getExam("N3_2023_12")).thenReturn(new JlptExamResponse("N3_2023_12","JLPT N3 12/2023","N3",2023,12,"PUBLISHED",
                List.of(new Part(1,"vocabulary","Từ vựng",30,List.of(new Mondai(1,1,"",null,null,
                        java.util.Arrays.stream(questions).filter(q -> !q.questionType().startsWith("GRAMMAR")).toList(),List.of()))),
                        new Part(2,"grammar_reading","Ngữ pháp",70,List.of(new Mondai(2,1,"",null,null,
                                java.util.Arrays.stream(questions).filter(q -> q.questionType().startsWith("GRAMMAR")).toList(),List.of()))))));
    }
    @Test void deduplicatesInflectedVocabularyAndKeepsOriginalQuestionLinks() {
        exam(q(1,"KANJI_READING","本を調べました。","本を<u>調べる</u>。",1,"しらべる","しらへる"),
                q(2,"CONTEXT","調べる",null,1,"調べる","読む"));
        var word = VocabularyEntry.builder().id(10L).expression("調べる").normalizedExpression("しらべる")
                .reading("しらべる").meaning("tra cứu").exampleJa("本を調べる。").dictionaryStatus("PUBLISHED").build();
        when(vocabulary.findByNormalizedExpressionInAndDictionaryStatus(anyCollection(),eq("PUBLISHED"))).thenReturn(List.of(word));
        var result = service.list("N3_2023_12");
        var item = result.vocabulary().stream().filter(x -> "調べる".equals(x.title())).findFirst().orElseThrow();
        assertThat(item.meaning()).isEqualTo("tra cứu");
        assertThat(item.asked()).isTrue();
        assertThat(item.sources()).extracting(s -> s.questionId()).containsExactly(1L,2L);
        assertThat(result.vocabulary()).filteredOn(x -> "調べる".equals(x.title())).hasSize(1);
        assertThat(result.vocabulary()).noneMatch(x -> "しらへる".equals(x.title()));
    }
    @Test void unknownTargetsNeverGetInventedMeaningsAndGrammarAnswersComeFromKeys() {
        exam(q(1,"CONTEXT","未知語",null,1,"未知語","語"),q(2,"GRAMMAR_FORM","国（　）違います。",null,2,"から","によって","ときに"));
        var g = GrammarPoint.builder().id(11L).pattern("N + によって").meaning("Tuỳ theo").example("国によって違います。").note("Nêu sự khác nhau").build();
        when(grammar.findByLesson_StatusOrderByIdAsc("PUBLISHED")).thenReturn(List.of(g));
        var result = service.list("N3_2023_12");
        assertThat(result.vocabulary()).singleElement().satisfies(x -> {assertThat(x.title()).isEqualTo("未知語");assertThat(x.meaning()).isNull();});
        assertThat(result.grammar()).filteredOn(x -> x.correctAnswer()).singleElement().satisfies(x -> {
            assertThat(x.title()).isEqualTo("N + によって");assertThat(x.meaning()).isEqualTo("Tuỳ theo");
            assertThat(x.sources().getFirst().surface()).isEqualTo("によって");
        });
        assertThat(result.grammar()).filteredOn(x -> "から".equals(x.title())).singleElement().satisfies(x -> assertThat(x.meaning()).isNull());
        verify(grammar).findByLesson_StatusOrderByIdAsc("PUBLISHED");
    }
    @Test void extractsLowercaseGrammarTypesFromCombinedVocabularyPart() {
        exam(q(1,"grammar_form","国（　）違います。",null,2,"から","によって"),
                q(2,"text_grammar","（　）続けました。",null,1,"それでも","たとえば"));

        var result = service.list("N3_2023_12");

        assertThat(result.vocabulary()).isEmpty();
        assertThat(result.grammar()).filteredOn(x -> x.correctAnswer())
                .extracting(x -> x.title()).containsExactly("によって", "それでも");
        assertThat(result.grammar()).allSatisfy(x ->
                assertThat(x.sources()).singleElement().satisfies(source ->
                        assertThat(source.part()).isEqualTo("vocabulary")));
    }
    @Test void separatesN4PdfGrammarFromReadingWhenBothUseMultipleChoice() {
        when(exams.getExam("N4_2018")).thenReturn(new JlptExamResponse("N4_2018", "JLPT N4 2018", "N4", 2018, null, "DRAFT",
                List.of(new Part(1, "grammar_reading", "Ngữ pháp & Đọc hiểu", 60, List.of(
                        new Mondai(1, 1, "MULTIPLE_CHOICE", null, null,
                                List.of(q(1,"MULTIPLE_CHOICE","牛乳（　）作ります。",null,1,"から","まで")), List.of()),
                        new Mondai(2, 2, "MULTIPLE_CHOICE", null, null,
                                List.of(q(2,"MULTIPLE_CHOICE","★に入る言葉",null,1,"図書館")), List.of()),
                        new Mondai(3, 3, "MULTIPLE_CHOICE", null, null,
                                List.of(q(3,"MULTIPLE_CHOICE","（　）続けました。",null,1,"それでも","だから")), List.of()),
                        new Mondai(4, 4, "MULTIPLE_CHOICE", null, null,
                                List.of(q(4,"MULTIPLE_CHOICE","筆者はどう思いますか。",null,1,"駅に行きます")), List.of()))))));

        var result = service.list("N4_2018");

        assertThat(result.grammar()).extracting(x -> x.title()).containsExactly("から", "まで", "それでも", "だから");
    }
    @Test void stripsRubyReadingsAndRejectsRegexOrPartialGrammarMatches() {
        assertThat(JlptStudyService.plain("<ruby>食<rt>た</rt></ruby>べる<script>bad()</script>")).isEqualTo("食べる");
        assertThat(JlptStudyService.matches("N + によって","によって")).isTrue();
        assertThat(JlptStudyService.matches("〜ように、〜ような","ような")).isTrue();
        assertThat(JlptStudyService.matches("N + によって","によっては")).isFalse();
        assertThat(JlptStudyService.matches(".*","何でも")).isFalse();
    }
    @Test void unknownExamPropagates404AndDoesNotLoadTheDictionary() {
        when(exams.getExam("N3_2023_12")).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.list("N3_2023_12")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verifyNoInteractions(vocabulary,grammar);
    }
    @Test void curatedN3KeepsExactSourceCountsMeaningsAndAnswerFlags() {
        var saved = JlptCuratedStudyTest.withoutSavedLists().find("N3_2023_12").orElseThrow();
        exam(q(1,"PARAPHRASE","彼が最も速い。",null,1,"最も","もっと"),
                q(2,"GRAMMAR_FORM",saved.path("grammar").path(0).path("question").path("text").asText(),null,1,"うちに","ときに"));
        when(curated.find("N3_2023_12")).thenReturn(java.util.Optional.of(saved));
        var result = service.list("N3_2023_12");
        assertThat(result.vocabulary()).hasSize(85);
        assertThat(result.vocabulary()).filteredOn(x -> x.asked()).hasSize(40);
        assertThat(result.grammar()).hasSize(19);
        assertThat(result.grammar()).filteredOn(x -> x.correctAnswer()).hasSize(5);
        assertThat(result.vocabulary().getFirst()).satisfies(x -> {
            assertThat(x.title()).isEqualTo("最も"); assertThat(x.reading()).isEqualTo("もっとも");
            assertThat(x.meaning()).isEqualTo("nhất; cực kỳ"); assertThat(x.hanViet()).isEqualTo("TỐI");
            assertThat(x.exampleVi()).isEqualTo("Anh ấy nhanh nhất.");assertThat(x.sources().getFirst().questionId()).isEqualTo(1);
        });
        assertThat(result.grammar().getFirst().sources().getFirst().questionId()).isEqualTo(2);
        verifyNoInteractions(vocabulary,senses,glosses);
    }
}
