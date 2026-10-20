package com.jlas.e2e;

import com.jlas.e2e.pages.InteractiveActivityPage;
import com.jlas.e2e.pages.LearningPathPage;
import com.jlas.e2e.pages.LoginPage;
import com.jlas.e2e.pages.SurveyPage;
import com.jlas.e2e.support.BaseE2ETest;
import com.jlas.e2e.support.E2EConfig;
import com.jlas.e2e.support.JdbcAssessmentAnswerOracle;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §2.24 / brief §5.5 on real seeded Travel content: survey-only entry, every situation selectable at once, a
 * NON-first situation chosen freely, its three parts started in any order and saved separately, leave/reload
 * resumes, no scored quiz anywhere, and each unit completes only after every part it carries.
 * Requires JLAS_GOAL (default TRAVEL), a fresh learner, and JLAS_DB_* for the shared activity page helper.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommunicationSituationJourneyE2ETest extends BaseE2ETest {
    private static final String STATE_SCRIPT = """
        const done = arguments[arguments.length - 1];
        fetch('/api/learning/v2/paths/current', { headers: window.JlasAuth.authHeaders() })
          .then(async response => {
            const body = await response.json();
            const path = body.data || {};
            const packages = (path.packages || []).map(pkg => ({
              id: String(pkg.id), chapter: pkg.chapterLessonId == null ? null : String(pkg.chapterLessonId),
              status: pkg.status, phase: pkg.phase,
              activities: (pkg.activities || []).map(a => ({ id: String(a.id), type: a.type, state: a.state })) }));
            done({ status: response.status, goal: path.goal, pathStatus: path.status, packages });
          }).catch(error => done({ error: String(error) }));
        """;

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void anyUnitCanBeStudiedPartByPartWithoutQuizAndCompletesOnlyAfterAllItsParts() {
        String base = E2EConfig.baseUrl();
        String goal = System.getenv().getOrDefault("JLAS_GOAL", "TRAVEL");
        stepLog.step(driver, "Log in", "Fresh dedicated learner is authenticated", () ->
            new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword()));
        SurveyPage survey = new SurveyPage(driver);
        stepLog.step(driver, "Onboarding survey " + goal, "Survey-only goal never offers Smart Test", () -> {
            survey.open(base).answerUsageGoal(goal);
            survey.openGoalSurvey(goal);
        });
        stepLog.step(driver, "Goal survey", "Path is created and the roadmap opens", () -> submitGoalSurvey(goal));
        new LearningPathPage(driver).waitUntilLoaded();

        Map<String, Object> initial = state();
        assertNull(initial.get("error"), initial.toString());
        assertEquals(goal, initial.get("goal"));
        List<Map<String, Object>> packages = (List<Map<String, Object>>) initial.get("packages");
        assertTrue(packages.size() > 1, "Expected several situations: " + packages.size());
        assertTrue(packages.stream().noneMatch(pkg -> "LOCKED".equals(pkg.get("status"))),
            "Communication situations must all be selectable at once: " + packages);

        // Current data model: vocabulary-topic situations (Vocabulary only, kept separate by owner decision) and
        // dialogue situations (Grammar + Dialogue). Pick a non-first one of each to prove free choice; the
        // separate test below checks every unit follows that approved model (owner 2026-10-19).
        Map<String, Object> chosen = null;
        Map<String, Object> topic = null;
        for (int i = packages.size() - 1; i > 0; i--) {
            Map<String, Object> candidate = packages.get(i);
            List<String> types = types(candidate);
            if (chosen == null && types.containsAll(List.of("GRAMMAR", "DIALOGUE"))) chosen = candidate;
            if (topic == null && types.equals(List.of("VOCABULARY")) && i > 1
                && ((List<?>) candidate.get("activities")).size() == 1) topic = candidate;
        }
        assertTrue(chosen != null, "No non-first situation has Grammar and Dialogue: " + packages);
        assertTrue(topic != null, "No non-first single-part vocabulary situation: " + packages);
        String packageId = (String) chosen.get("id");
        String topicPackageId = (String) topic.get("id");
        assertNotEquals(packages.get(0).get("id"), packageId);
        Map<String, String> ids = activityIds(chosen);
        ids.put("VOCABULARY", activityIds(topic).get("VOCABULARY"));

        stepLog.step(driver, "Start the chosen situation from its Grammar card", "Roadmap opens Grammar first", () -> {
            driver.get(base + "/learning/path/v2");
            new LearningPathPage(driver).waitUntilLoaded();
            WebElement start = new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> d.findElements(
                    By.cssSelector("[data-start-package='" + packageId + "'][data-preferred-activity]")).stream()
                .filter(button -> ids.get("GRAMMAR").equals(button.getAttribute("data-preferred-activity")))
                .findFirst().orElse(null));
            jsClick(start);
            wait.until(ExpectedConditions.urlContains("/learning/v2/grammar"));
        });

        InteractiveActivityPage activity = new InteractiveActivityPage(driver, new JdbcAssessmentAnswerOracle());
        stepLog.step(driver, "Grammar practice", "Grammar passes without any quiz", activity::completeWithoutQuiz);
        assertTrue(Boolean.TRUE.equals(postFails("/api/learning/v2/activities/" + ids.get("GRAMMAR") + "/grammar/micro-check")),
            "A communication Grammar part must not issue a scored micro-check");
        Map<String, String> afterGrammar = states(packageId);
        assertTrue(List.of("PASSED", "COMPLETED").contains(afterGrammar.get("GRAMMAR")), afterGrammar.toString());
        assertFalse(List.of("PASSED", "COMPLETED").contains(afterGrammar.get("DIALOGUE")), afterGrammar.toString());
        assertNotEquals("COMPLETED", packageStatus(packageId), "Situation must not complete after one part");

        stepLog.step(driver, "Leave and resume Vocabulary", "Flipped cards survive a reload", () -> {
            driver.get(base + "/learning/path/v2");
            new LearningPathPage(driver).waitUntilLoaded();
            jsClick(new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> d.findElements(By.cssSelector(
                "[data-start-package='" + topicPackageId + "'][data-preferred-activity]")).stream().findFirst().orElse(null)));
            wait.until(ExpectedConditions.urlContains("/learning/v2/vocabulary"));
            int flipped = activity.flipFirstVocabularyCards(2);
            assertTrue(flipped > 0, "No vocabulary card could be flipped");
            driver.get(base + "/learning/path/v2");
            new LearningPathPage(driver).waitUntilLoaded();
            driver.navigate().refresh();
            new LearningPathPage(driver).waitUntilLoaded();
            Map<String, Object> study = getJson("/api/learning/v2/activities/" + ids.get("VOCABULARY") + "/vocabulary/study");
            Map<String, Object> saved = (Map<String, Object>) study.get("data");
            assertTrue(saved != null && ((Number) saved.get("flippedCount")).intValue() >= flipped,
                "Saved flip state was not returned after leaving and reloading: " + study);
            driver.get(base + "/learning/v2/vocabulary?activity=" + ids.get("VOCABULARY"));
            activity.completeWithoutQuiz();
        });
        assertTrue(Boolean.TRUE.equals(postFails("/api/learning/v2/activities/" + ids.get("VOCABULARY") + "/vocabulary/micro-check")),
            "A communication Vocabulary part must not issue a scored micro-check");
        assertEquals("COMPLETED", packageStatus(topicPackageId), "A one-part vocabulary situation completes with its part");
        assertNotEquals("COMPLETED", packageStatus(packageId), "Dialogue situation must wait for its Dialogue part");

        stepLog.step(driver, "Dialogue aids", "Từ & cụm từ panel, furigana by default, furigana toggle and jump work", () -> {
            driver.get(base + "/learning/v2/dialogue?activity=" + ids.get("DIALOGUE"));
            WebDriverWait aidWait = new WebDriverWait(driver, Duration.ofSeconds(30));
            WebElement panel = aidWait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(".ilp-dialogue__phrases")));
            assertTrue(panel.findElements(By.cssSelector(".ilp-dialogue__phrase")).size() >= 5, "5–8 phrases per dialogue");
            assertTrue(panel.getText().contains("Nội dung bổ sung J-LAS"), "phrases are labelled as a J-LAS supplement");
            assertFalse(driver.findElements(By.cssSelector(".ilp-dialogue__ja ruby")).isEmpty(), "furigana is on by default");
            jsClick(driver.findElement(By.cssSelector("[data-toggle-furigana]")));
            aidWait.until(d -> d.findElements(By.cssSelector(".ilp-dialogue__ja ruby")).isEmpty());
            jsClick(driver.findElement(By.cssSelector("[data-toggle-furigana]")));
            aidWait.until(d -> !d.findElements(By.cssSelector(".ilp-dialogue__ja ruby")).isEmpty());
            jsClick(driver.findElement(By.cssSelector("[data-jump-line]")));
            aidWait.until(d -> !d.findElements(By.cssSelector(".ilp-dialogue__line.is-highlighted")).isEmpty());
        });

        stepLog.step(driver, "Dialogue practice", "Dialogue reaches its server-owned completion", () -> {
            keepSessionAlive();
            driver.get(base + "/learning/v2/dialogue?activity=" + ids.get("DIALOGUE"));
            activity.completeWithoutQuiz();
            keepSessionAlive();
        });

        stepLog.step(driver, "End summary", "The completed dialogue ends with Tóm tắt cuối bài", () -> {
            driver.get(base + "/learning/v2/dialogue?activity=" + ids.get("DIALOGUE"));
            WebElement summary = new WebDriverWait(driver, Duration.ofSeconds(30)).until(
                ExpectedConditions.presenceOfElementLocated(By.cssSelector(".ilp-dialogue__summary")));
            assertTrue(summary.getText().contains("Tóm tắt cuối bài"), summary.getText());
            assertTrue(summary.getText().contains("Mẫu câu đã dùng"), summary.getText());
            assertTrue(summary.getText().contains("Từ & cụm từ nên nhớ"), summary.getText());
        });

        stepLog.step(driver, "Situation completion", "Only the chosen situation is completed", () -> {
            assertEquals("COMPLETED", packageStatus(packageId), "Dialogue situation completes after Grammar and Dialogue");
            Map<String, Object> after = state();
            List<Map<String, Object>> all = (List<Map<String, Object>>) after.get("packages");
            assertEquals(2L, all.stream().filter(pkg -> "COMPLETED".equals(pkg.get("status"))).count(), all.toString());
            assertNotEquals("COMPLETED", after.get("pathStatus"), "Other situations remain to be studied");
        });
    }

    private void submitGoalSurvey(String goal) {
        WebDriverWait surveyWait = new WebDriverWait(driver, Duration.ofSeconds(30));
        surveyWait.until(d -> !d.findElements(By.cssSelector("#goalSurveyContent .goal-survey-question")).isEmpty()
            || !d.findElements(By.cssSelector("#goalSurveyContent .goal-survey-unavailable")).isEmpty());
        if (!driver.findElements(By.cssSelector("#goalSurveyContent .goal-survey-unavailable")).isEmpty()) {
            throw new IllegalStateException("Goal survey failed to load for " + goal);
        }
        for (WebElement question : driver.findElements(By.cssSelector("#goalSurveyContent .goal-survey-question"))) {
            WebElement label = question.findElements(By.cssSelector("label.survey-option")).stream()
                .filter(WebElement::isDisplayed).findFirst()
                .orElseThrow(() -> new IllegalStateException("No selectable option for goal " + goal));
            jsClick(label);
            WebElement input = label.findElement(By.cssSelector("input"));
            surveyWait.until(d -> input.isSelected());
        }
        jsClick(surveyWait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("#goalSurveyForm .survey-next"))));
        new WebDriverWait(driver, Duration.ofSeconds(60)).until(ExpectedConditions.urlContains("/learning/path/v2"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> state() {
        return (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript(STATE_SCRIPT);
    }

    @SuppressWarnings("unchecked")
    private static List<String> types(Map<String, Object> pkg) {
        return ((List<Map<String, Object>>) pkg.get("activities")).stream()
            .map(a -> (String) a.get("type")).distinct().toList();
    }

    /** §2.24 (owner 2026-10-19): vocabulary-topic units carry Vocabulary only; dialogue units carry Grammar + Dialogue. */
    @Test
    @Order(2)
    @SuppressWarnings("unchecked")
    void everyUnitFollowsTheApprovedCommunicationModel() {
        String base = E2EConfig.baseUrl();
        String goal = System.getenv().getOrDefault("JLAS_GOAL", "TRAVEL");
        new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword());
        driver.get(base + "/learning/path/v2");
        new LearningPathPage(driver).waitUntilLoaded();
        Map<String, Object> current = state();
        if (!goal.equals(current.get("goal"))) {
            SurveyPage survey = new SurveyPage(driver);
            survey.open(base).answerUsageGoal(goal);
            survey.openGoalSurvey(goal);
            submitGoalSurvey(goal);
            new LearningPathPage(driver).waitUntilLoaded();
            current = state();
        }
        List<Map<String, Object>> units = (List<Map<String, Object>>) current.get("packages");
        List<String> unexpected = units.stream()
            .filter(pkg -> !types(pkg).equals(List.of("VOCABULARY")) && !(types(pkg).size() == 2
                && types(pkg).containsAll(List.of("GRAMMAR", "DIALOGUE"))))
            .map(pkg -> pkg.get("chapter") + "=" + types(pkg)).toList();
        assertTrue(unexpected.isEmpty(), "Units outside the approved model: " + unexpected);
        assertTrue(units.stream().anyMatch(pkg -> types(pkg).equals(List.of("VOCABULARY"))), "No vocabulary-topic unit");
        assertTrue(units.stream().anyMatch(pkg -> types(pkg).contains("DIALOGUE")), "No dialogue unit");
    }

    /** §2.24 C1/C3 (owner 2026-10-19): roadmap grouped by topic, survey focus first, vocabulary then situations easy → hard. */
    @Test
    @Order(3)
    @SuppressWarnings("unchecked")
    void roadmapGroupsUnitsByTopicWithSurveyFocusFirst() {
        String base = E2EConfig.baseUrl();
        new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword());
        stepLog.step(driver, "Open the roadmap", "Units are grouped under topic headers", () -> {
            driver.get(base + "/learning/path/v2");
            new LearningPathPage(driver).waitUntilLoaded();
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> !d.findElements(By.cssSelector(".ilp-topic")).isEmpty());
        });
        Map<String, Object> guideResponse = getJson("/api/learning/v2/paths/current");
        Map<String, Object> guide = (Map<String, Object>) ((Map<String, Object>) guideResponse.get("data")).get("communicationGuide");
        assertTrue(guide != null, "Communication path must expose its topic guide: " + guideResponse);
        List<Map<String, Object>> topics = (List<Map<String, Object>>) guide.get("topics");
        assertTrue(topics.size() > 1, "Expected several topics: " + topics);

        List<WebElement> sections = driver.findElements(By.cssSelector(".ilp-topic"));
        assertEquals(topics.stream().map(t -> t.get("code")).toList(),
            sections.stream().map(s -> s.getAttribute("data-topic")).toList(), "Roadmap topic order follows the guide");
        boolean seenOrdinary = false;
        for (Map<String, Object> topic : topics) {
            boolean priority = Boolean.TRUE.equals(topic.get("priority"));
            assertFalse(priority && seenOrdinary, "Survey focus topics must come first: " + topics);
            seenOrdinary |= !priority;
            List<Map<String, Object>> units = (List<Map<String, Object>>) topic.get("units");
            assertTrue(Boolean.TRUE.equals(units.get(0).get("vocabularyTopic")), "Topic must open with its vocabulary: " + topic);
            int previous = 0;
            for (Map<String, Object> unit : units.subList(1, units.size())) {
                int rank = List.of("N5", "N4", "N3", "N2", "N1").indexOf(String.valueOf(unit.get("jlptLevel"))) + 1;
                assertTrue(rank > 0 && rank >= previous, "Situations must run easy → hard with a level: " + topic);
                previous = rank;
            }
        }
        assertTrue(topics.stream().anyMatch(t -> Boolean.TRUE.equals(t.get("priority"))), "The survey focus must be marked");
        // Inactive topics stay in the page but hidden, so read their text content rather than the visible text.
        String firstText = sections.get(0).getDomProperty("textContent");
        assertTrue(firstText.contains("Ưu tiên của bạn"), "Focus chip missing on the first topic: " + firstText);
        assertTrue(firstText.contains("Nên học trước: từ vựng chủ đề"), firstText);
        assertFalse(firstText.contains("Chọn kỹ năng muốn học"), "the repeated skill header is gone: " + firstText);
        assertTrue(driver.findElements(By.cssSelector(".ilp-topic .ilp-skill-part__lock")).isEmpty(),
            "Communication units are never locked");

        // Owner 2026-10-20 (option A): one topic per page behind a tab bar, remembered in the URL across a reload.
        List<WebElement> tabs = driver.findElements(By.cssSelector("[data-topic-tab]"));
        assertEquals(topics.stream().map(t -> t.get("code")).toList(),
            tabs.stream().map(tab -> tab.getAttribute("data-topic-tab")).toList(), "Topic tabs follow the guide order");
        assertEquals(1, tabs.stream().filter(tab -> "true".equals(tab.getAttribute("aria-selected"))).count(), "one topic selected");
        assertEquals(1, sections.stream().filter(WebElement::isDisplayed).count(), "only the selected topic is shown");
        WebElement other = tabs.stream().filter(tab -> !"true".equals(tab.getAttribute("aria-selected"))).findFirst().orElseThrow();
        String otherCode = other.getAttribute("data-topic-tab");
        stepLog.step(driver, "Switch topic", "Only the chosen topic shows and the URL remembers it", () -> {
            other.click();
            wait.until(d -> d.findElement(By.id("ilp-topic-" + otherCode)).isDisplayed());
            assertTrue(driver.getCurrentUrl().endsWith("#chu-de=" + otherCode), driver.getCurrentUrl());
            driver.navigate().refresh();
            new LearningPathPage(driver).waitUntilLoaded();
            wait.until(d -> d.findElement(By.id("ilp-topic-" + otherCode)).isDisplayed());
            assertEquals("true", driver.findElement(By.cssSelector("[data-topic-tab='" + otherCode + "']")).getAttribute("aria-selected"));
        });
        stepLog.step(driver, "Open a situation's parts", "Its parts list opens inside the card", () -> {
            WebElement panel = driver.findElement(By.id("ilp-topic-" + otherCode));
            WebElement toggle = panel.findElements(By.cssSelector(".ilp-situation [data-situation-toggle]")).get(0);
            jsClick(toggle);
            wait.until(d -> "true".equals(toggle.getAttribute("aria-expanded")));
            assertTrue(driver.findElement(By.id(toggle.getAttribute("aria-controls"))).isDisplayed(), "parts list visible");
        });

        // §2.24 Q5: only BrSE shows optional J-LAS book suggestions; every offered link opens a learner-visible textbook.
        String goal = System.getenv().getOrDefault("JLAS_GOAL", "TRAVEL");
        List<WebElement> books = driver.findElements(By.cssSelector(".ilp-books"));
        if (!"BA".equals(goal)) {
            assertTrue(books.isEmpty(), "Only BrSE paths suggest books");
            return;
        }
        assertEquals(1, books.size(), "BrSE roadmap shows the optional book suggestions");
        assertTrue(books.get(0).getText().contains("Sách tham khảo (tự nguyện)"), books.get(0).getText());
        List<String> hrefs = books.get(0).findElements(By.cssSelector("a.ilp-books__link")).stream()
            .map(link -> link.getAttribute("href")).toList();
        int offered = hrefs.size() + books.get(0).findElements(By.cssSelector(".ilp-books__pending")).size();
        assertTrue(offered >= 1, "At least one book is suggested below N2");
        // OD-45 (owner 2026-10-20): the N3 link opens the J-LAS N3 textbook while it is DRAFT, labelled "Chưa duyệt".
        List<WebElement> unreviewed = books.get(0).findElements(By.cssSelector(".ilp-books__unreviewed"));
        boolean draftN3Offered = books.get(0).getText().contains("Tiếng Nhật N3");
        if (draftN3Offered) {
            assertTrue(hrefs.stream().anyMatch(href -> href.endsWith("/textbooks/4")), "N3 book is a link: " + hrefs);
            assertEquals(1, unreviewed.size(), "the DRAFT N3 book is labelled unreviewed");
            assertEquals("Chưa duyệt", unreviewed.get(0).getText().trim());
            assertTrue(books.get(0).findElements(By.cssSelector(".ilp-books__pending")).isEmpty(), "no 'đang biên soạn' left");
        }
        for (String href : hrefs) {
            assertTrue(href.matches(".*/textbooks/\\d+$"), href);
            driver.get(href);
            new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> d.getTitle() != null && !d.getTitle().isBlank());
            assertFalse(driver.getPageSource().contains("Whitelabel Error"), "Suggested textbook page opens: " + href);
            // The units load through the learner API; a hidden book would show the not-found message instead.
            WebElement meta = new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> {
                var el = d.findElement(By.id("textbookMeta"));
                return el.getText().isBlank() ? null : el;
            });
            assertFalse(driver.getPageSource().contains("Không tìm thấy giáo trình"), "learner can open " + href);
            if (href.endsWith("/textbooks/4")) {
                assertTrue(meta.getText().contains("Chưa duyệt"), "DRAFT N3 page shows its unreviewed badge: " + meta.getText());
                stepLog.step(driver, "Open the DRAFT N3 textbook", "Units load and the page says Chưa duyệt", () -> { });
            }
        }
    }

    /** §2.24 G1–G3 (owner 2026-10-19): the optional reply challenge — a wrong pick costs nothing, the first full clear
     * pays 2 CP exactly once, a replay clears again without paying. */
    @Test
    @Order(4)
    @SuppressWarnings("unchecked")
    void optionalReplyChallengePaysTwoCreditsOnlyOnTheFirstClear() throws Exception {
        String base = E2EConfig.baseUrl();
        new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword());
        driver.get(base + "/learning/path/v2");
        new LearningPathPage(driver).waitUntilLoaded();
        List<Map<String, Object>> packages = (List<Map<String, Object>>) state().get("packages");
        Map<String, Object> dialoguePackage = packages.stream().filter(pkg -> types(pkg).contains("DIALOGUE")).findFirst().orElseThrow();
        String activityId = activityIds(dialoguePackage).get("DIALOGUE");
        String packageId = (String) dialoguePackage.get("id");
        long lessonId = Long.parseLong((String) dialoguePackage.get("chapter"));
        String api = "/api/learning/v2/activities/" + activityId + "/dialogue";
        // A learner opens the dialogue from its roadmap card, which starts the activity.
        jsClick(new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> d.findElements(
                By.cssSelector("[data-start-package='" + packageId + "'][data-preferred-activity]")).stream()
            .filter(button -> activityId.equals(button.getAttribute("data-preferred-activity"))).findFirst().orElse(null)));
        wait.until(ExpectedConditions.urlContains("/learning/v2/dialogue"));
        Map<String, String> scriptById = new java.util.HashMap<>();
        Map<String, Object> study = getJson(api + "/study");
        assertTrue(study.get("data") != null, "dialogue study state of activity " + activityId + ": " + study);
        for (Map<String, Object> line : (List<Map<String, Object>>) ((Map<String, Object>) study.get("data")).get("lines")) {
            scriptById.put(String.valueOf(line.get("lineId")), (String) line.get("japanese"));
        }
        Map<String, Object> view = (Map<String, Object>) getJson(api + "/challenge").get("data");
        assertEquals(Boolean.TRUE, view.get("available"), "every communication dialogue has a reply challenge: " + view);
        List<Map<String, Object>> turns = (List<Map<String, Object>>) view.get("turns");
        List<String> right = new java.util.ArrayList<>();
        List<String> wrong = new java.util.ArrayList<>();
        for (Map<String, Object> turn : turns) {
            String answer = scriptById.get(String.valueOf(turn.get("lineId")));
            for (Map<String, Object> option : (List<Map<String, Object>>) turn.get("options")) {
                assertFalse(option.containsKey("correct"), "options never carry the key");
                (answer.equals(option.get("japanese")) ? right : wrong).add((String) option.get("id"));
            }
        }
        assertEquals(turns.size(), right.size(), "exactly one real reply per turn");

        stepLog.step(driver, "Challenge: wrong reply", "Feedback shows the right reply and offers a free retry", () -> {
            driver.get(base + "/learning/v2/dialogue?activity=" + activityId);
            jsClick(new WebDriverWait(driver, Duration.ofSeconds(30)).until(
                ExpectedConditions.presenceOfElementLocated(By.cssSelector("[data-challenge-start]"))));
            jsClick(waitFor("[data-challenge-option='" + wrong.get(0) + "']"));
            assertTrue(waitFor(".ilp-challenge__feedback.is-fail").getText().contains("không mất gì"));
            assertFalse(driver.findElements(By.cssSelector("[data-challenge-option='" + right.get(0) + "'].is-correct")).isEmpty());
            jsClick(waitFor("[data-challenge-retry]"));
        });
        stepLog.step(driver, "Challenge: first clear", "All turns right pays +2 CP once", () -> {
            playEveryTurn(right);
            assertTrue(waitFor(".ilp-challenge h1").getText().contains("Vượt ải thành công"));
            assertTrue(driver.findElement(By.cssSelector(".ilp-challenge")).getText().contains("+2 CP"));
        });
        assertEquals(1, challengeLedger(lessonId), "exactly one 2 CP transaction for this situation");
        stepLog.step(driver, "Challenge: replay", "A second clear pays nothing", () -> {
            jsClick(waitFor("[data-challenge-retry]"));
            playEveryTurn(right);
            assertTrue(waitFor(".ilp-challenge").getText().contains("không cộng thêm CP"));
        });
        assertEquals(1, challengeLedger(lessonId), "a replay never pays again");
    }

    /** §2.24 / brief §5.5–5.6: a communication summary has no Kanji/Reading/Listening/Smart Test cards, no JLPT Mock and no
     * certificate while situations remain. */
    @Test
    @Order(5)
    @SuppressWarnings("unchecked")
    void communicationSummaryShowsOnlyItsOwnSkillsWithoutMockOrCertificate() {
        String base = E2EConfig.baseUrl();
        new LoginPage(driver).open(base).loginAs(E2EConfig.userEmail(), E2EConfig.userPassword());
        Map<String, Object> summary = (Map<String, Object>) getJson("/api/learning/v2/paths/current/summary").get("data");
        assertTrue(summary != null, "summary API answers for the current communication path");
        assertEquals(Boolean.TRUE, summary.get("communicationGoal"));
        assertEquals(Boolean.FALSE, summary.get("certificateEligible"), "situations remain, so no certificate");
        List<String> skills = ((List<Map<String, Object>>) summary.get("skills")).stream().map(s -> (String) s.get("skill")).toList();
        assertTrue(skills.stream().noneMatch(s -> List.of("KANJI", "READING", "LISTENING").contains(s)), "skills " + skills);
        assertTrue(((List<?>) summary.get("comprehensionInPath")).isEmpty(), "no Reading/Listening evidence on communication paths");
        stepLog.step(driver, "Communication summary", "Own skills only; no mock button, no certificate", () -> {
            driver.get(base + "/learning/path/v2/summary");
            WebElement root = new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> {
                WebElement r = d.findElement(By.id("interactivePathSummaryRoot"));
                return r.findElements(By.cssSelector(".ilp-summary-card")).isEmpty() ? null : r;
            });
            String text = root.getText();
            assertFalse(text.contains("Thi thử JLPT"), "no JLPT mock for communication goals: " + text);
            assertFalse(text.contains("Xem chứng nhận"), text);
            assertFalse(text.contains("Smart Test"), text);
            assertTrue(driver.findElements(By.cssSelector("a[href='/learning/v2/certificate']")).isEmpty());
        });
    }

    private void playEveryTurn(List<String> right) {
        for (String option : right) {
            jsClick(waitFor("[data-challenge-option='" + option + "']:not([disabled])"));
            waitFor(".ilp-challenge__feedback.is-pass");
            jsClick(waitFor("[data-challenge-next]"));
        }
        new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> d.findElements(By.cssSelector(".ilp-result.ilp-challenge")).size() > 0);
    }

    private WebElement waitFor(String css) {
        return new WebDriverWait(driver, Duration.ofSeconds(30)).until(ExpectedConditions.presenceOfElementLocated(By.cssSelector(css)));
    }

    /** 2 CP ledger rows of the challenge for the dedicated test learner (asserting its clear row exists too). */
    private int challengeLedger(long lessonId) throws Exception {
        String url = System.getenv().getOrDefault("JLAS_DB_URL", "jdbc:mysql://localhost:3306/jlas");
        try (var connection = java.sql.DriverManager.getConnection(url, System.getenv().getOrDefault("JLAS_DB_USER", "root"),
                System.getenv("JLAS_DB_PASSWORD"));
             var credits = connection.prepareStatement("SELECT COUNT(*) FROM user_credit_transaction t JOIN users u ON u.id=t.user_id "
                 + "WHERE u.email=? AND t.reference_type='COMM_CHALLENGE' AND t.reference_id=? AND t.amount=2");
             var clears = connection.prepareStatement("SELECT COUNT(*) FROM learning_dialogue_challenge_clear c JOIN users u "
                 + "ON u.id=c.user_id WHERE u.email=? AND c.lesson_id=?")) {
            for (var query : List.of(credits, clears)) {
                query.setString(1, E2EConfig.userEmail());
                query.setLong(2, lessonId);
            }
            try (var clearRows = clears.executeQuery(); var creditRows = credits.executeQuery()) {
                clearRows.next();
                creditRows.next();
                assertEquals(1, clearRows.getInt(1), "one clear row for this learner and situation");
                return creditRows.getInt(1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> activityIds(Map<String, Object> pkg) {
        Map<String, String> ids = new java.util.HashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) pkg.get("activities")) {
            ids.putIfAbsent((String) a.get("type"), (String) a.get("id"));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> states(String packageId) {
        Map<String, Object> pkg = ((List<Map<String, Object>>) state().get("packages")).stream()
            .filter(item -> packageId.equals(item.get("id"))).findFirst().orElseThrow();
        Map<String, String> states = new java.util.HashMap<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) pkg.get("activities")) {
            states.merge((String) a.get("type"), (String) a.get("state"),
                (left, right) -> "PASSED".equals(left) || "COMPLETED".equals(left) ? right : left);
        }
        return states;
    }

    @SuppressWarnings("unchecked")
    private String packageStatus(String packageId) {
        return ((List<Map<String, Object>>) state().get("packages")).stream()
            .filter(item -> packageId.equals(item.get("id"))).map(item -> (String) item.get("status"))
            .findFirst().orElseThrow();
    }

    /** True when the server refuses the POST (the expected outcome for a quiz endpoint on a no-quiz part). */
    private Boolean postFails(String url) {
        Object result = ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            fetch(arguments[0], { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' },
                window.JlasAuth.authHeaders()), body: '{}' })
              .then(response => done(!response.ok)).catch(() => done(null));
            """, url);
        return result == null ? null : (Boolean) result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(String url) {
        return (Map<String, Object>) ((JavascriptExecutor) driver).executeAsyncScript("""
            const done = arguments[arguments.length - 1];
            fetch(arguments[0], { headers: window.JlasAuth.authHeaders() })
              .then(async response => done({ status: response.status, data: (await response.json()).data }))
              .catch(error => done({ error: String(error) }));
            """, url);
    }

    private void jsClick(WebElement element) {
        ((JavascriptExecutor) driver).executeScript(
            "arguments[0].scrollIntoView({block:'center'}); arguments[0].click();", element);
    }
}
