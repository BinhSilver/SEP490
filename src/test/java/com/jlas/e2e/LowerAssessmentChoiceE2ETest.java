package com.jlas.e2e;

import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SmartTestPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.3 (owner 2026-10-19): a Smart Test result below the self-reported level explains the gap and asks the learner
 * to accept a foundation route or keep the survey route. Accepting walks adjacent stages from the assessed level.
 */
class LowerAssessmentChoiceE2ETest extends BaseE2ETest {

    @Test
    @SuppressWarnings("unchecked")
    void acceptingALowerResultBuildsTheFoundationChainToTheUnchangedTarget() {
        String baseUrl = E2EConfig.baseUrl();
        stepLog.step(driver, "Log in", "Fresh dedicated learner is authenticated", () ->
            new LoginPage(driver).open(baseUrl).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Survey self N3 → target N3", "Survey saved", () -> survey.open(baseUrl).answerN3Profile());
        stepLog.step(driver, "Start Smart Test", "First question appears", survey::startSmartTest);
        SmartTestPage assessment = new SmartTestPage(driver);
        stepLog.step(driver, "Answer every question wrongly", "Result is visible", () ->
            assessment.waitUntilLoaded().completeAdaptiveTestAnsweringWrongly(140));

        stepLog.step(driver, "Accept the foundation route", "J-LAS dialog offers accept or keep-survey", () -> {
            jsClick(wait.until(ExpectedConditions.presenceOfElementLocated(By.id("createPathButton"))));
            WebElement dialog = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("smartConfirm")));
            WebElement picker = dialog.findElement(By.id("discrepancyPicker"));
            assertTrue(picker.isDisplayed(), "The lower-result choice must be shown");
            assertTrue(picker.getText().contains("N5"), picker.getText());
            WebElement accept = picker.findElement(By.cssSelector("input[value='ACCEPT_RECOMMENDATION']"));
            assertTrue(accept.isSelected(), "Accept is the recommended default");
            jsClick(dialog.findElement(By.id("smartConfirmYes")));
            wait.until(ExpectedConditions.urlContains("/learning/path"));
        });

        LearningPathPage path = new LearningPathPage(driver);
        stepLog.step(driver, "Read server-owned path", "Stages walk N5 → N4 → N3; target unchanged", () -> {
            path.waitUntilLoaded();
            Map<String, Object> actual = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch('/api/learning/paths/current', { headers: window.JlasAuth.authHeaders() })
                  .then(async response => {
                    const body = await response.json();
                    done({ status: response.status, target: body.data?.targetLevel,
                      stages: (body.data?.stages || []).map(stage => stage.level), url: location.search });
                  }).catch(error => done({ error: String(error) }));
                """);
            assertNull(actual.get("error"), actual.toString());
            assertEquals(200L, actual.get("status"), actual.toString());
            assertEquals("N3", actual.get("target"));
            List<String> stages = (List<String>) actual.get("stages");
            assertEquals("N3", stages.get(stages.size() - 1), actual.toString());
            assertTrue(stages.containsAll(List.of("N4", "N3")), actual.toString());
            assertTrue(String.valueOf(actual.get("url")).contains("discrepancyChoice=ACCEPT_RECOMMENDATION"), actual.toString());
        });
    }

    private void jsClick(WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", element);
    }
}
