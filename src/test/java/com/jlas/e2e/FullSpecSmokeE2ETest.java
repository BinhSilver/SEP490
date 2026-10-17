package com.jlas.e2e;

import com.jlas.e2e.pages.LoginSmokePage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.openqa.selenium.Dimension;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/** First runnable group of the full-spec case catalogue; does not certify business journeys. */
class FullSpecSmokeE2ETest extends BaseE2ETest {
    @ParameterizedTest(name = "AUTH-01_UX-01_login_{0}x{1}")
    @CsvSource({"1440,900", "375,812"})
    void loginFormIsUsable(int width, int height) {
        LoginSmokePage page = new LoginSmokePage(driver);
        stepLog.step(driver, "Set browser size", "Requested test window size",
                () -> driver.manage().window().setSize(new Dimension(width, height)));
        stepLog.step(driver, "Open login", "Email, password and submit visible", () -> page.open(E2EConfig.baseUrl()));
        stepLog.step(driver, "Check password field", "Password masked", () -> assertTrue(page.passwordIsMasked()));
        stepLog.step(driver, "Check viewport", "No horizontal overflow", () -> assertFalse(page.hasHorizontalOverflow()));
    }

    @Test
    void AUTH_02_emptyFormShowsErrors() {
        LoginSmokePage page = new LoginSmokePage(driver);
        stepLog.step(driver, "Open login", "Login form visible", () -> page.open(E2EConfig.baseUrl()));
        stepLog.step(driver, "Submit empty form", "Both fields rejected", () -> {
            page.submitEmpty();
            assertFalse(page.awaitEmailError().isBlank());
            assertFalse(page.awaitPasswordError().isBlank());
            assertEquals("/login", URI.create(driver.getCurrentUrl()).getPath());
        });
    }

    @Test
    void AUTH_03_malformedEmailShowsError() {
        LoginSmokePage page = new LoginSmokePage(driver);
        stepLog.step(driver, "Open login", "Login form visible", () -> page.open(E2EConfig.baseUrl()));
        stepLog.step(driver, "Submit malformed email", "Email rejected; remain on login", () -> {
            page.submitMalformedEmail();
            assertFalse(page.awaitEmailError().isBlank());
            assertEquals("/login", URI.create(driver.getCurrentUrl()).getPath());
        });
    }
}
