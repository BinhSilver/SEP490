package com.jlas.e2e;

import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SmartTestPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import com.jlas.e2e.support.JdbcAssessmentAnswerOracle;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Brief §5.3/§5.4 on a fresh JLPT path with real Minna Reading content: one question per step with "Câu x / n",
 * Trước/Tiếp, autosaved answers; after submitting, going back to the roadmap and pressing browser Back (and
 * Forward/Back again) must restore the saved result instead of opening a new quiz attempt.
 */
class ComprehensionResultNavigationE2ETest extends BaseE2ETest {

    @Test
    @SuppressWarnings("unchecked")
    void readingResultSurvivesRoadmapBackForwardWithoutANewAttempt() {
        String base = E2EConfig.baseUrl();
        stepLog.step(driver, "Log in", "Fresh dedicated learner is authenticated", () ->
            new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Survey N5 → N4", "Survey saved", () -> survey.open(base).answerN5ToN4UnderSixMonths());
        stepLog.step(driver, "Start Smart Test", "First question appears", survey::startSmartTest);
        SmartTestPage assessment = new SmartTestPage(driver);
        stepLog.step(driver, "Finish Smart Test", "Result is visible", () ->
            assessment.waitUntilLoaded().completeAdaptiveTest(140));
        stepLog.step(driver, "Create path", "Roadmap opens", assessment::createLearningPath);
        new LearningPathPage(driver).waitUntilLoaded();

        Map<String, Object> target = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() })
              .then(async response => {
                const path = (await response.json()).data || {};
                for (const pkg of (path.packages || [])) {
                  if (pkg.status !== 'AVAILABLE' && pkg.status !== 'IN_PROGRESS') continue;
                  const reading = (pkg.activities || []).find(a => a.type === 'READING');
                  if (reading) return done({ packageId: String(pkg.id), activityId: String(reading.id), status: pkg.status });
                }
                done({ error: 'No open package with a Reading activity' });
              }).catch(error => done({ error: String(error) }));
            """);
        assertNull(target.get("error"), target.toString());
        String activityId = (String) target.get("activityId");

        stepLog.step(driver, "Open the Reading activity from its chapter", "Reading page opens", () -> {
            driver.get(base + "/learning/path/v2");
            new LearningPathPage(driver).waitUntilLoaded();
            WebElement start = new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> d.findElements(
                    By.cssSelector("[data-start-package='" + target.get("packageId") + "'][data-preferred-activity='"
                        + activityId + "']")).stream().findFirst().orElse(null));
            jsClick(start);
            wait.until(ExpectedConditions.urlContains("/learning/v2/reading"));
        });

        WebDriverWait pageWait = new WebDriverWait(driver, Duration.ofSeconds(30));
        WebElement root = pageWait.until(ExpectedConditions.visibilityOfElementLocated(By.id("interactiveComprehensionStudyRoot")));
        pageWait.until(d -> "false".equals(root.getAttribute("aria-busy")));
        driver.findElements(By.cssSelector("[data-reached-passage]")).stream().findFirst().ifPresent(this::jsClick);
        if (driver.findElements(By.cssSelector("form[data-question-pager]")).isEmpty()) {
            pageWait.until(d -> !d.findElements(By.cssSelector("[data-check]:not([disabled])")).isEmpty());
            jsClick(driver.findElement(By.cssSelector("[data-check]:not([disabled])")));
        }
        WebElement form = pageWait.until(d -> d.findElements(By.cssSelector("form[data-question-pager]")).stream()
            .findFirst().orElse(null));
        List<WebElement> pages = form.findElements(By.cssSelector("fieldset[data-question-page]"));
        int total = pages.size();
        assertTrue(total > 1, "The Reading exercise must have several questions to page through");

        JdbcAssessmentAnswerOracle oracle = new JdbcAssessmentAnswerOracle();
        stepLog.step(driver, "Answer question by question", "Counter, Trước/Tiếp and autosave work", () -> {
            for (int i = 0; i < total; i++) {
                assertEquals("Câu " + (i + 1) + " / " + total,
                    form.findElement(By.cssSelector("[data-question-counter]")).getText());
                assertEquals(1, form.findElements(By.cssSelector("fieldset[data-question-page]:not([hidden])")).size());
                WebElement page = pages.get(i);
                List<WebElement> radios = page.findElements(By.cssSelector("input[type='radio']"));
                long questionId = Long.parseLong(radios.get(0).getAttribute("name").substring(2));
                int wrong = oracle.correctOption(questionId) == 1 ? 2 : 1;
                jsClick(radios.stream().filter(r -> String.valueOf(wrong).equals(r.getAttribute("value")))
                    .findFirst().orElseThrow());
                pageWait.until(d -> "Đã lưu".equals(form.findElement(By.cssSelector("[data-draft-status]")).getText()));
                if (i < total - 1) jsClick(form.findElement(By.cssSelector("[data-question-next]")));
            }
            jsClick(form.findElement(By.cssSelector("[data-question-prev]")));
            assertTrue(pages.get(total - 2).findElement(By.cssSelector("input[type='radio']:checked")).isSelected(),
                "Trước must keep the previous answer");
            jsClick(form.findElement(By.cssSelector("[data-question-next]")));
            jsClick(form.findElement(By.cssSelector("[data-question-submit]")));
        });

        pageWait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("#interactiveComprehensionStudyRoot .ilp-result")));
        int attemptsAfterSubmit = attemptCount(activityId);
        assertTrue(attemptsAfterSubmit >= 1, "Submitted attempt must be in the history");

        stepLog.step(driver, "Roadmap then browser Back", "Saved result is shown again, no new quiz", () -> {
            driver.get(base + "/learning/path/v2");
            new LearningPathPage(driver).waitUntilLoaded();
            driver.navigate().back();
            assertResultRestored(activityId, attemptsAfterSubmit);
        });
        stepLog.step(driver, "Forward then Back again", "Result is still restored", () -> {
            driver.navigate().forward();
            new LearningPathPage(driver).waitUntilLoaded();
            driver.navigate().back();
            assertResultRestored(activityId, attemptsAfterSubmit);
        });
    }

    private void assertResultRestored(String activityId, int expectedAttempts) {
        WebDriverWait pageWait = new WebDriverWait(driver, Duration.ofSeconds(30));
        WebElement root = pageWait.until(ExpectedConditions.visibilityOfElementLocated(By.id("interactiveComprehensionStudyRoot")));
        pageWait.until(d -> "false".equals(root.getAttribute("aria-busy")));
        assertTrue(driver.findElements(By.cssSelector("#interactiveComprehensionStudyRoot form[data-question-pager]")).isEmpty(),
            "Back reopened a quiz form instead of the saved result");
        assertTrue(!driver.findElements(By.cssSelector("#interactiveComprehensionStudyRoot .ilp-result")).isEmpty(),
            "Back did not restore the saved result: " + root.getText().substring(0, Math.min(300, root.getText().length())));
        assertEquals(expectedAttempts, attemptCount(activityId), "Back must not create another attempt");
        Object issued = ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            fetch('/api/learning/v2/activities/' + arguments[0] + '/comprehension/micro-check/state',
                { headers: window.JlasAuth.authHeaders() })
              .then(async r => done(JSON.stringify((await r.json()).data || null))).catch(e => done('ERR ' + e));
            """, activityId);
        assertTrue(!String.valueOf(issued).contains("\"ISSUED\""), "Back issued a new quiz attempt: " + issued);
    }

    private int attemptCount(String activityId) {
        Object count = ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            fetch('/api/learning/v2/activities/' + arguments[0] + '/comprehension/attempt-history',
                { headers: window.JlasAuth.authHeaders() })
              .then(async r => { const d = (await r.json()).data; done(Array.isArray(d) ? d.length : -1); })
              .catch(() => done(-1));
            """, activityId);
        return ((Number) count).intValue();
    }

    private void jsClick(WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
            "arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", element);
    }
}
