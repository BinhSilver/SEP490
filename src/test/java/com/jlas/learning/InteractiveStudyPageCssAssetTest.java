package com.jlas.learning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] Task 6 — regression guard for a Task 5 bug found while building the Grammar page: the Kanji
 * study template passed {@code standard('...', 'interactive-kanji-study')} to the shared head fragment, but
 * the only stylesheet ever built for these pages is {@code interactive-study.css} — Thymeleaf's {@code
 * th:if}/{@code th:href} construct the {@code <link>} without checking the file exists, so the browser got a
 * silent 404 and the Kanji page rendered completely unstyled. Fixed narrowly (the {@code pageCss} argument
 * only feeds one {@code <link href>}, verified against {@code fragments/head.html}) alongside adding Grammar,
 * which would otherwise have copied the same mistake. This test reads the template source directly (no
 * Thymeleaf render harness exists in this repo) so a future page cannot reintroduce a dangling reference. */
class InteractiveStudyPageCssAssetTest {
    private static final Pattern PAGE_CSS = Pattern.compile("standard\\([^,]+,\\s*'([^']*)'\\)");
    private static final Path TEMPLATES = Path.of("src/main/resources/templates/learning");
    private static final Path CSS_PAGES = Path.of("src/main/resources/static/css/pages");

    @ParameterizedTest
    @ValueSource(strings = {"interactive-study.html", "interactive-kanji-study.html", "interactive-grammar-study.html",
            "interactive-path-summary.html", "interactive-certificate.html", "interactive-comprehension-study.html"})
    void pageCssArgumentResolvesToAnExistingStylesheet(String templateName) throws IOException {
        String source = Files.readString(TEMPLATES.resolve(templateName));
        Matcher m = PAGE_CSS.matcher(source);
        assertTrue(m.find(), templateName + " does not pass a pageCss argument to fragments/head :: standard(...)");
        String pageCss = m.group(1);
        assertFalse(pageCss.isBlank(), templateName + " passes a blank pageCss");
        Path css = CSS_PAGES.resolve(pageCss + ".css");
        assertTrue(Files.exists(css), templateName + " references " + css + " which does not exist "
                + "(the <link> would silently 404 and the page would render unstyled)");
    }

    @Test void vocabularyKanjiAndGrammarStudyPagesShareTheSameStylesheetByDesign() throws IOException {
        for (String name : new String[]{"interactive-study.html", "interactive-kanji-study.html", "interactive-grammar-study.html"}) {
            String source = Files.readString(TEMPLATES.resolve(name));
            Matcher m = PAGE_CSS.matcher(source);
            assertTrue(m.find());
            assertEquals("interactive-study", m.group(1), name + " should reuse the shared interactive-study.css");
        }
    }

    /** [JLAS-W0193] RC Task 5 — the comprehension player reuses the shared bundle for base layout/check/result
     * classes (same rule as Grammar/Dialogue above) and additionally loads its own dedicated stylesheet for the
     * passage/audio-source panel via an explicit {@code <link>} rather than a second {@code pageCss} argument,
     * since {@code fragments/head.html}'s {@code standard} fragment only wires one page-specific stylesheet. */
    @Test void comprehensionStudyPageReusesTheSharedBundlePlusItsOwnDedicatedStylesheet() throws IOException {
        String source = Files.readString(TEMPLATES.resolve("interactive-comprehension-study.html"));
        Matcher m = PAGE_CSS.matcher(source);
        assertTrue(m.find());
        assertEquals("interactive-study", m.group(1), "should reuse the shared interactive-study.css for base layout");

        Pattern extraLink = Pattern.compile("th:href=\"@\\{/css/pages/([^}]+)\\.css}\"");
        Matcher extra = extraLink.matcher(source);
        assertTrue(extra.find(), "expected an explicit dedicated stylesheet <link> for the comprehension player");
        String extraName = extra.group(1);
        assertEquals("interactive-comprehension-study", extraName);
        assertTrue(Files.exists(CSS_PAGES.resolve(extraName + ".css")),
                "dedicated stylesheet " + extraName + ".css must exist or the <link> silently 404s");
    }
}
