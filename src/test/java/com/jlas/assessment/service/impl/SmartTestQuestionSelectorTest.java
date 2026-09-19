package com.jlas.assessment.service.impl;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.dictionary.entity.Tag;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class SmartTestQuestionSelectorTest {
    @Test
    void prioritizesGoalButRandomizesWhichQuestionsArePicked() {
        List<AssessmentQuestion> pool = new ArrayList<>();
        for (long id = 1; id <= 40; id++) pool.add(question(id, id <= 20 ? "BA" : "JLPT", "word" + id));
        Set<List<Long>> outcomes = new HashSet<>();
        for (int seed = 0; seed < 20; seed++) {
            var batch = SmartTestQuestionSelector.select(pool, List.of(), 5, List.of("BA"), new Random(seed));
            assertThat(batch).hasSize(5).allMatch(q -> "BA".equals(q.getKnowledgeTag().getCode()));
            outcomes.add(batch.stream().map(AssessmentQuestion::getId).toList());
        }
        assertThat(outcomes.size()).isGreaterThan(1);
        assertThat(pool.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void fallsBackWhenGoalPoolHasTooFewDistinctWords() {
        var batch = SmartTestQuestionSelector.select(List.of(
                question(1, "BA", "確認"), question(2, "BA", "確認"),
                question(3, "BA", "説明"), question(4, "JLPT", "試験"),
                question(5, "TRAVEL", "旅行"), question(6, "DAILY_LIFE", "買物")),
                List.of(), 5, List.of("BA"), new Random(1));
        assertThat(batch).hasSize(5);
        assertThat(batch.subList(0, 2)).allMatch(q -> "BA".equals(q.getKnowledgeTag().getCode()));
        assertThat(batch.stream().map(SmartTestQuestionSelector::kanjiWord)).doesNotHaveDuplicates();
    }

    @Test
    void excludesEarlierWordEvenWithDifferentIdOrLevel() {
        var old = question(1, "BA", "確認");
        var repeated = question(2, "BA", "確認");
        repeated.setJlptLevel("N2");
        var fresh = question(3, "BA", "説明");
        assertThat(SmartTestQuestionSelector.select(List.of(old, repeated, fresh), List.of(old),
                5, List.of("BA"), new Random(1))).containsExactly(fresh);
    }

    @Test
    void recognizesHistoricalRowsWithoutLexeme() {
        var old = question(1, "BA", "確認");
        old.setContextJson("{}");
        old.setExplanation("確認（かくにん）nghĩa là xác nhận.");
        assertThat(SmartTestQuestionSelector.kanjiWord(old)).isEqualTo("確認");
        old.setExplanation("確認 đọc là かくにん, nghĩa là xác nhận.");
        assertThat(SmartTestQuestionSelector.kanjiWord(old)).isEqualTo("確認");
    }

    @Test
    void duplicateLexemeDoesNotExcludeOtherSkills() {
        var a = question(1, "JLPT", "確認"); var b = question(2, "JLPT", "確認");
        a.setSkill("VOCABULARY"); b.setSkill("VOCABULARY");
        assertThat(SmartTestQuestionSelector.select(List.of(a, b), List.of(), 5,
                List.of("JLPT"), new Random(1))).containsExactlyInAnyOrder(a, b);
    }

    private AssessmentQuestion question(long id, String tag, String word) {
        return AssessmentQuestion.builder().id(id).skill("KANJI").jlptLevel("N3")
                .contextJson("{\"lexeme\":\"" + word + "\"}")
                .knowledgeTag(Tag.builder().code(tag).name(tag).tagType("SITUATION").build()).build();
    }
}
