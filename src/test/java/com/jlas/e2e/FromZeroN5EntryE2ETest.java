package com.jlas.e2e;

import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-browser entry gate only; it does not claim the whole N5 curriculum completes. */
class FromZeroN5EntryE2ETest extends BaseE2ETest {
    @Test
    void fromZeroSurveyCreatesKanaThenN5WithoutSmartTest() {
        String baseUrl = E2EConfig.baseUrl();
        stepLog.step(driver, "Sign in", "Fresh dedicated learner is authenticated", () ->
                new LoginPage(driver).open(baseUrl)
                        .loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));

        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Survey from zero", "N5 chosen without self-level assessment", () ->
                survey.open(baseUrl).answerFromZeroN5().openFromZeroPath());

        stepLog.step(driver, "Read server-owned path", "Kana precedes N5 and no Smart Test attempt exists", () -> {
            LearningPathPage path = new LearningPathPage(driver).waitUntilLoaded();
            assertTrue(path.packageCount() > 0);
            @SuppressWarnings("unchecked")
            Map<String, Object> actual = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                    const done = arguments[arguments.length - 1];
                    fetch('/api/learning/paths/current', { headers: window.JlasAuth.authHeaders() })
                      .then(async response => {
                        const body = await response.json();
                        done({ status: response.status, goal: body.data?.goal,
                          target: body.data?.targetLevel,
                          attempt: body.data?.assessmentAttemptId,
                          stages: (body.data?.stages || []).map(stage => stage.level) });
                      }).catch(error => done({ error: String(error) }));
                    """);
            assertEquals(200L, actual.get("status"));
            assertEquals("JLPT", actual.get("goal"));
            assertEquals("N5", actual.get("target"));
            assertNull(actual.get("attempt"));
            assertEquals(List.of("FOUNDATION", "N5"), actual.get("stages"));
        });
        // Brief §5.6: the summary hands the Mock to the path's own target (server-resolved) and shows no certificate
        // before a passed mock at that target; it never mixes in a Smart Test card.
        stepLog.step(driver, "Summary and Mock hand-off", "Mock targets N5; no certificate before a passed mock", () -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                    const done = arguments[arguments.length - 1];
                    fetch('/api/learning/v2/paths/current/summary', { headers: window.JlasAuth.authHeaders() })
                      .then(async response => done({ status: response.status, data: (await response.json()).data }))
                      .catch(error => done({ error: String(error) }));
                    """);
            assertEquals(200L, summary.get("status"), String.valueOf(summary));
            @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) summary.get("data");
            assertEquals(Boolean.FALSE, data.get("certificateEligible"));
            assertEquals(Boolean.FALSE, data.get("communicationGoal"));
            @SuppressWarnings("unchecked") Map<String, Object> mock = (Map<String, Object>) data.get("mockTest");
            assertEquals("N5", mock.get("targetLevel"), String.valueOf(mock));
            assertTrue(List.of("AVAILABLE", "BLOCKED_INTEGRATION").contains(mock.get("status")), String.valueOf(mock));
            driver.get(E2EConfig.baseUrl() + "/learning/path/v2/summary");
            var root = new org.openqa.selenium.support.ui.WebDriverWait(driver, java.time.Duration.ofSeconds(30)).until(d -> {
                var r = d.findElement(org.openqa.selenium.By.id("interactivePathSummaryRoot"));
                return r.findElements(org.openqa.selenium.By.cssSelector(".ilp-summary-card")).isEmpty() ? null : r;
            });
            assertTrue(driver.findElements(org.openqa.selenium.By.cssSelector("a[href='/learning/v2/certificate']")).isEmpty(),
                "no certificate link before a passed mock");
            assertFalse(root.getText().contains("Smart Test"), root.getText());
            if (Boolean.TRUE.equals(mock.get("available"))) {
                assertFalse(driver.findElements(org.openqa.selenium.By.cssSelector("a.ilp-summary__quiet[href='" + mock.get("url") + "']")).isEmpty(),
                    "the Mock link is exactly the server-resolved destination");
            } else {
                assertTrue(root.getText().contains("Thi thử JLPT (sắp có)"), root.getText());
            }
        });
    }
}
