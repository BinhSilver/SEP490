package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.entity.JlptExam;
import com.jlas.assessment.entity.JlptExamAttempt;
import com.jlas.assessment.entity.JlptExamSection;
import com.jlas.assessment.entity.JlptSectionResult;
import com.jlas.assessment.repository.JlptExamAttemptRepository;
import com.jlas.auth.entity.User;
import com.jlas.learning.service.JlptQualificationPort.Result;
import com.jlas.assessment.service.impl.AssessmentJlptQualificationAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** [JLAS-W0108] Task 9 — the Learning module only READS a JLPT Mock Test result and trusts nothing the client
 * sends except the attempt id. Fixtures are in-memory test objects, not production exam data. */
class AssessmentJlptQualificationAdapterTest {
    private static final Long USER = 7L;
    private static final Long ATTEMPT = 500L;

    private JlptExamAttemptRepository repo;
    private AssessmentJlptQualificationAdapter adapter;

    @BeforeEach
    void setUp() {
        repo = mock(JlptExamAttemptRepository.class);
        adapter = new AssessmentJlptQualificationAdapter(repo, List.of("JLPT_PATH_GATE_V1"));
    }

    private JlptExamAttempt attempt(Long ownerId, String type, String status, String level, String passing) {
        var user = User.builder().id(ownerId).build();
        var base = AssessmentAttempt.builder().id(ATTEMPT).user(user).attemptType(type).status(status).build();
        var exam = JlptExam.builder().id(1L).jlptLevel(level).passingScore(new BigDecimal(passing))
                .policyVersion("JLPT_PATH_GATE_V1").build();
        return JlptExamAttempt.builder().attemptId(ATTEMPT).attempt(base).exam(exam).policyVersion("JLPT_PATH_GATE_V1").build();
    }

    private JlptSectionResult section(long id, String min, String score) {
        var sec = JlptExamSection.builder().id(id).minPassingScore(new BigDecimal(min)).build();
        return JlptSectionResult.builder().section(sec).score(new BigDecimal(score)).build();
    }

    private void given(JlptExamAttempt attempt, JlptSectionResult... sections) {
        when(repo.findDetailedById(ATTEMPT)).thenReturn(Optional.ofNullable(attempt));
        when(repo.findSectionResults(ATTEMPT)).thenReturn(List.of(sections));
        when(repo.findExamSections(1L)).thenReturn(java.util.Arrays.stream(sections).map(JlptSectionResult::getSection).toList());
    }

