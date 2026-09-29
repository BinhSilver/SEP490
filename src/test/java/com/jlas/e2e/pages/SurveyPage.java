package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multi-step onboarding survey (/survey). Each slide exposes one
 * {@code input[name=...]} radio group; {@link #answerAndAdvance(Map)} walks
 * through every slide, selecting whichever answer is present for that slide's
 * field name and clicking "Next" until the invitation screen appears.
 */
public class SurveyPage {

    private static final By NEXT_BUTTON = By.id("surveyNextButton");
    private static final By INVITATION = By.id("surveyInvitation");
    private static final By START_TEST_BUTTON = By.id("surveyStartTest");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public SurveyPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public SurveyPage open(String baseUrl) {
        driver.get(baseUrl + "/survey");
        wait.until(ExpectedConditions.visibilityOfElementLocated(NEXT_BUTTON));
        return this;
    }

    /**
     * Answers a N5 -> N4 in under 6 months profile: already studied some,
     * plans to spend 3 months, current level N5, goal JLPT, target N4,
     * ~60 minutes/day.
     */
    public SurveyPage answerN5ToN4UnderSixMonths() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("studied", "YES");
        answers.put("duration", "3");
        answers.put("selfLevel", "N5");
        answers.put("goal", "JLPT");
        answers.put("targetLevel", "N4");
        answers.put("minutes", "60");
        return answerAndAdvance(answers);
    }

    // The wizard's total step count changes as answers are given (e.g. "1/3"
    // then "2/5"), so this is a generous safety cap rather than an exact count.
    private static final int MAX_SLIDES = 25;

    private SurveyPage answerAndAdvance(Map<String, String> answersByField) {
        for (int i = 0; i < MAX_SLIDES; i++) {
            if (isInvitationDisplayed()) {
                return this;
            }
            for (Map.Entry<String, String> entry : answersByField.entrySet()) {
                // Earlier slides' radios stay in the DOM (just hidden), so only
                // act on the one that is actually visible on the active slide.
                var visibleMatch = driver.findElements(
                        By.cssSelector("input[name='" + entry.getKey() + "'][value='" + entry.getValue() + "']"))
                    .stream()
                    .filter(WebElement::isDisplayed)
                    .findFirst();
                if (visibleMatch.isPresent()) {
                    clickRadio(visibleMatch.get());
                    break;
                }
            }
            // The "Next" button starts disabled and only enables once a
            // choice on this slide is registered, so waiting for it to
            // become clickable also confirms the selection was applied.
            WebElement next = wait.until(ExpectedConditions.elementToBeClickable(NEXT_BUTTON));
            jsClick(next);
        }
        throw new IllegalStateException("Survey did not reach the invitation screen within " + MAX_SLIDES + " slides");
    }

    private boolean isInvitationDisplayed() {
        var invitation = driver.findElements(INVITATION);
        return !invitation.isEmpty() && invitation.get(0).isDisplayed();
    }

    private void clickRadio(WebElement radio) {
        if (!radio.isSelected()) {
            jsClick(radio);
        }
    }

    private void jsClick(WebElement element) {
        Clicks.jsClick(driver, element);
    }

    /** Starts the Smart Test from the post-survey invitation screen. */
    public void startSmartTest() {
        jsClick(wait.until(ExpectedConditions.presenceOfElementLocated(START_TEST_BUTTON)));
        wait.until(ExpectedConditions.urlContains("/smart-test"));
    }
}
