package com.jlas.e2e.pages;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;

/** Real browser interactions; no JS click or injected successful login. */
public final class LoginSmokePage {
    private final WebDriver driver;
    private final WebDriverWait wait;

    public LoginSmokePage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    public void open(String baseUrl) {
        driver.get(baseUrl + "/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("loginForm")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("email")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("password")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("submitBtn")));
    }

    public boolean passwordIsMasked() {
        return "password".equals(driver.findElement(By.id("password")).getDomAttribute("type"));
    }

    public boolean hasHorizontalOverflow() {
        return Boolean.TRUE.equals(((JavascriptExecutor) driver).executeScript(
                "return document.documentElement.scrollWidth > document.documentElement.clientWidth + 1"));
    }

    public void submitEmpty() {
        wait.until(ExpectedConditions.elementToBeClickable(By.id("submitBtn"))).click();
    }

    public void submitMalformedEmail() {
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("email"))).sendKeys("invalid-email");
        driver.findElement(By.id("password")).sendKeys("InvalidTestOnly#123");
        wait.until(ExpectedConditions.elementToBeClickable(By.id("submitBtn"))).click();
    }

    public String awaitEmailError() {
        return wait.until(d -> {
            String text = d.findElement(By.id("emailError")).getText();
            return text.isBlank() ? null : text;
        });
    }

    public String awaitPasswordError() {
        return wait.until(d -> {
            String text = d.findElement(By.id("passwordError")).getText();
            return text.isBlank() ? null : text;
        });
    }
}
