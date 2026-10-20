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
import org.openqa.selenium.support.ui.Select;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.3 / brief §5.1 — a Smart Test result above the chosen target keeps the assessment, lets the learner pick a
 * review start no higher than the target, and builds every consecutive stage. The survey here is an upgrade
 * (self N5 → target N4), the case that used to dead-end because the server only accepted survey == target.
 */
class HigherAssessmentReviewStartE2ETest extends BaseE2ETest {
    private static final Map<String, Integer> RANK = Map.of("N5", 1, "N4", 2, "N3", 3, "N2", 4, "N1", 5);

    @Test
    @SuppressWarnings("unchecked")
    void upgradeSurveyWithHigherResultCreatesConfirmedConsecutiveReviewPath() {
        String baseUrl = E2EConfig.baseUrl();
        stepLog.step(driver, "Log in", "Fresh dedicated learner is authenticated", () ->
            new LoginPage(driver).open(baseUrl).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Survey N5 → N4", "Upgrade survey saved", () ->
            survey.open(baseUrl).answerN5ToN4UnderSixMonths());
        stepLog.step(driver, "Start Smart Test", "First question appears", survey::startSmartTest);
        SmartTestPage assessment = new SmartTestPage(driver);
        stepLog.step(driver, "Answer every question correctly", "Result is visible", () ->
            assessment.waitUntilLoaded().completeAdaptiveTestAnsweringCorrectly(140));

        Map<String, Object> before = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            const attempt = localStorage.getItem('assessmentAttemptId');
            done({ assessed: document.body.innerText.match(/Kết quả Smart Test của bạn là (N[1-5])/)?.[1] || null });
            """);
        String assessed = (String) before.get("assessed");
        assertTrue(assessed != null && RANK.get(assessed) > RANK.get("N4"),
            "All-correct answers must land above the N4 target; banner shows " + assessed);

        stepLog.step(driver, "Choose review start N5", "J-LAS dialog offers only levels up to the target", () -> {
            jsClick(wait.until(ExpectedConditions.presenceOfElementLocated(By.id("createPathButton"))));
            WebElement dialog = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("smartConfirm")));
            WebElement select = dialog.findElement(By.id("reviewStartLevel"));
            List<String> offered = new Select(select).getOptions().stream()
                .filter(option -> option.getDomProperty("hidden") == null || "false".equals(option.getDomProperty("hidden")))
                .map(option -> option.getAttribute("value")).toList();
            assertEquals(List.of("N5", "N4"), offered);
            new Select(select).selectByValue("N5");
            jsClick(dialog.findElement(By.id("smartConfirmYes")));
            wait.until(ExpectedConditions.urlContains("/learning/path"));
        });

        LearningPathPage path = new LearningPathPage(driver);
        stepLog.step(driver, "Read server-owned path", "Stages N5 then N4; Smart Test result kept", () -> {
            path.waitUntilLoaded();
            assertTrue(path.packageCount() > 0);
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
            assertEquals("N4", actual.get("target"));
            assertEquals(List.of("N5", "N4"), actual.get("stages"), actual.toString());
            // The path API carries no title; the confirmed review start must survive the V2 redirect.
            assertTrue(String.valueOf(actual.get("url")).contains("reviewStartLevel=N5"), actual.toString());
        });
    }

    private void jsClick(WebElement element) {
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", element);
    }
}
