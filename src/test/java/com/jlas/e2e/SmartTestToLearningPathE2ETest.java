package com.jlas.e2e;

import com.jlas.e2e.pages.GenericLessonPage;
import com.jlas.e2e.pages.InteractiveActivityPage;
import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LessonQuizPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SmartTestPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import com.jlas.e2e.support.JdbcAssessmentAnswerOracle;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end happy path for a learner going N5 -> N4 in under 6 months:
 * login -> onboarding survey -> Smart Test -> generate learning path ->
 * work through every lesson/quiz in the path.
 *
 * Requires JLAS_TEST_EMAIL / JLAS_TEST_PASSWORD (a real, non-demo account)
 * and a running app at JLAS_BASE_URL (default http://localhost:8080).
 * Run with: mvn test -Dtest=SmartTestToLearningPathE2ETest -Dgroups=e2e
 */
@Tag("e2e")
class SmartTestToLearningPathE2ETest extends BaseE2ETest {

    private static final Pattern ATTEMPT_ID_PATTERN = Pattern.compile("assessmentAttemptId=([^&]+)");
    private static final int MAX_SMART_TEST_QUESTIONS = 60;
    private static final int MAX_QUIZ_STEPS = 10;
    private static final int MAX_LEARNING_ITEMS =
        Integer.parseInt(System.getProperty("jlas.maxLearningItems", "300"));

    @Test
    @DisplayName("N5 -> N4 in under 6 months: login, survey, smart test, path creation, full path completion")
    void completesFullJourneyFromSurveyToLearningPath() {
        String baseUrl = E2EConfig.baseUrl();

        stepLog.step(driver, "Sign in", "Dedicated test learner is authenticated", () ->
            new LoginPage(driver).open(baseUrl).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));

        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Complete onboarding survey", "N5 to N4 learning profile is saved", () ->
            survey.open(baseUrl).answerN5ToN4UnderSixMonths());
        stepLog.step(driver, "Start Smart Test", "Assessment question is visible", survey::startSmartTest);

        SmartTestPage smartTest = new SmartTestPage(driver);
        stepLog.step(driver, "Complete adaptive Smart Test", "Result screen appears within safety cap", () ->
            smartTest.waitUntilLoaded().completeAdaptiveTest(MAX_SMART_TEST_QUESTIONS));
        keepSessionAlive();
        stepLog.step(driver, "Create personalized path", "Path page opens from assessment result", smartTest::createLearningPath);

        String attemptId = extractAttemptId(driver.getCurrentUrl());
        Assertions.assertNotNull(attemptId, "Expected an assessmentAttemptId on the learning path URL");

        LearningPathPage path = new LearningPathPage(driver);
        stepLog.step(driver, "Read learning path", "At least one actionable activity is visible", path::waitUntilLoaded);
        int completedItems = workThroughEntirePath(path, baseUrl, attemptId);

        Assertions.assertTrue(completedItems > 0,
            "Expected at least one lesson/quiz item to be completed in the learning path");
        keepSessionAlive();
        Assertions.assertTrue(path.isFullyCompleted(),
            "Learning path is not complete after " + completedItems + " activities "
                + "(safety cap " + MAX_LEARNING_ITEMS + "). An empty link list alone does not prove completion.");
    }

    /**
     * Repeatedly opens the next pending lesson/quiz, completes it, and
     * returns to the path screen, until nothing is left pending or the
     * safety cap is hit.
     */
    private int workThroughEntirePath(LearningPathPage path, String baseUrl, String attemptId) {
        JdbcAssessmentAnswerOracle answerOracle = new JdbcAssessmentAnswerOracle();
        int completed = 0;
        Optional<String> next;
        while (completed < MAX_LEARNING_ITEMS && (next = path.nextPendingItemUrl()).isPresent()) {
            String itemUrl = next.get();
            stepLog.step(driver, "Complete path activity " + (completed + 1),
                "Activity completes and path progress remains accessible", () -> {
                    keepSessionAlive();
                    driver.get(itemUrl);
                    if (path.isQuizItem(itemUrl)) {
                        new LessonQuizPage(driver).completeQuiz(MAX_QUIZ_STEPS);
                    } else if (itemUrl.contains("/learning/v2/")) {
                        new InteractiveActivityPage(driver, answerOracle).complete();
                    } else {
                        if (!new GenericLessonPage(driver).completeIfPossible()) {
                            throw new IllegalStateException("No supported completion control for this lesson route");
                        }
                    }
                    path.reload(baseUrl);
                });
            completed++;
        }
        return completed;
    }

    private String extractAttemptId(String url) {
        Matcher matcher = ATTEMPT_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Fallback: path may have been re-rendered with the id in the query string differently.
        return URI.create(url).getQuery();
    }
}
