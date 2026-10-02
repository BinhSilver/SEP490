package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/**
 * Quiz screen reached from a learning-path item
 * (/learning/lesson/{id}/quiz, /learning/grammar/{id}/quiz,
 * /learning/path/flashcard-quiz). All variants share the same
 * #lessonQuizRoot / .lq-question / .lq-submit markup.
 */
public class LessonQuizPage {

    private static final By ROOT = By.id("lessonQuizRoot");
    private static final By QUESTIONS = By.cssSelector(".lq-question");
    private static final By SUBMIT_BUTTON = By.cssSelector("button.lq-submit[data-action='submit']");
    private static final By NEXT_BUTTON = By.cssSelector("[data-action='next']");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public LessonQuizPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    /**
     * Answers every visible question with its first option and submits,
     * repeating for multi-step quizzes until no submit button remains.
     */
    public void completeQuiz(int maxSteps) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(ROOT));
        for (int step = 0; step < maxSteps; step++) {
            List<WebElement> questions = driver.findElements(QUESTIONS);
            for (WebElement question : questions) {
                List<WebElement> firstOption = question.findElements(By.cssSelector(".lq-option input"));
                if (!firstOption.isEmpty() && !firstOption.get(0).isSelected()) {
                    Clicks.jsClick(driver, firstOption.get(0));
                }
            }

            List<WebElement> nextButtons = driver.findElements(NEXT_BUTTON);
            if (!nextButtons.isEmpty() && nextButtons.get(0).isDisplayed()) {
                Clicks.jsClick(driver, nextButtons.get(0));
                continue;
            }

            List<WebElement> submitButtons = driver.findElements(SUBMIT_BUTTON);
            if (submitButtons.isEmpty()) {
                return; // quiz already finished / no more steps
            }
            Clicks.jsClick(driver, wait.until(ExpectedConditions.presenceOfElementLocated(SUBMIT_BUTTON)));
        }
    }
}
