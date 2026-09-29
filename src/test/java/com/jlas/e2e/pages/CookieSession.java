package com.jlas.e2e.pages;

import org.openqa.selenium.Cookie;
import org.openqa.selenium.WebDriver;

/**
 * Establishes a logged-in session by injecting the app's own "access_token"
 * cookie (captured manually from DevTools after a real Google sign-in),
 * instead of automating Google's sign-in page — Google actively blocks
 * automated logins and we never want a bot script touching the user's
 * Google credentials.
 */
public final class CookieSession {

    private CookieSession() {
    }

    /**
     * Loads a neutral same-origin page first (a cookie can only be added
     * once the browser is already on that domain), then attaches the token
     * and reloads the target path.
     */
    public static void loginWithAccessTokenCookie(WebDriver driver, String baseUrl, String accessTokenValue) {
        driver.get(baseUrl + "/login");
        driver.manage().addCookie(new Cookie.Builder("access_token", accessTokenValue)
            .path("/")
            .build());
        driver.get(baseUrl + "/");
    }
}
