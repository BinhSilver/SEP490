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
    private static final By GOAL_PATH_BUTTON = By.id("surveyGoHome");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public SurveyPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public SurveyPage open(String baseUrl) {
        driver.get(baseUrl + "/survey");
        wait.until(d -> isInvitationDisplayed() ||
            !d.findElements(NEXT_BUTTON).isEmpty() && d.findElement(NEXT_BUTTON).isDisplayed());
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

    /**
     * Answers an N3 -> N3 profile: already studied some, over 1 year, current
     * level N3, goal JLPT, target N3, ~60 minutes/day. Exercises the N3 Kanji
     * bank that was unblocked by the JLPT retag.
     */
    public SurveyPage answerN3Profile() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("studied", "YES");
        answers.put("duration", "24");
        answers.put("selfLevel", "N3");
        answers.put("goal", "JLPT");
        answers.put("targetLevel", "N3");
        answers.put("minutes", "60");
        return answerAndAdvance(answers);
    }

    /** From-zero JLPT N5 skips both the self-level slide and Smart Test. */
    public SurveyPage answerFromZeroN5() {
        return answerFromZero("N5");
    }

    /** From-zero JLPT N5/N4 enters foundation then the chosen target without Smart Test. */
    public SurveyPage answerFromZero(String targetLevel) {
        if (!java.util.Set.of("N5", "N4").contains(targetLevel)) {
            throw new IllegalArgumentException("Unsupported from-zero target: " + targetLevel);
        }
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("studied", "NO");
        answers.put("goal", "JLPT");
        answers.put("targetLevel", targetLevel);
        answers.put("minutes", "60");
        return answerAndAdvance(answers);
    }

    public void openFromZeroPath() {
        if (!isInvitationDisplayed() || driver.findElement(START_TEST_BUTTON).isDisplayed()) {
            throw new IllegalStateException("From-zero learner should proceed directly to a path");
        }
        jsClick(wait.until(ExpectedConditions.elementToBeClickable(GOAL_PATH_BUTTON)));
        wait.until(ExpectedConditions.urlContains("/learning/path"));
    }

    /** Completes the real onboarding survey for a survey-only communication goal. */
    public SurveyPage answerUsageGoal(String goal) {
        if (!java.util.Set.of("BA", "TRAVEL", "DAILY_LIFE").contains(goal)) {
            throw new IllegalArgumentException("Unsupported survey-only goal: " + goal);
        }
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("studied", "YES");
        answers.put("duration", "3");
        answers.put("selfLevel", "N5");
        answers.put("goal", goal);
        answers.put("minutes", "60");
        return answerAndAdvance(answers);
    }

    /** Uses the survey's own CTA and verifies it opens the matching goal survey. */
    public void openGoalSurvey(String goal) {
        if (!isInvitationDisplayed()) throw new IllegalStateException("Onboarding survey is incomplete");
        if (!driver.findElement(START_TEST_BUTTON).isDisplayed()) {
            jsClick(wait.until(ExpectedConditions.elementToBeClickable(GOAL_PATH_BUTTON)));
            wait.until(ExpectedConditions.urlContains("/learning/goals/" + goal + "/survey"));
            return;
        }
        throw new IllegalStateException("Survey-only goal unexpectedly offered Smart Test: " + goal);
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
                    .filter(input -> input.findElement(By.xpath("parent::label")).isDisplayed())
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
            WebElement activeSlide = driver.findElements(By.cssSelector(".survey-slide")).stream()
                .filter(WebElement::isDisplayed).findFirst()
                .orElseThrow(() -> new IllegalStateException("No active onboarding survey slide"));
            jsClick(next);
            wait.until(d -> isInvitationDisplayed() || !activeSlide.isDisplayed());
        }
        throw new IllegalStateException("Survey did not reach the invitation screen within " + MAX_SLIDES + " slides");
    }

    private boolean isInvitationDisplayed() {
        var invitation = driver.findElements(INVITATION);
        return !invitation.isEmpty() && invitation.get(0).isDisplayed();
    }

    private void clickRadio(WebElement radio) {
        if (!radio.isSelected()) {
            wait.until(ExpectedConditions.elementToBeClickable(radio.findElement(By.xpath("parent::label")))).click();
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