    @Test void passesWhenOwnerLevelStatusTotalAndEverySectionPass() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "90"), section(1, "19", "40"), section(2, "19", "30"), section(3, "19", "25"));
        assertEquals(new Result(true, null), adapter.verify(USER, ATTEMPT, "N4"));
    }

    @Test void unknownAttemptIsNotFoundWithoutLeakingExistence() {
        given(null);
        assertEquals("ATTEMPT_NOT_FOUND", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void anotherUsersAttemptIsIndistinguishableFromMissing() {
        given(attempt(99L, "JLPT", "SUBMITTED", "N4", "90"), section(1, "19", "60"));
        var result = adapter.verify(USER, ATTEMPT, "N4");
        assertFalse(result.qualified());
        assertEquals("ATTEMPT_NOT_FOUND", result.reasonCode());
    }

    @Test void nonJlptAttemptTypeIsRejected() {
        given(attempt(USER, "QUIZ", "SUBMITTED", "N4", "90"), section(1, "19", "60"));
        assertEquals("ATTEMPT_NOT_JLPT", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void unsubmittedAttemptIsRejected() {
        given(attempt(USER, "JLPT", "IN_PROGRESS", "N4", "90"), section(1, "19", "60"));
        assertEquals("ATTEMPT_NOT_SUBMITTED", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void examOfAnotherLevelIsRejected() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N5", "90"), section(1, "19", "60"));
        assertEquals("LEVEL_MISMATCH", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void totalBelowPassingScoreFailsEvenWhenSectionsPass() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "90"), section(1, "19", "30"), section(2, "19", "30"), section(3, "19", "29"));
        assertEquals("TOTAL_SCORE_BELOW_PASSING", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void oneFailedSectionFailsEvenWhenTotalPasses() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "90"), section(1, "19", "60"), section(2, "19", "40"), section(3, "19", "18.99"));
        assertEquals("SECTION_BELOW_PASSING", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void sectionExactlyAtMinimumPasses() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "57"), section(1, "19", "19"), section(2, "19", "19"), section(3, "19", "19"));
        assertTrue(adapter.verify(USER, ATTEMPT, "N4").qualified());
    }

    @Test void attemptWithNoSectionResultsCannotQualify() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "0"));
        when(repo.findExamSections(1L)).thenReturn(List.of(section(1, "0", "0").getSection()));
        assertEquals("NO_SECTION_RESULTS", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    private JlptExamAttempt qualifying() {
        var a = attempt(USER, "JLPT", "SUBMITTED", "N4", "100");
        given(a, section(1, "20", "60"), section(2, "20", "60"));
        return a;
    }

    @Test void snapshotInTheAllowListPasses() {
        qualifying();
        assertTrue(adapter.verify(USER, ATTEMPT, "N4").qualified());
    }

    @Test void examMovedToANewPolicyDoesNotInvalidateAnOldAcceptedAttempt() {
        var a = qualifying();
        a.getExam().setPolicyVersion("JLPT_PATH_GATE_V2");
        assertTrue(adapter.verify(USER, ATTEMPT, "N4").qualified());
    }

    @Test void blankOrNullSnapshotFailsClosed() {
        var a = qualifying();
        a.setPolicyVersion(null);
        assertEquals("POLICY_VERSION_MISSING", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
        a.setPolicyVersion("  ");
        assertEquals("POLICY_VERSION_MISSING", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void snapshotOutsideTheAllowListIsRejectedEvenWhenTheExamStillUsesIt() {
        var a = qualifying();
        a.setPolicyVersion("JLPT_OLD_POLICY");
        a.getExam().setPolicyVersion("JLPT_OLD_POLICY");
        assertEquals("POLICY_VERSION_UNSUPPORTED", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void emptyConfigurationFailsClosed() {
        qualifying();
        var none = new AssessmentJlptQualificationAdapter(repo, List.of());
        assertEquals("POLICY_VERSION_UNSUPPORTED", none.verify(USER, ATTEMPT, "N4").reasonCode());
        var blank = new AssessmentJlptQualificationAdapter(repo, java.util.Arrays.asList("", "  "));
        assertEquals("POLICY_VERSION_UNSUPPORTED", blank.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void theBrowserHasNoWayToSupplyAPolicyVersion() throws Exception {
        var verify = com.jlas.learning.service.JlptQualificationPort.class.getMethod("verify", Long.class, Long.class, String.class);
        assertEquals(3, verify.getParameterCount());
        var fields = com.jlas.learning.dto.CompleteFromMockRequest.class.getRecordComponents();
        assertEquals(1, fields.length);
    }

    @Test void anExamSectionWithoutAResultBlocksCompletion() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "100"), section(1, "20", "60"));
        var missing = JlptExamSection.builder().id(2L).minPassingScore(new BigDecimal("20")).build();
        when(repo.findExamSections(1L)).thenReturn(List.of(section(1, "20", "60").getSection(), missing));
        assertEquals("SECTION_RESULT_MISSING", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void anExamWithNoDefinedSectionsCannotQualify() {
        given(attempt(USER, "JLPT", "SUBMITTED", "N4", "100"), section(1, "20", "60"));
        when(repo.findExamSections(1L)).thenReturn(List.of());
        assertEquals("EXAM_HAS_NO_SECTIONS", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }

    @Test void aResultForASectionOutsideTheAttemptExamIsRejectedAndCannotInflateTheTotal() {
        var requiredOne = section(1, "20", "45");
        var requiredTwo = section(2, "20", "45");
        var foreign = section(999, "0", "100");
        when(repo.findDetailedById(ATTEMPT)).thenReturn(Optional.of(
                attempt(USER, "JLPT", "SUBMITTED", "N4", "100")));
        when(repo.findExamSections(1L)).thenReturn(List.of(requiredOne.getSection(), requiredTwo.getSection()));
        when(repo.findSectionResults(ATTEMPT)).thenReturn(List.of(requiredOne, requiredTwo, foreign));

        assertEquals("UNEXPECTED_SECTION_RESULT", adapter.verify(USER, ATTEMPT, "N4").reasonCode());
    }
}
