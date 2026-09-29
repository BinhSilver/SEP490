package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Learning path screen (/learning/path). The page is fully client-rendered
 * from JSON, so most nodes only carry classes/data-attributes rather than
 * static ids.
 */
public class LearningPathPage {

    private static final By ROOT = By.id("learningPathRoot");
    private static final By LESSON_ACTIONS = By.cssSelector(".lp-lesson__action");
    private static final By PENDING_LESSON_ACTIONS =
        By.cssSelector(".lp-lesson__action:not(.lp-lesson__action--done):not(.is-completed)");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public LearningPathPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    public LearningPathPage waitUntilLoaded() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(ROOT));
        wait.until(d -> !d.findElements(LESSON_ACTIONS).isEmpty());
        return this;
    }

    /**
     * Returns the href of the next lesson/quiz item that is not yet
     * completed, or empty when the whole path is done.
     */
    public Optional<String> nextPendingItemUrl() {
        List<WebElement> pending = driver.findElements(PENDING_LESSON_ACTIONS);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(pending.get(0).getAttribute("href"));
    }

    public boolean isQuizItem(String href) {
        return href != null && href.contains("quiz");
    }

    public void reload(String baseUrl, String assessmentAttemptId) {
        driver.get(baseUrl + "/learning/path?assessmentAttemptId=" + assessmentAttemptId);
        waitUntilLoaded();
    }
}
