package com.jlas.learning.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jlas.assessment.entity.AssessmentQuestion;
import com.jlas.dictionary.entity.Outcome;
import com.jlas.assessment.repository.AssessmentQuestionRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Owner decision 2026-10-06 (option c): reading passages keep their paragraph breaks on screen, while the
 * flattened text that feeds the exercise fingerprint/revision stays byte-identical, so in-progress Reading
 * activities are not reported as "source changed". */
class JpaComprehensionExerciseCatalogDisplayTest {
    private static final String HTML = "<p class=\"q\">春です。</p><p>花見をします。<br>&#12372;みに注意。</p>";

    @Test
    void displayKeepsParagraphsWhileTheFingerprintSourceStaysFlat() {
        var repo = mock(AssessmentQuestionRepository.class);
        when(repo.findByIsActiveTrueAndSkillAndJlptLevelOrderByIdAsc("READING", "N4"))
                .thenReturn(List.of(question(1L), question(2L)));

        var exercise = new JpaComprehensionExerciseCatalog(repo).resolve("READING", "N4").exercises().getFirst();

        assertEquals("春です。 花見をします。 ごみに注意。", exercise.source(), "fingerprint source must not change");
        assertEquals("春です。\n花見をします。\nごみに注意。", exercise.displaySource());
    }

    private static AssessmentQuestion question(Long id) {
        return AssessmentQuestion.builder().id(id).skill("READING").jlptLevel("N4").isActive(true)
                .outcome(Outcome.builder().code("OUT-1").build()).passageHtml(HTML).questionType("MULTIPLE_CHOICE")
                .questionText("Q" + id).option1("A").option2("B").option3("C").correctOption(1).explanation("E").build();
    }
}
