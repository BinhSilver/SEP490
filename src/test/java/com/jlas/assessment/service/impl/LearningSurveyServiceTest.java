package com.jlas.assessment.service.impl;

import com.jlas.assessment.dto.SmartTestStartRequest;
import com.jlas.assessment.entity.UserLearningSurvey;
import com.jlas.assessment.repository.*;
import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LearningSurveyServiceTest {
    @Mock UserRepository users;
    @Mock AssessmentAttemptRepository attempts;
    @Mock AssessmentQuestionRepository questions;
    @Mock AssessmentAnswerRepository answers;
    @Mock SmartTestSessionRepository sessions;
    @Mock SmartTestSessionQuestionRepository issued;
    @Mock SmartTestSkillResultRepository results;
    @Mock UserLearningSurveyRepository surveys;
    @InjectMocks AssessmentServiceImpl service;

    @Test void newAccountNeedsSurvey() {
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        var response = service.getLearningSurvey(7L);
        assertThat(response.completed()).isFalse();
        assertThat(response.survey()).isNull();
    }

    @Test void savingSurveyDoesNotCreateATestAndPreservesUnknownLevel() {
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        var response = service.saveLearningSurvey(7L, request("DAILY_LIFE", null, "UNKNOWN"));
        assertThat(response.completed()).isTrue();
        assertThat(response.survey().selfReportedLevel()).isEqualTo("UNKNOWN");
        verify(surveys).saveAndFlush(argThat(s -> s.getUser().getId() == 7L
                && s.getSkills().equals("KANJI,VOCABULARY,GRAMMAR,READING,LISTENING") && s.getCompletedAt() != null));
        verifyNoInteractions(attempts, questions, answers, sessions, issued, results);
    }

    @Test void completedSurveyCanBeLoadedAcrossSessions() {
        when(surveys.findById(7L)).thenReturn(Optional.of(UserLearningSurvey.builder()
                .goal("JLPT").targetLevel("N2").selfReportedLevel("N3")
                .skills("KANJI,READING").studyDurationMonths(9).dailyStudyMinutes(30).build()));
        var response = service.getLearningSurvey(7L);
        assertThat(response.completed()).isTrue();
        assertThat(response.survey().targetLevel()).isEqualTo("N2");
    }

    @Test void updatingSurveyKeepsOneProfileAndClearsIrrelevantTarget() {
        var existing = UserLearningSurvey.builder().userId(7L).goal("JLPT").targetLevel("N2").build();
        when(surveys.findById(7L)).thenReturn(Optional.of(existing));
        service.saveLearningSurvey(7L, request("TRAVEL", "N2", "N4"));
        verify(surveys).saveAndFlush(existing);
        assertThat(existing.getTargetLevel()).isNull();
        assertThat(existing.getGoal()).isEqualTo("TRAVEL");
        verifyNoInteractions(users, attempts);
    }

    @Test void jlptRequiresATargetBeforeAnythingIsSaved() {
        assertThatThrownBy(() -> service.saveLearningSurvey(7L, request("JLPT", null, "N3")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(surveys, users, attempts);
    }

    @Test void jlptSurveySynchronizesTheProfileGoalNotTheSelfAssessedLevel() {
        var user = User.builder().id(7L).japaneseLevel("N5").build();
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        when(users.findById(7L)).thenReturn(Optional.of(user));
        service.saveLearningSurvey(7L, request("JLPT", "N3", "N4"));
        assertThat(user.getJapaneseLevel()).isEqualTo("N3");
        verify(users).save(user);
        verify(surveys).saveAndFlush(argThat(s -> s.getSelfReportedLevel().equals("N4")));
    }

    @Test void n2PathTargetIsNotOfferedForJlptSurvey() {
        assertThatThrownBy(() -> service.saveLearningSurvey(7L, request("JLPT", "N2", "N3")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TARGET_LEVEL_NOT_ALLOWED));
        verifyNoInteractions(surveys, users, attempts);
    }

    @Test void n1PathTargetIsRejectedBeforeStartingSmartTest() {
        assertThatThrownBy(() -> service.startSmartTest(7L, request("JLPT", "N1", "N2")))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TARGET_LEVEL_NOT_ALLOWED));
        verifyNoInteractions(attempts, questions, answers, sessions, issued, results);
    }

    @Test void supportedTargetLevelIsAccepted() {
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        var response = service.saveLearningSurvey(7L, request("JLPT", "N3", "N4"));
        assertThat(response.survey().targetLevel()).isEqualTo("N3");
    }

    @Test void beginnerCannotSaveAnN3Target() {
        assertThatThrownBy(() -> service.saveLearningSurvey(7L, request("JLPT", "N3", "FROM_ZERO", 0)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TARGET_LEVEL_NOT_ALLOWED));
        verifyNoInteractions(surveys, users, attempts);
    }

    @Test void unknownLevelGetsTemporaryOptionsUntilSmartTestFinishes() {
        assertThat(service.getTargetOptions("UNKNOWN"))
                .filteredOn(option -> java.util.List.of("N5", "N4", "N3").contains(option.targetLevel()))
                .allSatisfy(option -> assertThat(option.allowed()).isTrue());
        assertThat(service.getTargetOptions("UNKNOWN"))
                .filteredOn(option -> java.util.List.of("N2", "N1").contains(option.targetLevel()))
                .allSatisfy(option -> assertThat(option.allowed()).isFalse());
    }

    @Test void savingSurveyPreservesFromZeroLevel() {
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        var response = service.saveLearningSurvey(7L, request("TRAVEL", null, "FROM_ZERO"));
        assertThat(response.survey().selfReportedLevel()).isEqualTo("FROM_ZERO");
        verify(surveys).saveAndFlush(argThat(s -> "FROM_ZERO".equals(s.getSelfReportedLevel())));
    }

    @Test void fromZeroCannotStartSmartTest() {
        assertThatThrownBy(() -> service.startSmartTest(7L, request("TRAVEL", null, "FROM_ZERO")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users, attempts, questions, answers, sessions, issued, results);
    }

    @Test void usageGoalUnderSixMonthsCannotStartSmartTest() {
        assertThatThrownBy(() -> service.startSmartTest(7L, request("TRAVEL", null, "N5", 3)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users, attempts, questions, answers, sessions, issued, results);
    }

    @Test void surveyOnlyGoalsNeverStartSmartTestRegardlessOfExperience() {
        for (String goal : java.util.List.of("TRAVEL", "DAILY_LIFE", "BA")) {
            assertThatThrownBy(() -> service.startSmartTest(7L, request(goal, null, "N3", 36)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("chỉ cần làm khảo sát");
        }
        verifyNoInteractions(users, attempts, questions, answers, sessions, issued, results);
    }

    @Test void fromZeroNormalizesStudyDurationToZero() {
        when(surveys.findById(7L)).thenReturn(Optional.empty());
        when(users.findById(7L)).thenReturn(Optional.of(User.builder().id(7L).build()));
        service.saveLearningSurvey(7L, request("TRAVEL", null, "FROM_ZERO", 24));
        verify(surveys).saveAndFlush(argThat(s -> s.getStudyDurationMonths() != null
                && s.getStudyDurationMonths() == 0));
    }

    @Test void studiedLearnerRequiresPositiveStudyDuration() {
        assertThatThrownBy(() -> service.saveLearningSurvey(7L, request("JLPT", "N4", "N5", 0)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(surveys, users);
    }

    @Test void studiedLearnerWithNullDurationIsRejected() {
        var req = new SmartTestStartRequest("TRAVEL", null, "N5", null, 30);
        assertThatThrownBy(() -> service.saveLearningSurvey(7L, req))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(surveys, users);
    }

    private SmartTestStartRequest request(String goal, String target, String self) {
        return new SmartTestStartRequest(goal, target, self, 9, 30);
    }

    private SmartTestStartRequest request(String goal, String target, String self, int months) {
        return new SmartTestStartRequest(goal, target, self, months, 30);
    }
}
