package com.jlas.e2e;

import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A fresh learner must see every Minna listening clip in its N5 or N4 curriculum path. */
class MinnaListeningPathE2ETest extends BaseE2ETest {
    @Test
    void fromZeroPathContainsEveryChapterAndItsListeningClips() throws Exception {
        String level = System.getenv("JLAS_MINNA_TARGET_LEVEL");
        if (!"N5".equals(level) && !"N4".equals(level)) {
            throw new IllegalStateException("Set JLAS_MINNA_TARGET_LEVEL to N5 or N4");
        }
        String base = E2EConfig.baseUrl();
        stepLog.step(driver, "Sign in", "Dedicated learner authenticated", () ->
                new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        stepLog.step(driver, "Survey", "From-zero target " + level + " creates a path without Smart Test", () ->
                new SurveyPage(driver).open(base).answerFromZero(level).openFromZeroPath());

        stepLog.step(driver, "Curriculum path", "All 25 chapters per level and every Minna listening clip", () -> {
            // The first load bootstraps a full curriculum, then reads a large path payload.
            new WebDriverWait(driver, Duration.ofSeconds(120)).until(d ->
                    "false".equals(d.findElement(By.id("interactivePathRoot")).getAttribute("aria-busy")));
            new LearningPathPage(driver).waitUntilLoaded();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                const done = arguments[arguments.length - 1];
                fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() })
                  .then(async r => ({ status: r.status, body: await r.json() }))
                  .then(({ status, body }) => {
                    const path = body.data || {};
                    const curriculum = (path.packages || []).filter(p => p.phase === 'GOAL_LEARNING');
                    const reading = curriculum.flatMap(p => p.activities || []).filter(a => a.type === 'READING');
                    const listening = curriculum.flatMap(p => p.activities || []).filter(a => a.type === 'LISTENING');
                    done({ status, success: body.success, target: path.targetLevel,
                      chapters: new Set(curriculum.map(p => p.chapterLessonId)).size,
                      reading: reading.length,
                      listening: listening.length,
                      allAtomic: listening.every(a => a.estimatedMinutes === 15) });
                  }).catch(error => done({ error: String(error) }));
                """);
            assertEquals(200L, result.get("status"), result.toString());
            assertEquals(true, result.get("success"), result.toString());
            assertEquals(level, result.get("target"), result.toString());
            assertTrue(((Number) result.get("chapters")).intValue() >= ("N5".equals(level) ? 25 : 50), result.toString());
            // One Reading activity per source passage (brief §5.3), not one per chapter: the Minna seed has
            // 43 distinct N5 passages and 48 N4 passages (94/96 checks paged inside those activities).
            assertEquals("N5".equals(level) ? 43L : 91L, result.get("reading"), result.toString());
            String pathText = driver.findElement(By.id("interactivePathRoot")).getText();
            assertTrue(pathText.contains("Đọc hiểu"), "Reading skill card is not visible");
            assertTrue(pathText.contains("Nghe hiểu"), "Listening skill card is not visible");
            assertEquals("N5".equals(level) ? 100L : 204L, result.get("listening"), result.toString());
            assertEquals(true, result.get("allAtomic"), result.toString());
        });

        stepLog.step(driver, "Independent skill expansion", "Only the opened skill card grows", () -> {
            WebElement grid = driver.findElements(By.cssSelector(".ilp-skill-grid")).stream()
                    .filter(element -> element.findElements(By.cssSelector(":scope > .ilp-skill-card")).size() >= 3)
                    .findFirst().orElseThrow(() -> new IllegalStateException("No curriculum skill grid found"));
            var cards = grid.findElements(By.cssSelector(":scope > .ilp-skill-card"));
            WebElement first = cards.get(0);
            WebElement sibling = cards.get(1);
            int siblingHeight = sibling.getRect().getHeight();
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", first);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();",
                    first.findElement(By.cssSelector("[data-skill-toggle]")));
            wait.until(d -> "true".equals(first.findElement(By.cssSelector("[data-skill-toggle]"))
                    .getAttribute("aria-expanded")));
            assertTrue(first.getRect().getHeight() > sibling.getRect().getHeight(),
                    "Opening one skill stretched its sibling card");
            assertEquals(siblingHeight, sibling.getRect().getHeight(),
                    "Sibling skill card changed height when another skill expanded");
            assertTrue(sibling.findElement(By.cssSelector(".ilp-skill-card__details"))
                    .getDomAttribute("hidden") != null, "Sibling skill details opened unexpectedly");
        });

        String audioUrl = firstMinnaAudio(level);
        stepLog.step(driver, "Browser audio", "The local Minna audio loads as playable media", () -> {
            driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(25));
            @SuppressWarnings("unchecked")
            Map<String, Object> media = (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
                const url = arguments[0], done = arguments[arguments.length - 1];
                const audio = document.createElement('audio');
                let settled = false;
                const finish = (event) => {
                  if (settled) return;
                  settled = true;
                  audio.removeAttribute('src');
                  audio.load();
                  done({ event, readyState: audio.readyState });
                };
                audio.preload = 'metadata';
                audio.addEventListener('loadedmetadata', () => {
                  if (settled) return;
                  settled = true;
                  const readyState = audio.readyState;
                  audio.removeAttribute('src'); audio.load();
                  done({ event: 'loadedmetadata', readyState });
                }, { once: true });
                audio.addEventListener('error', () => finish('error:' + (audio.error?.code || 'unknown')), { once: true });
                setTimeout(() => finish('timeout'), 20000);
                audio.src = url;
                audio.load();
                """, audioUrl);
            assertEquals("loadedmetadata", media.get("event"), media.toString());
            assertTrue(((Number) media.get("readyState")).intValue() >= 1, media.toString());
        });
    }

    private static String firstMinnaAudio(String level) throws Exception {
        String url = System.getenv("JLAS_DB_URL");
        String user = System.getenv("JLAS_DB_USER");
        String password = System.getenv("JLAS_DB_PASSWORD");
        if (url == null || password == null) throw new IllegalStateException("Dedicated MySQL oracle is required");
        try (var connection = DriverManager.getConnection(url, user == null ? "root" : user, password);
             var query = connection.prepareStatement("SELECT JSON_UNQUOTE(JSON_EXTRACT(context_json, '$.audio_file')) FROM assessment_question "
                     + "WHERE source_code LIKE ? AND is_active = 1 ORDER BY id LIMIT 1")) {
            query.setString(1, "LP-OWNED-MINNA-" + level + "-L%-LISTEN-%");
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    String file = rows.getString(1);
                    if (file != null && file.startsWith("audio/vnjpclub/" + level + "/minna-")
                            && !file.contains("..")) {
                        return "/uploads/content-audio/minna/" + file.substring("audio/".length());
                    }
                }
            }
        }
        throw new IllegalStateException("No owned Minna audio URL for " + level);
    }
}
