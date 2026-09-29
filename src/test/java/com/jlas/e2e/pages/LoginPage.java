package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

public class LoginPage {

    private static final By EMAIL_INPUT = By.id("email");
    private static final By PASSWORD_INPUT = By.id("password");
    private static final By SUBMIT_BUTTON = By.id("submitBtn");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public LoginPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public LoginPage open(String baseUrl) {
        driver.get(baseUrl + "/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(EMAIL_INPUT));
        return this;
    }

    public void loginAs(String email, String password) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(EMAIL_INPUT)).sendKeys(email);
        driver.findElement(PASSWORD_INPUT).sendKeys(password);
        Clicks.jsClick(driver, driver.findElement(SUBMIT_BUTTON));
        // Login redirects away from /login on success (JS calls JLabAuth.getSafeRedirect()).
        wait.until(ExpectedConditions.not(ExpectedConditions.urlContains("/login")));
    }
}
