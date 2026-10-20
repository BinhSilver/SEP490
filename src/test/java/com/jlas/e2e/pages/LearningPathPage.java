package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Interactive learning path screen (/learning/path/v2).
 */
public class LearningPathPage {

    private static final By ROOT = By.id("interactivePathRoot");
    private static final By PACKAGE_CARDS = By.cssSelector("#interactivePathRoot [data-package-card]");
    private static final By ERROR_STATE = By.cssSelector("#interactivePathRoot .ilp-state h1");
    private static final By PENDING_LESSON_ACTIONS = By.cssSelector("#interactivePathRoot .ilp-activity__link");
    private static final By START_PACKAGE = By.cssSelector("#interactivePathRoot [data-start-package]");
    private static final By PACE_DIALOG = By.cssSelector("#interactivePathRoot [data-pace-dialog]");
    private static final By PACE_CONFIRM = By.cssSelector("#interactivePathRoot [data-pace-confirm]");
    private static final By TOAST = By.cssSelector("#interactivePathRoot [data-toast]");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public LearningPathPage(WebDriver driver) {
        this.driver = driver;
        // A fresh path composes every target package before the first roadmap render.
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(120));
    }

    public LearningPathPage waitUntilLoaded() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(ROOT));
        wait.until(d -> "false".equals(d.findElement(ROOT).getAttribute("aria-busy")));
        if (driver.findElements(PACKAGE_CARDS).isEmpty()) {
            throw new IllegalStateException("Interactive path did not render packages: "
                + driver.findElement(ERROR_STATE).getText() + "; API=" + currentPathApiResponse());
        }
        return this;
    }

    /** A fresh read helps distinguish a persistent API failure from a failed first load. */
    private String currentPathApiResponse() {
        Object response = ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            const headers = window.JlasAuth && window.JlasAuth.authHeaders
              ? window.JlasAuth.authHeaders() : {};
            fetch('/api/learning/v2/paths/current', { headers })
              .then(async response => {
                const body = await response.json().catch(() => ({}));
                done(JSON.stringify({
                  status: response.status,
                  code: body.code,
                  message: body.message,
                  completedPackages: body.data?.completedPackages,
                  totalPackages: body.data?.totalPackages
                }));
              })
              .catch(error => done(JSON.stringify({ error: String(error) })));
            """);
        return String.valueOf(response);
    }

    public int packageCount() {
        return driver.findElements(PACKAGE_CARDS).size();
    }

    public int completedActivityCount() {
        return driver.findElements(By.cssSelector("#interactivePathRoot .ilp-activity.is-done")).size();
    }

    /** The completed summary is server-owned; DOM links can disappear while a path is still active. */
    public boolean isFullyCompleted() {
        Object result = ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            const headers = window.JlasAuth && window.JlasAuth.authHeaders
              ? window.JlasAuth.authHeaders() : {};
            fetch('/api/learning/v2/paths/current/summary', { headers })
              .then(async response => {
                const body = await response.json().catch(() => ({}));
                done(response.ok && body.data?.status === 'COMPLETED');
              })
              .catch(() => done(false));
            """);
        return Boolean.TRUE.equals(result);
    }

    /**
     * Exercises the "đổi nhịp" (change pace) control: opens the preview for the
     * given direction, confirms it, and waits for the path to re-render with the
     * new pace applied. Returns the minutes-per-day value shown after the change.
     */
    public int changePace(String direction) {
        WebElement preview = wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("#interactivePathRoot [data-pace-preview='" + direction + "']")));
        Clicks.jsClick(driver, preview);

        WebElement confirm = wait.until(ExpectedConditions.elementToBeClickable(PACE_CONFIRM));
        String newMinutes = confirm.getAttribute("data-pace-minutes");
        Clicks.jsClick(driver, confirm);

        wait.until(d -> d.findElements(PACE_DIALOG).isEmpty() && !d.findElements(TOAST).isEmpty());
        wait.until(d -> "false".equals(d.findElement(ROOT).getAttribute("aria-busy")));
        return Integer.parseInt(newMinutes);
    }

    /** Reads the current minutes-per-day shown in the pace aside, or 0 if absent. */
    public int minutesPerDay() {
        List<WebElement> metrics = driver.findElements(By.cssSelector("#interactivePathRoot .ilp-hero .ilp-metrics span"));
        for (WebElement metric : metrics) {
            String text = metric.getText();
            if (text != null && text.contains("phút/ngày")) {
                String digits = text.replaceAll("[^0-9]", "");
                return digits.isEmpty() ? 0 : Integer.parseInt(digits);
            }
        }
        return 0;
    }

    /** Start the first available package instead of following a later package's active link. */
    public String startFirstAvailablePackage() {
        // Skill cards render collapsed (one skill open at a time); a learner expands the card first.
        WebElement button = wait.until(ExpectedConditions.presenceOfElementLocated(START_PACKAGE));
        if (!button.isDisplayed()) {
            WebElement toggle = button.findElement(By.xpath(
                "ancestor::*[contains(concat(' ', normalize-space(@class), ' '), ' ilp-skill-card ')][1]//*[@data-skill-toggle]"));
            Clicks.jsClick(driver, toggle);
            wait.until(d -> button.isDisplayed());
        }
        Clicks.jsClick(driver, button);
        wait.until(d -> d.getCurrentUrl().contains("/learning/v2/")
            || !d.findElements(ERROR_STATE).isEmpty());
        if (!driver.findElements(ERROR_STATE).isEmpty()) {
            throw new IllegalStateException("Could not start package: " + driver.findElement(ERROR_STATE).getText());
        }
        return driver.getCurrentUrl();
    }

    /** Returns a visible next activity, if any. Empty is not proof that the path is complete. */
    public Optional<String> nextPendingItemUrl() {
        List<WebElement> start = driver.findElements(START_PACKAGE);
        if (!start.isEmpty()) {
            return Optional.of(startFirstAvailablePackage());
        }
        List<WebElement> pending = driver.findElements(PENDING_LESSON_ACTIONS);
        if (!pending.isEmpty()) {
            return Optional.ofNullable(pending.get(0).getAttribute("href"));
        }
        return Optional.empty();
    }

    public boolean isQuizItem(String href) {
        return href != null && href.contains("quiz");
    }

    public void reload(String baseUrl) {
        // Once a path has been created, normal learner navigation opens the
        // current path. Re-sending assessmentAttemptId would request path
        // creation again and does not represent the product's "Về lộ trình"
        // action.
        driver.get(baseUrl + "/learning/path/v2");
        waitUntilLoaded();
    }
}
