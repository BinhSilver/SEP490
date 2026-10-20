package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/** Page object for exploring a goal survey without submitting it. */
public final class GoalSurveyReadOnlyPage {

    private static final By ROOT = By.id("goalSurveyPage");
    private static final By CONTENT = By.id("goalSurveyContent");
    private static final By QUESTIONS = By.cssSelector("#goalSurveyContent .goal-survey-question");
    private static final By OPTIONS = By.cssSelector("input[type='radio'], input[type='checkbox']");
    private static final By UNAVAILABLE = By.cssSelector("#goalSurveyContent [data-goal-survey-retry]");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public GoalSurveyReadOnlyPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20));
    }

    public GoalSurveyReadOnlyPage open(String baseUrl, String goalCode) {
        driver.get(baseUrl + "/learning/goals/" + goalCode + "/survey");
        wait.until(ExpectedConditions.visibilityOfElementLocated(ROOT));
        wait.until(d -> !d.findElements(QUESTIONS).isEmpty() || !d.findElements(UNAVAILABLE).isEmpty());
        if (!driver.findElements(QUESTIONS).isEmpty()) {
            wait.until(d -> d.findElements(OPTIONS).stream()
                .map(input -> input.findElement(By.xpath("parent::label")))
                .anyMatch(WebElement::isDisplayed));
        }
        return this;
    }

    public int questionCount() {
        return driver.findElements(QUESTIONS).size();
    }

    /** Clicks every visible choice, then restores checkboxes to their original unchecked state. */
    public boolean exerciseAllChoices() {
        List<WebElement> questions = driver.findElements(QUESTIONS);
        boolean exercised = false;
        for (WebElement question : questions) {
            List<WebElement> inputs = question.findElements(OPTIONS);
            if (inputs.isEmpty()) {
                if (Boolean.parseBoolean(question.getAttribute("data-required"))) return false;
                continue;
            }
            for (WebElement input : inputs) {
                WebElement label = input.findElement(By.xpath("parent::label"));
                if (!label.isDisplayed() || !label.isEnabled()) continue;
                if (label.getText().isBlank()) return false;
                // The shared shell's fixed mobile bottom bar covers whatever sits at the viewport edge; a
                // learner scrolls the option into view first, so do the same before the real click.
                ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center', behavior:'instant'});", label);
                wait.until(ExpectedConditions.elementToBeClickable(label)).click();
                wait.until(d -> input.isSelected());
                exercised = true;
                if ("checkbox".equals(input.getAttribute("type"))) {
                    label.click();
                    wait.until(d -> !input.isSelected());
                }
            }
        }
        return exercised && questionCount() > 0;
    }

    public boolean hasHorizontalOverflow() {
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(
            "return Math.max(document.documentElement.scrollWidth, document.body.scrollWidth) > " +
                "document.documentElement.clientWidth + 1"));
    }

    public int blockedWriteCount() {
        Object count = ((JavascriptExecutor) driver).executeScript(
            "return Array.isArray(window.__jlasBlockedWrites) ? window.__jlasBlockedWrites.length : -1");
        if (!(count instanceof Number value)) return -1;
        return value.intValue();
    }
}
