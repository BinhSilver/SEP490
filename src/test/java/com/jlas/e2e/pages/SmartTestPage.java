package com.jlas.e2e.pages;

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
}
