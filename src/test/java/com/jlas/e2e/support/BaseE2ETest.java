package com.jlas.e2e.support;

import io.github.bonigarcia.wdm.WebDriverManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.io.IOException;

@Tag("e2e")
public abstract class BaseE2ETest {

    protected WebDriver driver;
    protected WebDriverWait wait;
    protected SeleniumStepLog stepLog;

    @BeforeEach
    void setUpDriver(TestInfo info) throws IOException {
        stepLog = new SeleniumStepLog(info.getDisplayName());
        stepLog.step(null, "Start Chrome", "WebDriver session created", () -> {
            WebDriverManager.chromedriver().setup();
            ChromeOptions options = new ChromeOptions();
            if (E2EConfig.headless()) {
                options.addArguments("--headless=new");
            }
            options.addArguments("--window-size=1440,900");
            driver = new ChromeDriver(options);
            wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        });
    }

    @AfterEach
    void tearDownDriver() {
        if (driver != null) {
            stepLog.step(null, "Close Chrome", "WebDriver session closed", () -> driver.quit());
        }
    }
}
