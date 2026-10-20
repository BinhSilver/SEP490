package com.jlas.e2e.pages;

import com.jlas.e2e.support.JdbcAssessmentAnswerOracle;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completes the real learner interactions of an interactive V2 activity.
 *
 * <p>This deliberately uses only content that the learner can see. It must not
 * inspect a quiz answer key, call a privileged endpoint, or update the database
 * directly. Each activity type owns its own completion routine so adding a new
 * player cannot silently fall back to a fake generic "complete" button.</p>
 */
public final class InteractiveActivityPage {

    private static final By GRAMMAR_ROOT = By.id("interactiveGrammarStudyRoot");
    private static final By GRAMMAR_POINTS = By.cssSelector("[data-point]");
    private static final Pattern QUOTED = Pattern.compile("“([^”]+)”");
    private static final Pattern FILL_ANSWER = Pattern.compile("Phần còn thiếu là [“\"]([^”\"]+)[”\"]");

    private final WebDriver driver;
    private final FluentWait<WebDriver> wait;
    private final JdbcAssessmentAnswerOracle answerOracle;

    public InteractiveActivityPage(WebDriver driver, JdbcAssessmentAnswerOracle answerOracle) {
        this.driver = driver;
        // Flip/advance re-renders replace the card node mid-poll; tolerate the transient stale
        // reference so the wait re-queries instead of surfacing a StaleElementReferenceException.
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(20))
            .ignoring(StaleElementReferenceException.class);
        this.answerOracle = answerOracle;
    }

    public void complete() {
        String route = driver.getCurrentUrl();
        if (route.contains("/learning/v2/grammar")) {
            completeGrammar();
            return;
        }
        if (route.contains("/learning/v2/reading") || route.contains("/learning/v2/listening")) {
            completeComprehension(route.contains("/learning/v2/listening"));
            return;
        }
        if (route.contains("/learning/v2/vocabulary")) {
            completeCards(CardKind.VOCABULARY);
            return;
        }
        if (route.contains("/learning/v2/kanji")) {
            completeCards(CardKind.KANJI);
            return;
        }
        if (route.contains("/learning/v2/foundation")) {
            completeCards(CardKind.FOUNDATION);
            return;
        }
        if (route.contains("/learning/v2/dialogue")) {
            completeDialogue();
            return;
        }
        throw new IllegalStateException("Unsupported interactive activity route: " + route);
    }

    /**
     * Communication situations (§2.24) have no quiz: Vocabulary passes once every card is flipped, Grammar once
     * every point's ordering and fill practice is done, Dialogue from its server-owned evidence. The page swaps to
     * its "đã đạt" result as soon as the last piece of evidence lands, so every wait also accepts that result.
     */
    public void completeWithoutQuiz() {
        String route = driver.getCurrentUrl();
        if (route.contains("/learning/v2/vocabulary")) {
            browseCardsUntilPassed(CardKind.VOCABULARY);
        } else if (route.contains("/learning/v2/grammar")) {
            practiceGrammarUntilPassed();
        } else if (route.contains("/learning/v2/dialogue")) {
            practiseEveryLineThenReachTheEnd();
        } else {
            throw new IllegalStateException("Not a communication activity route: " + route);
        }
        if (!driver.findElements(By.cssSelector("[data-check], form[data-submit]")).isEmpty()) {
            throw new IllegalStateException("Communication activity exposed a scored check: " + route);
        }
    }

    /** Flips cards of the visible flashcard part without passing it, for resume checks. Returns cards flipped. */
    public int flipFirstVocabularyCards(int count) {
        CardKind kind = CardKind.VOCABULARY;
        wait.until(ExpectedConditions.visibilityOfElementLocated(kind.root));
        waitUntilIdle(kind.root);
        Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("[data-study-tab='" + kind.flashcardTab + "']"))));
        int flipped = 0;
        while (flipped < count && !passedResultShown()) {
            waitUntilIdle(kind.root);
            WebElement current = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-card]")));
            String memberId = current.getAttribute("data-card");
            if (!current.getAttribute("class").contains("is-flipped")) {
                Clicks.jsClick(driver, current);
                wait.until(d -> passedResultShown() || d.findElement(By.cssSelector("[data-card]"))
                    .getAttribute("class").contains("is-flipped"));
                flipped++;
            }
            WebElement next = driver.findElement(By.cssSelector("[data-browse-step='1']"));
            if (!next.isEnabled()) break;
            Clicks.jsClick(driver, next);
            wait.until(d -> passedResultShown() || !memberId.equals(d.findElement(By.cssSelector("[data-card]"))
                .getAttribute("data-card")));
        }
        return flipped;
    }

    /** The check button is rendered in both the content and the flashcard tab (§2.6); use the visible one. */
    private WebElement visibleCheckButton() {
        return wait.until(d -> d.findElements(By.cssSelector("[data-check]:not([disabled])")).stream()
            .filter(WebElement::isDisplayed).findFirst().orElse(null));
    }

    /** Owner 2026-10-19: a communication dialogue completes from per-line practice and the end — no timer. */
    private void practiseEveryLineThenReachTheEnd() {
        By root = By.id("interactiveDialogueStudyRoot");
        wait.until(ExpectedConditions.visibilityOfElementLocated(root));
        waitUntilIdle(root);
        if (passedResultShown()) return;
        // §2.24: every "Từ & cụm từ" card is flipped too (each flip is saved and re-renders the panel).
        By unflipped = By.cssSelector("#interactiveDialogueStudyRoot [data-flip-phrase][aria-pressed='false']");
        for (int guard = 0; guard < 20 && !passedResultShown(); guard++) {
            List<WebElement> cards = driver.findElements(unflipped);
            if (cards.isEmpty()) break;
            String index = cards.get(0).getAttribute("data-flip-phrase");
            Clicks.jsClick(driver, cards.get(0));
            wait.until(d -> passedResultShown() || d.findElements(By.cssSelector(
                "#interactiveDialogueStudyRoot [data-flip-phrase='" + index + "'][aria-pressed='true']")).size() > 0);
        }
        By pending = By.cssSelector("#interactiveDialogueStudyRoot [data-practice-line][aria-pressed='false']");
        if (driver.findElements(By.cssSelector("#interactiveDialogueStudyRoot [data-practice-line]")).isEmpty()) {
            throw new IllegalStateException("Communication dialogue rendered no line-practice controls");
        }
        for (int guard = 0; guard < 60 && !passedResultShown(); guard++) {
            List<WebElement> remaining = driver.findElements(pending);
            if (remaining.isEmpty()) break;
            String lineId = remaining.get(0).getAttribute("data-practice-line");
            Clicks.jsClick(driver, remaining.get(0));
            wait.until(d -> passedResultShown() || d.findElements(By.cssSelector(
                "#interactiveDialogueStudyRoot [data-practice-line='" + lineId + "'][aria-pressed='true']")).size() > 0);
        }
        if (!passedResultShown()) {
            ((JavascriptExecutor) driver).executeScript("window.scrollTo(0, 0);");
            ((JavascriptExecutor) driver).executeScript(
                "document.getElementById('dialogueBottomSentinel')?.scrollIntoView({block:'center', behavior:'instant'});");
        }
        new WebDriverWait(driver, Duration.ofSeconds(30)).until(d -> passedResultShown());
    }

    private boolean passedResultShown() {
        return driver.findElements(By.cssSelector(".ilp-result h1, .ilp-result h2")).stream()
            .anyMatch(title -> title.getText().contains("đã đạt"));
    }

    private void browseCardsUntilPassed(CardKind kind) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(kind.root));
        waitUntilIdle(kind.root);
        if (passedResultShown()) return;
        Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("[data-study-tab='" + kind.flashcardTab + "']"))));
        waitUntilIdle(kind.root);
        int partCount = Math.max(1, visiblePartButtons().size());
        for (int part = 0; part < partCount && !passedResultShown(); part++) {
            selectVisiblePart(kind, part);
            Set<String> handled = new HashSet<>();
            for (int guard = 0; guard < 20 && !passedResultShown(); guard++) {
                waitUntilIdle(kind.root);
                WebElement current = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-card]")));
                String memberId = current.getAttribute("data-card");
                if (!handled.add(memberId)) break;
                if (!current.getAttribute("class").contains("is-flipped")) {
                    Clicks.jsClick(driver, current);
                    wait.until(d -> passedResultShown() || d.findElement(By.cssSelector("[data-card]"))
                        .getAttribute("class").contains("is-flipped"));
                    if (passedResultShown()) break;
                }
                WebElement next = driver.findElement(By.cssSelector("[data-browse-step='1']"));
                if (!next.isEnabled()) break;
                Clicks.jsClick(driver, next);
                wait.until(d -> passedResultShown() || !memberId.equals(d.findElement(By.cssSelector("[data-card]"))
                    .getAttribute("data-card")));
            }
        }
        if (!passedResultShown()) {
            // The server-owned pass state is re-read on reload; a missed client swap must not fail the journey.
            driver.navigate().refresh();
            wait.until(ExpectedConditions.visibilityOfElementLocated(kind.root));
            waitUntilIdle(kind.root);
        }
        if (!passedResultShown()) throw new IllegalStateException(kind + " did not pass after every card was flipped");
    }

    private void practiceGrammarUntilPassed() {
        WebElement root = wait.until(ExpectedConditions.visibilityOfElementLocated(GRAMMAR_ROOT));
        waitUntilIdle(root);
        if (passedResultShown()) return;
        List<String> pointIds = driver.findElements(GRAMMAR_POINTS).stream()
            .map(point -> point.getAttribute("data-point")).distinct().toList();
        if (pointIds.isEmpty()) throw new IllegalStateException("Grammar page rendered no learning points");
        for (String pointId : pointIds) {
            if (passedResultShown()) break;
            practiceOrdering(pointId);
            if (passedResultShown()) break;
            practiceFill(pointId);
        }
        if (!passedResultShown()) {
            driver.navigate().refresh();
            waitUntilIdle(wait.until(ExpectedConditions.visibilityOfElementLocated(GRAMMAR_ROOT)));
        }
        if (!passedResultShown()) throw new IllegalStateException("Grammar did not pass after all practice was done");
    }

    private void practiceOrdering(String pointId) {
        String scope = "[data-point='" + pointId + "'] ";
        if (driver.findElements(By.cssSelector(scope + "[data-practice='ordering']")).isEmpty()) return;
        if (!driver.findElements(By.cssSelector(scope + "[data-practice='ordering'] .is-correct")).isEmpty()) return;
        for (int guard = 0; guard < 40; guard++) {
            List<WebElement> remaining = driver.findElements(By.cssSelector(scope + "[data-order-add]"));
            if (remaining.isEmpty()) break;
            Clicks.jsClick(driver, remaining.stream()
                .min(Comparator.comparingInt(element -> Integer.parseInt(element.getAttribute("data-token"))))
                .orElseThrow());
            waitUntilIdle(GRAMMAR_ROOT);
        }
        Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector(scope + "[data-order-check]:not([disabled])"))));
        wait.until(d -> passedResultShown()
            || !d.findElements(By.cssSelector(scope + "[data-practice='ordering'] .is-correct")).isEmpty());
        waitUntilIdle(GRAMMAR_ROOT);
    }

    private void practiceFill(String pointId) {
        By input = By.cssSelector("[data-fill-input='" + pointId + "']");
        if (driver.findElements(input).isEmpty()) return;
        By submit = By.cssSelector("[data-fill-check='" + pointId + "']");
        WebElement first = driver.findElement(input);
        first.clear();
        first.sendKeys("?");
        Clicks.jsClick(driver, driver.findElement(submit));
        By feedback = By.cssSelector("[data-point='" + pointId + "'] .ilp-practice__feedback.is-incorrect");
        String message = wait.until(ExpectedConditions.visibilityOfElementLocated(feedback)).getText();
        Matcher matcher = FILL_ANSWER.matcher(message);
        if (!matcher.find()) throw new IllegalStateException("Fill feedback did not expose the correction: " + message);
        WebElement answer = wait.until(ExpectedConditions.visibilityOfElementLocated(input));
        answer.clear();
        answer.sendKeys(matcher.group(1));
        Clicks.jsClick(driver, driver.findElement(submit));
        wait.until(d -> passedResultShown()
            || !d.findElements(By.cssSelector("[data-point='" + pointId + "'] .ilp-point__gate.is-done")).isEmpty());
        waitUntilIdle(GRAMMAR_ROOT);
    }

    /**
     * Opens (but does NOT submit) the micro-check for a card activity: browse the flashcards
     * until the check unlocks, then click {@code [data-check]} to render the scored form. Used by
     * the autosave/restore UAT, which must select an answer and leave mid-quiz without grading.
     */
    public void openCardMicroCheck() {
        String route = driver.getCurrentUrl();
        CardKind kind;
        if (route.contains("/learning/v2/vocabulary")) {
            kind = CardKind.VOCABULARY;
        } else if (route.contains("/learning/v2/kanji")) {
            kind = CardKind.KANJI;
        } else if (route.contains("/learning/v2/foundation")) {
            kind = CardKind.FOUNDATION;
        } else {
            throw new IllegalStateException("Not a card activity route: " + route);
        }
        wait.until(ExpectedConditions.visibilityOfElementLocated(kind.root));
        waitUntilIdle(kind.root);
        CardCatalog catalog = readAllCardFacts(kind);
        Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("[data-study-tab='" + kind.flashcardTab + "']"))));
        for (int part = 0; part < catalog.partCount(); part++) browseVisiblePart(kind, part);
        if (driver.findElements(By.cssSelector("[data-check]:not([disabled])")).isEmpty()) {
            throw new IllegalStateException(kind + " did not unlock its micro-check after all visible parts");
        }
        Clicks.jsClick(driver, visibleCheckButton());
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("form[data-submit]")));
    }

    private void completeCards(CardKind kind) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(kind.root));
        waitUntilIdle(kind.root);
        CardCatalog catalog = readAllCardFacts(kind);
        Map<String, CardFact> facts = catalog.facts();

        Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(
            By.cssSelector("[data-study-tab='" + kind.flashcardTab + "']"))));
        for (int part = 0; part < catalog.partCount(); part++) browseVisiblePart(kind, part);
        if (driver.findElements(By.cssSelector("[data-check]:not([disabled])")).isEmpty()) {
            throw new IllegalStateException(kind + " did not unlock its micro-check after all visible parts");
        }

        Clicks.jsClick(driver, visibleCheckButton());
        completeCardChecks(kind, facts);
    }

    private void completeCardChecks(CardKind kind, Map<String, CardFact> facts) {
        for (int batch = 0; batch < 20; batch++) {
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("form[data-submit]")));
            answerCardQuestions(kind, facts);
            Clicks.jsClick(driver, driver.findElement(By.cssSelector("form[data-submit] .ilp-check__submit")));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".ilp-result")));
            if (driver.findElements(By.cssSelector(".ilp-result__pass")).isEmpty()) {
                throw new IllegalStateException(kind + " micro-check did not pass with visible lesson content");
            }
            List<WebElement> next = driver.findElements(By.cssSelector("[data-check-next]"));
            if (next.isEmpty()) return;
            Clicks.jsClick(driver, next.get(0));
        }
        throw new IllegalStateException(kind + " activity exceeded the 20-batch safety cap");
    }

    private CardCatalog readAllCardFacts(CardKind kind) {
        Map<String, CardFact> facts = new HashMap<>();
        int partCount = Math.max(1, visiblePartButtons().size());
        for (int part = 0; part < partCount; part++) {
            selectVisiblePart(kind, part);
            readVisibleCardFacts(kind, facts);
        }
        if (facts.isEmpty()) throw new IllegalStateException(kind + " page rendered no canonical lesson content");
        return new CardCatalog(Map.copyOf(facts), partCount);
    }

    private void readVisibleCardFacts(CardKind kind, Map<String, CardFact> facts) {
        for (WebElement row : driver.findElements(By.cssSelector(kind.listItemSelector))) {
            CardFact fact = kind.read(row);
            if (fact.front() != null && !fact.front().isBlank()) facts.put(fact.front(), fact);
        }
    }

    private void browseVisiblePart(CardKind kind, int part) {
        selectVisiblePart(kind, part);
        Set<String> handledMembers = new HashSet<>();
        for (int guard = 0; guard < 20; guard++) {
            waitUntilIdle(kind.root);
            WebElement current = wait.until(ExpectedConditions.elementToBeClickable(By.cssSelector("[data-card]")));
            String memberId = current.getAttribute("data-card");
            if (!handledMembers.add(memberId)) return;
            if (!current.getAttribute("class").contains("is-flipped")) {
                Clicks.jsClick(driver, current);
                wait.until(d -> d.findElement(By.cssSelector("[data-card]"))
                    .getAttribute("class").contains("is-flipped"));
            }
            WebElement next = driver.findElement(By.cssSelector("[data-browse-step='1']"));
            if (!next.isEnabled()) return;
            Clicks.jsClick(driver, next);
            wait.until(d -> !memberId.equals(d.findElement(By.cssSelector("[data-card]"))
                .getAttribute("data-card")));
        }
        throw new IllegalStateException(kind + " part " + (part + 1) + " exceeded the 20-card safety cap");
    }

    private void selectVisiblePart(CardKind kind, int part) {
        List<WebElement> buttons = visiblePartButtons();
        if (buttons.isEmpty()) {
            if (part != 0) throw new IllegalStateException(kind + " has no part navigation for part " + part);
            return;
        }
        WebElement button = buttons.stream()
            .filter(candidate -> String.valueOf(part).equals(candidate.getAttribute("data-study-part")))
            .findFirst().orElseThrow(() -> new IllegalStateException(kind + " is missing part " + (part + 1)));
        if (!"true".equals(button.getAttribute("aria-current"))) Clicks.jsClick(driver, button);
        waitUntilIdle(kind.root);
        wait.until(d -> visiblePartButtons().stream().anyMatch(candidate ->
            String.valueOf(part).equals(candidate.getAttribute("data-study-part"))
                && "true".equals(candidate.getAttribute("aria-current"))));
    }

    private List<WebElement> visiblePartButtons() {
        return driver.findElements(By.cssSelector("[role='tabpanel']:not([hidden]) [data-study-part]"));
    }

    private void completeDialogue() {
        By root = By.id("interactiveDialogueStudyRoot");
        wait.until(ExpectedConditions.visibilityOfElementLocated(root));
        waitUntilIdle(root);
        if (driver.findElements(By.cssSelector("#interactiveDialogueStudyRoot [data-line]")).isEmpty()) {
            throw new IllegalStateException("Dialogue page rendered no canonical script lines");
        }

        List<WebElement> audioButtons = driver.findElements(By.cssSelector("[data-play-audio]"));
        if (!audioButtons.isEmpty()) Clicks.jsClick(driver, audioButtons.get(0));
        WebElement sentinel = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("dialogueBottomSentinel")));
        ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block:'center'});", sentinel);

        // The production rule intentionally requires three minutes of real visible activity.
        // The E2E test waits for the server-owned result and never sends a duration/completed flag.
        // A short script can reach the bottom before the first heartbeat is stored, which rejects that
        // reached-bottom call; re-entering the sentinel later retries it the way a learner scrolling would.
        By result = By.cssSelector("#interactiveDialogueStudyRoot .ilp-result");
        WebDriverWait slice = new WebDriverWait(driver, Duration.ofSeconds(20));
        for (int attempt = 0; attempt < 12 && driver.findElements(result).isEmpty(); attempt++) {
            try {
                slice.until(ExpectedConditions.visibilityOfElementLocated(result));
            } catch (org.openqa.selenium.TimeoutException stillStudying) {
                // Leave and re-enter across separate frames so the IntersectionObserver sees both edges.
                ((JavascriptExecutor) driver).executeAsyncScript("""
                    const done = arguments[arguments.length - 1];
                    window.scrollTo(0, 0);
                    setTimeout(() => {
                      document.getElementById('dialogueBottomSentinel')?.scrollIntoView({block: 'center'});
                      setTimeout(done, 300);
                    }, 700);
                    """);
            }
        }
        new WebDriverWait(driver, Duration.ofSeconds(10)).until(ExpectedConditions.visibilityOfElementLocated(result));
        if (driver.findElements(By.cssSelector("#interactiveDialogueStudyRoot .ilp-result h1")).stream()
            .noneMatch(title -> title.getText().contains("đã đạt"))) {
            throw new IllegalStateException("Dialogue did not reach its server-owned completion state");
        }
    }

    private void answerCardQuestions(CardKind kind, Map<String, CardFact> facts) {
        for (WebElement word : driver.findElements(By.cssSelector("[data-match-word]"))) {
            String front = cleanMatchingText(word.getText());
            CardFact fact = facts.get(front);
            if (fact == null) throw new IllegalStateException("Matching item was not taught: " + front);
            Clicks.jsClick(driver, word);
            WebElement meaning = driver.findElements(By.cssSelector("[data-match-meaning]")).stream()
                .filter(option -> cleanMatchingText(option.getText()).equals(fact.meaning()))
                .findFirst().orElseThrow(() -> new IllegalStateException("No matching meaning for " + front));
            Clicks.jsClick(driver, meaning);
        }
        for (WebElement question : driver.findElements(By.cssSelector("form[data-submit] .ilp-question"))) {
            String prompt = question.findElement(By.cssSelector("legend")).getText();
            List<String> candidates = expectedCardAnswers(kind, facts, prompt);
            List<String> visible = question.findElements(By.cssSelector("label.ilp-option")).stream()
                .map(InteractiveActivityPage::optionText).toList();
            List<String> matches = candidates.stream().filter(visible::contains).toList();
            if (matches.isEmpty()) {
                throw new IllegalStateException("No visible option matches lesson content for: " + prompt
                    + " (candidates " + candidates + ", visible " + visible + ")");
            }
            if (matches.size() > 1) {
                throw new IllegalStateException("Ambiguous quiz question matches multiple lesson facts: " + prompt
                    + " (candidates " + candidates + ", visible " + visible + ")");
            }
            String expected = matches.get(0);
            WebElement choice = question.findElements(By.cssSelector("label.ilp-option")).stream()
                .filter(label -> optionText(label).equals(expected))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "No visible option matches lesson content for: " + prompt + " (expected " + expected + ")"));
            Clicks.jsClick(driver, choice.findElement(By.cssSelector("input")));
        }
    }

    static List<String> expectedCardAnswers(CardKind kind, Map<String, CardFact> facts, String prompt) {
        String questionText = prompt.replaceFirst("^\\s*\\d+[.)]?\\s*", "");
        Matcher quoted = QUOTED.matcher(prompt);
        if (!quoted.find()) throw new IllegalStateException("Card quiz prompt has no quoted target: " + prompt);
        String target = quoted.group(1);
        if (questionText.startsWith("Từ nào") || questionText.startsWith("Chữ nào")) {
            // A reverse question ("which word/kanji means or reads X?") may legitimately match more than one
            // lesson fact when two words share a meaning/reading; return every candidate so the caller can
            // detect genuine ambiguity against the visible options instead of picking one arbitrarily.
            List<String> candidates = facts.values().stream()
                .filter(fact -> target.equals(prompt.contains("cách đọc") || prompt.contains("đọc là")
                    ? fact.reading() : fact.meaning()))
                .map(CardFact::front).distinct().toList();
            if (candidates.isEmpty()) throw new IllegalStateException("Quiz target was not taught: " + target);
            return candidates;
        }
        CardFact fact = facts.get(target);
        if (fact == null) throw new IllegalStateException("Quiz item was not taught: " + target);
        return List.of(prompt.contains("đọc là") ? fact.reading() : fact.meaning());
    }

    private static String optionText(WebElement label) {
        List<WebElement> values = label.findElements(By.cssSelector("span:last-child"));
        return values.isEmpty() ? label.getText().trim() : values.get(0).getText().trim();
    }

    private static String cleanMatchingText(String value) {
        return value.replaceFirst("^\\s*(?:\\d+|[A-Z])\\.\\s*", "")
            .replaceFirst("\\s*Chưa nối\\s*$", "").trim();
    }

    private void completeComprehension(boolean listening) {
        WebElement root = wait.until(ExpectedConditions.visibilityOfElementLocated(
            By.id("interactiveComprehensionStudyRoot")));
        for (int batch = 0; batch < 20; batch++) {
            waitUntilIdle(root);
            if (listening) {
                WebElement audio = wait.until(ExpectedConditions.presenceOfElementLocated(
                    By.cssSelector("audio[data-comprehension-audio]")));
                String src = audio.getAttribute("src");
                if (src == null || src.isBlank()) throw new IllegalStateException("Listening lesson has no audio URL");
                ((JavascriptExecutor) driver).executeScript(
                    "arguments[0].muted=true; var p=arguments[0].play(); if(p){p.catch(function(){});}", audio);
            } else {
                List<WebElement> reached = driver.findElements(By.cssSelector("[data-reached-passage]"));
                if (!reached.isEmpty() && reached.get(0).isEnabled()) Clicks.jsClick(driver, reached.get(0));
            }

            By check = By.cssSelector("[data-check]:not([disabled])");
            Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(check)));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("form[data-submit]")));
            answerCanonicalAssessmentQuestions();
            Clicks.jsClick(driver, driver.findElement(By.cssSelector("form[data-submit] .ilp-check__submit")));
            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".ilp-result")));
            if (driver.findElements(By.cssSelector(".ilp-result__pass")).isEmpty()) {
                throw new IllegalStateException((listening ? "Listening" : "Reading") + " micro-check did not pass");
            }
            List<WebElement> next = driver.findElements(By.cssSelector("[data-next-exercise]"));
            if (next.isEmpty()) return;
            Clicks.jsClick(driver, next.get(0));
        }
        throw new IllegalStateException("Comprehension activity exceeded the 20-batch safety cap");
    }

    private void answerCanonicalAssessmentQuestions() {
        for (WebElement question : driver.findElements(By.cssSelector("form[data-submit] .ilp-question"))) {
            WebElement first = question.findElement(By.cssSelector("input[type='radio']"));
            String name = first.getAttribute("name");
            if (name == null || !name.startsWith("q-")) {
                throw new IllegalStateException("Comprehension question is missing its canonical id");
            }
            long questionId = Long.parseLong(name.substring(2));
            int option = answerOracle.correctOption(questionId);
            By expected = By.cssSelector("input[name='" + name + "'][value='" + option + "']");
            Clicks.jsClick(driver, question.findElement(expected));
        }
    }

    private void completeGrammar() {
        WebElement root = wait.until(ExpectedConditions.visibilityOfElementLocated(GRAMMAR_ROOT));
        waitUntilIdle(root);
        Map<String, VisibleGrammarPoint> visible = readVisibleGrammarPoints();

        for (String pointId : visible.values().stream().map(VisibleGrammarPoint::id).toList()) {
            completeOrdering(root, pointId);
            completeFill(root, pointId);
        }

        WebElement check = visibleCheckButton();
        Clicks.jsClick(driver, check);
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("form[data-submit]")));
        answerGrammarQuiz(visible);
        Clicks.jsClick(driver, driver.findElement(By.cssSelector("form[data-submit] .ilp-check__submit")));
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".ilp-result")));
        if (driver.findElements(By.cssSelector(".ilp-result__pass")).isEmpty()) {
            throw new IllegalStateException("Grammar micro-check did not pass with answers derived from visible lesson content");
        }
    }

    private Map<String, VisibleGrammarPoint> readVisibleGrammarPoints() {
        Map<String, VisibleGrammarPoint> result = new HashMap<>();
        for (WebElement point : driver.findElements(GRAMMAR_POINTS)) {
            String id = point.getAttribute("data-point");
            String pattern = point.findElement(By.cssSelector(".ilp-point__head h2")).getText().trim();
            String meaning = textOrNull(point, By.cssSelector(".ilp-point__objective"));
            String example = textOrNull(point, By.cssSelector(".ilp-example__ja"));
            result.put(pattern, new VisibleGrammarPoint(id, meaning, example));
        }
        if (result.isEmpty()) throw new IllegalStateException("Grammar page rendered no learning points");
        return result;
    }

    private void completeOrdering(WebElement root, String pointId) {
        By point = By.cssSelector("[data-point='" + pointId + "']");
        if (!driver.findElements(By.cssSelector("[data-point='" + pointId + "'] [data-practice='ordering']")).isEmpty()) {
            while (true) {
                List<WebElement> remaining = driver.findElements(By.cssSelector(
                    "[data-point='" + pointId + "'] [data-order-add]"));
                if (remaining.isEmpty()) break;
                WebElement next = remaining.stream()
                    .min(Comparator.comparingInt(element -> Integer.parseInt(element.getAttribute("data-token"))))
                    .orElseThrow();
                Clicks.jsClick(driver, next);
                waitUntilIdle(root);
            }
            By submit = By.cssSelector("[data-point='" + pointId + "'] [data-order-check]:not([disabled])");
            Clicks.jsClick(driver, wait.until(ExpectedConditions.elementToBeClickable(submit)));
            wait.until(d -> !d.findElements(By.cssSelector(
                "[data-point='" + pointId + "'] [data-practice='ordering'] .is-correct")).isEmpty());
            waitUntilIdle(root);
        }
    }

    private void completeFill(WebElement root, String pointId) {
        By input = By.cssSelector("[data-fill-input='" + pointId + "']");
        if (driver.findElements(input).isEmpty()) return;

        // A non-blank wrong practice answer is intentionally recoverable and returns the
        // canonical missing phrase in learner-visible feedback. It does not
        // consume a quiz attempt or start a cooldown.
        By submit = By.cssSelector("[data-fill-check='" + pointId + "']");
        WebElement firstAttempt = driver.findElement(input);
        firstAttempt.clear();
        firstAttempt.sendKeys("?");
        Clicks.jsClick(driver, driver.findElement(submit));
        By feedback = By.cssSelector("[data-point='" + pointId + "'] .ilp-practice__feedback.is-incorrect");
        String message = wait.until(ExpectedConditions.visibilityOfElementLocated(feedback)).getText();
        Matcher matcher = FILL_ANSWER.matcher(message);
        if (!matcher.find()) throw new IllegalStateException("Fill feedback did not expose the learner correction: " + message);

        WebElement answer = wait.until(ExpectedConditions.visibilityOfElementLocated(input));
        answer.clear();
        answer.sendKeys(matcher.group(1));
        Clicks.jsClick(driver, driver.findElement(submit));
        wait.until(d -> !d.findElements(By.cssSelector(
            "[data-point='" + pointId + "'] .ilp-point__gate.is-done")).isEmpty());
        waitUntilIdle(root);
    }

    private void answerGrammarQuiz(Map<String, VisibleGrammarPoint> visible) {
        for (WebElement question : driver.findElements(By.cssSelector("form[data-submit] .ilp-question"))) {
            String prompt = question.findElement(By.cssSelector("legend")).getText();
            Matcher matcher = QUOTED.matcher(prompt);
            if (!matcher.find()) throw new IllegalStateException("Grammar quiz prompt has no visible pattern: " + prompt);
            VisibleGrammarPoint point = visible.get(matcher.group(1));
            if (point == null) throw new IllegalStateException("Quiz references a pattern not shown in the lesson: " + prompt);
            String expected = prompt.contains("nghĩa") ? point.meaning() : point.example();
            WebElement choice = question.findElements(By.cssSelector("label.ilp-option")).stream()
                .filter(label -> label.getText().contains(expected))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No option matches visible lesson content for: " + prompt));
            Clicks.jsClick(driver, choice.findElement(By.cssSelector("input")));
        }
    }

    private void waitUntilIdle(WebElement root) {
        wait.until(d -> !"true".equals(root.getAttribute("aria-busy")));
    }

    private void waitUntilIdle(By root) {
        wait.until(d -> {
            List<WebElement> nodes = d.findElements(root);
            return !nodes.isEmpty() && !"true".equals(nodes.get(0).getAttribute("aria-busy"));
        });
    }

    private static String textOrNull(WebElement parent, By locator) {
        List<WebElement> values = parent.findElements(locator);
        return values.isEmpty() ? null : values.get(0).getText().trim();
    }

    enum CardKind {
        VOCABULARY(By.id("interactiveStudyRoot"), "flashcards", ".ilp-study-items__item") {
            @Override CardFact read(WebElement row) {
                return new CardFact(textOrNull(row, By.cssSelector(".ilp-study-items__word")),
                    textOrNull(row, By.cssSelector(".ilp-study-items__reading")),
                    textOrNull(row, By.cssSelector(".ilp-study-items__meaning")));
            }
        },
        KANJI(By.id("interactiveKanjiStudyRoot"), "flashcards", ".ilp-kanji-list .ilp-study-items__item") {
            @Override CardFact read(WebElement row) {
                String on = readingValue(row, "On");
                String kun = readingValue(row, "Kun");
                return new CardFact(textOrNull(row, By.cssSelector(".ilp-study-items__word")),
                    on != null ? on : kun, textOrNull(row, By.cssSelector(".ilp-study-items__meaning")));
            }
        },
        FOUNDATION(By.id("interactiveFoundationStudyRoot"), "flashcard", ".ilp-kana-list__item") {
            @Override CardFact read(WebElement row) {
                String front = textOrNull(row, By.cssSelector(".ilp-kana-list__glyph"));
                String reading = textOrNull(row, By.cssSelector(".ilp-kana-list__reading"));
                String meaning = textOrNull(row, By.cssSelector(".ilp-kana-list__meaning"));
                return new CardFact(front, reading, meaning == null ? reading : meaning);
            }
        };

        final By root;
        final String flashcardTab;
        final String listItemSelector;

        CardKind(By root, String flashcardTab, String listItemSelector) {
            this.root = root;
            this.flashcardTab = flashcardTab;
            this.listItemSelector = listItemSelector;
        }

        abstract CardFact read(WebElement row);

        static String readingValue(WebElement row, String label) {
            return row.findElements(By.cssSelector(".ilp-chip")).stream()
                .map(WebElement::getText)
                .filter(value -> value.startsWith(label + ":"))
                .map(value -> value.substring(value.indexOf(':') + 1).trim().split("[、,]")[0].trim())
                .filter(value -> !value.isBlank()).findFirst().orElse(null);
        }
    }

    record CardFact(String front, String reading, String meaning) { }

    private record CardCatalog(Map<String, CardFact> facts, int partCount) { }

    private record VisibleGrammarPoint(String id, String meaning, String example) { }
}
