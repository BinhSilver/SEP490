package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/**
 * Best-effort completer for non-quiz path items (reading/video/vocab
 * lessons). These templates vary per lesson type, so this looks for any of
 * the known "mark complete" affordances rather than a single fixed locator.
 */
public class GenericLessonPage {

    private static final List<By> COMPLETE_BUTTON_CANDIDATES = List.of(
        By.cssSelector("[data-action='complete']"),
        By.cssSelector("[data-action='complete-lesson']"),
        By.cssSelector(".lesson-complete-button"),
        By.cssSelector("button[data-action='mark-complete']")
    );

    private final WebDriver driver;
    private final WebDriverWait wait;

    public GenericLessonPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    /** @return true if a completion control was found and clicked. */
    public boolean completeIfPossible() {
        for (By candidate : COMPLETE_BUTTON_CANDIDATES) {
            List<WebElement> matches = driver.findElements(candidate);
            if (!matches.isEmpty() && matches.get(0).isDisplayed()) {
                Clicks.jsClick(driver, wait.until(ExpectedConditions.presenceOfElementLocated(candidate)));
                return true;
            }
        }
        return false;
    }
}
