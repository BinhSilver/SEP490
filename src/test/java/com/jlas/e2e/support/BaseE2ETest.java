package com.jlas.e2e.support;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.io.IOException;
import java.util.Map;

@Tag("e2e")
public abstract class BaseE2ETest {

    /**
     * Re-issues the access token through the persisted refresh token (the same single-use
     * {@code POST /api/auth/refresh} the login flow stores). The app has no silent access-token
     * refresh, so an expired token makes the global fetch wrapper redirect to {@code /login} and
     * silently kills server-timed flows (e.g. the dialogue heartbeat) mid-journey. This mirrors a
     * real client keeping its session alive; it never reduces the 180s dialogue timer or fakes
     * completion. Run on any page that has loaded {@code auth-session.js}.
     */
    private static final String REFRESH_SESSION_SCRIPT = """
        var done = arguments[arguments.length - 1];
        if (!window.JlasAuth) {
          done('NO_AUTH');
        } else {
          var refresh = localStorage.getItem('refreshToken');
          if (!refresh) {
            done('NO_REFRESH');
          } else {
            fetch('/api/auth/refresh', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ refreshToken: refresh })
            }).then(function (r) {
              return r.json().then(function (b) { return { status: r.status, body: b }; });
            }).then(function (res) {
              var d = res.body && res.body.data;
              if (res.status !== 200 || !d || !d.accessToken) {
                done('REFRESH_FAILED:' + res.status);
              } else {
                window.JlasAuth.setSession(d.accessToken, d.refreshToken || refresh, d.user || null);
                done('REFRESHED');
              }
            }).catch(function (e) {
              done('REFRESH_ERROR:' + e.message);
            });
          }
        }
        """;

    protected WebDriver driver;
    protected WebDriverWait wait;
    protected SeleniumStepLog stepLog;
    private long lastKeepAliveAt;

    @BeforeEach
    void setUpDriver(TestInfo info) throws IOException {
        stepLog = new SeleniumStepLog(info.getDisplayName());
        stepLog.step(null, "Start Chrome", "WebDriver session created", () -> {
            // A pinned local driver keeps the E2E runner usable without network access.
            String localDriver = System.getenv("JLAS_CHROMEDRIVER_PATH");
            if (localDriver != null && !localDriver.isBlank()) {
                System.setProperty("webdriver.chrome.driver", localDriver);
            } else if (System.getProperty("webdriver.chrome.driver") == null) {
                WebDriverManager.chromedriver().setup();
            }
            ChromeOptions options = new ChromeOptions();
            if (E2EConfig.headless()) {
                options.addArguments("--headless=new");
            }
            options.addArguments("--window-size=1440,900");
            // Keep the page "visible" in headless mode: Chrome may otherwise treat the renderer as
            // backgrounded/occluded mid-run and flip document.visibilityState to "hidden", which freezes
            // server-timed (visibility-gated) flows such as the dialogue heartbeat.
            options.addArguments("--disable-backgrounding-occluded-windows");
            options.addArguments("--disable-renderer-backgrounding");
            options.addArguments("--disable-background-timer-throttling");
            driver = new ChromeDriver(options);
            keepRendererActive();
            wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            lastKeepAliveAt = System.currentTimeMillis();
        });
    }

    /**
     * Keeps the headless renderer genuinely active so Chromium never treats the page as idle or
     * backgrounded and throttles its own {@code setInterval} timers. That throttling is the root cause
     * behind the dialogue heartbeat stalling at ~174s deep into a long journey: a throttled heartbeat
     * lets gaps exceed the server's 30s gap-reset, zeroing the contribution. {@code Emulation.setIdleOverride}
     * and {@code Page.setWebLifecycleState} are real CDP signals (active user, non-frozen page) — not a JS
     * patch of {@code document.visibilityState} — so a real learner's focused tab is what gets emulated.
     */
    private void keepRendererActive() {
        try {
            ChromeDriver chrome = (ChromeDriver) driver;
            chrome.executeCdpCommand("Emulation.setIdleOverride",
                Map.of("isUserActive", true, "isScreenUnlocked", true));
            chrome.executeCdpCommand("Page.setWebLifecycleState", Map.of("state", "active"));
            // A visible (non-headless) window the owner is watching may lose focus behind other windows;
            // keep the page focused so visibility-gated flows (dialogue heartbeat) behave like a learner's tab.
            chrome.executeCdpCommand("Emulation.setFocusEmulationEnabled", Map.of("enabled", true));
        } catch (Exception e) {
            System.out.println("[e2e] keepRendererActive: " + e.getMessage());
        }
    }

    /**
     * Keeps the access token fresh across a journey longer than its 15-minute TTL. No-op unless at
     * least 8 minutes have passed since the last refresh; failures are logged, not thrown, because a
     * still-valid token lets the journey continue and the next tick retries.
     */
    protected void keepSessionAlive() {
        keepRendererActive();
        long now = System.currentTimeMillis();
        if (now - lastKeepAliveAt < 8 * 60_000L) return;
        lastKeepAliveAt = now;
        Object result;
        try {
            result = ((JavascriptExecutor) driver).executeAsyncScript(REFRESH_SESSION_SCRIPT);
        } catch (Exception e) {
            System.out.println("[e2e] keepSessionAlive: script error: " + e.getMessage());
            return;
        }
        if (result != null && !"REFRESHED".equals(result.toString())) {
            System.out.println("[e2e] keepSessionAlive: " + result);
        }
    }

    @AfterEach
    void tearDownDriver() {
        if (driver != null) {
            stepLog.step(null, "Close Chrome", "WebDriver session closed", () -> driver.quit());
        }
    }
}
