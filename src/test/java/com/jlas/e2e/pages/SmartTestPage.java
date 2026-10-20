package com.jlas.e2e.pages;

import com.jlas.e2e.support.JdbcAssessmentAnswerOracle;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

public class SmartTestPage {

    private static final By QUESTION_VIEW = By.id("questionView");
    private static final By RESULT_VIEW = By.id("resultView");
    private static final By OPTION_INPUTS = By.cssSelector("#optionList input[name='answer']");
    private static final By ANSWER_BUTTON = By.id("answerButton");
    private static final By CREATE_PATH_BUTTON = By.id("createPathButton");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public SmartTestPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    public SmartTestPage waitUntilLoaded() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(QUESTION_VIEW));
        return this;
    }

    /**
     * Answers every adaptive question with the first available option until
     * the Smart Test finishes and the result screen is shown. The adaptive
     * engine picks the next question server-side, so there is no fixed
     * question count to loop over.
     */
    public SmartTestPage completeAdaptiveTest(int maxQuestions) {
        for (int i = 0; i < maxQuestions; i++) {
            if (!driver.findElements(RESULT_VIEW).isEmpty() && driver.findElement(RESULT_VIEW).isDisplayed()) {
                return this;
            }
            wait.until(ExpectedConditions.visibilityOfElementLocated(QUESTION_VIEW));
            List<WebElement> options = wait.until(
                ExpectedConditions.presenceOfAllElementsLocatedBy(OPTION_INPUTS));
            Clicks.jsClick(driver, options.get(0));

            WebElement answerButton = wait.until(ExpectedConditions.presenceOfElementLocated(ANSWER_BUTTON));
            Clicks.jsClick(driver, answerButton);

            // The answer POST is asynchronous. On the final answer the question
            // view becomes hidden while the old option nodes remain in the DOM;
            // on other answers the options are replaced for the next question.
            wait.until(d -> isResultDisplayed() || ExpectedConditions.stalenessOf(options.get(0)).apply(d));
            if (isResultDisplayed()) {
                return this;
            }
        }
        throw new IllegalStateException("Smart Test did not finish within " + maxQuestions + " questions");
    }

    private boolean isResultDisplayed() {
        List<WebElement> results = driver.findElements(RESULT_VIEW);
        return !results.isEmpty() && results.get(0).isDisplayed();
    }

    public void createLearningPath() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(RESULT_VIEW));
        Clicks.jsClick(driver, wait.until(ExpectedConditions.presenceOfElementLocated(CREATE_PATH_BUTTON)));
        wait.until(ExpectedConditions.urlContains("/learning/path"));
    }

    /** Answers every question wrongly via the canonical answer oracle, so the result lands below the self-report. */
    public SmartTestPage completeAdaptiveTestAnsweringWrongly(int maxQuestions) {
        JdbcAssessmentAnswerOracle oracle = new JdbcAssessmentAnswerOracle();
        for (int i = 0; i < maxQuestions; i++) {
            if (isResultDisplayed()) return this;
            wait.until(ExpectedConditions.visibilityOfElementLocated(QUESTION_VIEW));
            long questionId = Long.parseLong(
                driver.findElement(QUESTION_VIEW).getAttribute("data-question-id"));
            String wrong = oracle.correctOption(questionId) == 1 ? "2" : "1";
            WebElement option = driver.findElements(OPTION_INPUTS).stream()
                .filter(input -> wrong.equals(input.getAttribute("value")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Smart Test rendered no option " + wrong + " for question " + questionId));
            Clicks.jsClick(driver, option);
            Clicks.jsClick(driver, wait.until(ExpectedConditions.presenceOfElementLocated(ANSWER_BUTTON)));
            wait.until(d -> isResultDisplayed() || ExpectedConditions.stalenessOf(option).apply(d));
        }
        if (isResultDisplayed()) return this;
        throw new IllegalStateException("Smart Test did not finish within " + maxQuestions + " questions");
    }

    /** Answers every question correctly via the canonical answer oracle, so the result lands above a low target. */
    public SmartTestPage completeAdaptiveTestAnsweringCorrectly(int maxQuestions) {
        JdbcAssessmentAnswerOracle oracle = new JdbcAssessmentAnswerOracle();
        for (int i = 0; i < maxQuestions; i++) {
            if (isResultDisplayed()) return this;
            wait.until(ExpectedConditions.visibilityOfElementLocated(QUESTION_VIEW));
            long questionId = Long.parseLong(
                driver.findElement(QUESTION_VIEW).getAttribute("data-question-id"));
            String correct = String.valueOf(oracle.correctOption(questionId));
            WebElement option = driver.findElements(OPTION_INPUTS).stream()
                .filter(input -> correct.equals(input.getAttribute("value")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Smart Test rendered no option " + correct + " for question " + questionId));
            Clicks.jsClick(driver, option);
            Clicks.jsClick(driver, wait.until(ExpectedConditions.presenceOfElementLocated(ANSWER_BUTTON)));
            wait.until(d -> isResultDisplayed() || ExpectedConditions.stalenessOf(option).apply(d));
        }
        if (isResultDisplayed()) return this;
        throw new IllegalStateException("Smart Test did not finish within " + maxQuestions + " questions");
    }

    /**
     * Answers the adaptive Smart Test like a competent learner at the surveyed
     * level so the final estimated level matches that level (instead of always
     * answering the first option, which a real learner would not do). Three of
     * every five questions are answered correctly via the canonical answer
     * oracle; the other two are answered deliberately wrong. That 3/5 rhythm is
     * the adaptive engine's "stay at this level" signal, so each skill closes
     * at the surveyed level rather than drifting down to N5.
     */
    public SmartTestPage completeAdaptiveTestAsCompetentLearner(int maxQuestions) {
        JdbcAssessmentAnswerOracle oracle = new JdbcAssessmentAnswerOracle();
        for (int i = 0; i < maxQuestions; i++) {
            if (isResultDisplayed()) return this;
            wait.until(ExpectedConditions.visibilityOfElementLocated(QUESTION_VIEW));
            long questionId = Long.parseLong(
                driver.findElement(QUESTION_VIEW).getAttribute("data-question-id"));
            int correctOption = oracle.correctOption(questionId);
            boolean answerCorrectly = (i % 5) < 3;
            int chosen = answerCorrectly ? correctOption : (correctOption == 1 ? 2 : 1);

            WebElement option = driver.findElements(OPTION_INPUTS).stream()
                .filter(input -> String.valueOf(chosen).equals(input.getAttribute("value")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Smart Test rendered no option " + chosen + " for question " + questionId));
            Clicks.jsClick(driver, option);

            WebElement answerButton = wait.until(ExpectedConditions.presenceOfElementLocated(ANSWER_BUTTON));
            Clicks.jsClick(driver, answerButton);

            wait.until(d -> isResultDisplayed() || ExpectedConditions.stalenessOf(option).apply(d));
            if (isResultDisplayed()) return this;
        }
        throw new IllegalStateException("Smart Test did not finish within " + maxQuestions + " questions");
    }
}
