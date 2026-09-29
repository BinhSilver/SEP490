package com.jlas.e2e.pages;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Several J-LAS screens use sticky footers / animated overlays that make an
 * element report as present while a native WebDriver click still gets
 * intercepted. Scrolling the element into view and dispatching the click via
 * JS sidesteps that without resorting to Thread.sleep().
 */
final class Clicks {

    private Clicks() {
    }

    static void jsClick(WebDriver driver, WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
            "arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", element);
    }
}
