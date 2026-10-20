package com.jlas.e2e;

import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SmartTestPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OD-48 (owner 2026-10-20), spec §2.1: with the readiness gate ON, a new N3 path is refused while the catalog has no
 * N3 Reading/Listening — the learner sees which level misses which skill and no path row is created. Run only
 * against a DB copy WITHOUT the Speed Master N3 seed (never jlas, where N3 is complete).
 */
@Tag("e2e")
class N3ReadinessGateBlocksE2ETest extends BaseE2ETest {

    @Test
    void anN3PathMissingReadingAndListeningIsRefusedWithTheSkillsNamed() throws Exception {
        String baseUrl = E2EConfig.baseUrl();
        String url = System.getenv().getOrDefault("JLAS_DB_URL", "jdbc:mysql://localhost:3306/jlas");
        try (var connection = java.sql.DriverManager.getConnection(url,
                System.getenv().getOrDefault("JLAS_DB_USER", "root"), System.getenv("JLAS_DB_PASSWORD"));
             var speed = connection.prepareStatement(
                     "select count(*) from assessment_question where is_active = 1 and source_code like 'LP-OWNED-SPEED-N3-C%'");
             var rows = speed.executeQuery()) {
            rows.next();
            assertEquals(0L, rows.getLong(1), "this check needs a DB copy without the Speed Master N3 seed");
        }
        stepLog.step(driver, "Sign in", "Dedicated N3 learner is authenticated", () ->
            new LoginPage(driver).open(baseUrl).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Complete N3 survey", "N3 to N3 learning profile is saved", () ->
            survey.open(baseUrl).answerN3Profile());
        stepLog.step(driver, "Start Smart Test", "First assessment question is visible", survey::startSmartTest);
        SmartTestPage smartTest = new SmartTestPage(driver);
        stepLog.step(driver, "Complete adaptive Smart Test", "Result screen appears", () ->
            smartTest.waitUntilLoaded().completeAdaptiveTestAsCompetentLearner(60));
        stepLog.step(driver, "Ask for the path", "The gate refuses it and names the missing skills", () -> {
            smartTest.createLearningPath();
            var detail = wait.withTimeout(Duration.ofSeconds(60)).until(
                ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".ilp-state__detail")));
            assertTrue(detail.getText().contains("cấp N3 còn thiếu Đọc, Nghe"), detail.getText());
        });
        try (var connection = java.sql.DriverManager.getConnection(url,
                System.getenv().getOrDefault("JLAS_DB_USER", "root"), System.getenv("JLAS_DB_PASSWORD"));
             var query = connection.prepareStatement("""
                 select count(*) from user_learning_path p join users u on u.id = p.user_id
                 where u.email = ? and p.goal = 'JLPT' and p.target_level = 'N3'
                 """)) {
            query.setString(1, E2EConfig.userEmail());
            try (var rows = query.executeQuery()) {
                rows.next();
                assertEquals(0L, rows.getLong(1), "a refused path leaves no partial path behind");
            }
        }
    }
}
