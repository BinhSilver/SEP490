package com.jlas.learning;

import com.jlas.learning.service.impl.grammar.GrammarTextAnalyzer;
import com.jlas.learning.service.impl.grammar.GrammarTextAnalyzer.Role;
import com.jlas.learning.service.impl.grammar.GrammarTextAnalyzer.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** [JLAS-W0193] Task 6 — pure, deterministic analysis over real seeded {@code grammar_point} pattern/example
 * text (see sql/seeds/seed_data_jlas.sql rows 1-8): no AI, no fabricated per-item content, same fixed dictionary
 * applied to every point. */
class GrammarTextAnalyzerTest {

    @Test void classifiesTheCoreAWaBDesuPattern() {
        List<Token> tokens = GrammarTextAnalyzer.classifyPattern("N1 は N2 です");
        assertEquals(4, tokens.size());
        assertEquals(Role.SLOT, tokens.get(0).role());
        assertEquals(Role.PARTICLE, tokens.get(1).role());
        assertEquals(Role.SLOT, tokens.get(2).role());
        assertEquals(Role.AUXILIARY, tokens.get(3).role());
    }

    @Test void classifiesTheNegativePatternWithTwoAuxiliaryWords() {
        List<Token> tokens = GrammarTextAnalyzer.classifyPattern("N1 は N2 じゃ ありません");
        assertEquals(5, tokens.size());
        assertEquals(Role.AUXILIARY, tokens.get(3).role());
        assertEquals(Role.AUXILIARY, tokens.get(4).role());
    }

    @Test void classifiesTheNoPossessivePattern() {
        List<Token> tokens = GrammarTextAnalyzer.classifyPattern("N1 の N2");
        assertEquals(Role.PARTICLE, tokens.get(1).role());
    }

    @Test void fallsBackToOtherForNonGrammaticalTokensRatherThanFabricatingARole() {
        List<Token> tokens = GrammarTextAnalyzer.classifyPattern("Tên người + さん");
        assertTrue(tokens.stream().allMatch(t -> t.role() == Role.OTHER));
    }

    @Test void emptyOrNullPatternProducesNoTokens() {
        assertTrue(GrammarTextAnalyzer.classifyPattern(null).isEmpty());
        assertTrue(GrammarTextAnalyzer.classifyPattern("   ").isEmpty());
    }

    @Test void choosesABlankableWordAfterTheFirstWordWhenAvailable() {
        List<String> words = GrammarTextAnalyzer.splitWords("わたしは グエンです。");
        assertEquals(2, words.size());
        int index = GrammarTextAnalyzer.chooseBlankIndex(words);
        assertEquals(1, index, "should prefer index >= 1 (the auxiliary-bearing word) over the topic word");
    }

    @Test void choosesIndexZeroWhenItIsTheOnlyBlankableWord() {
        // A single-word sentence: nothing after index 0 to prefer.
        int index = GrammarTextAnalyzer.chooseBlankIndex(List.of("です"));
        assertEquals(0, index);
    }

    @Test void fallsBackToTheLastWordWhenNothingMatchesAnyKnownMarker() {
        List<String> words = List.of("はじめまして", "どうぞ", "よろしく");
        assertEquals(words.size() - 1, GrammarTextAnalyzer.chooseBlankIndex(words));
    }

    @Test void chooseBlankIndexOnEmptyListReturnsNegativeOne() {
        assertEquals(-1, GrammarTextAnalyzer.chooseBlankIndex(List.of()));
    }

    @Test void shuffledOrderIsDeterministicForTheSameSeed() {
        List<Integer> first = GrammarTextAnalyzer.shuffledOrder(42L, 5);
        List<Integer> second = GrammarTextAnalyzer.shuffledOrder(42L, 5);
        assertEquals(first, second);
        assertEquals(5, first.size());
        assertEquals(List.of(0, 1, 2, 3, 4), first.stream().sorted().toList());
    }

    @Test void shuffledOrderNeverReturnsTheIdentityOrderWhenThereIsMoreThanOneWord() {
        for (long seed = 0; seed < 20; seed++) {
            List<Integer> order = GrammarTextAnalyzer.shuffledOrder(seed, 2);
            assertNotEquals(List.of(0, 1), order, "seed " + seed + " produced an un-shuffled 2-word order");
        }
    }

    @Test void shuffledOrderOfASingleWordIsTrivial() {
        assertEquals(List.of(0), GrammarTextAnalyzer.shuffledOrder(1L, 1));
    }

    @Test void normalizeAnswerTrimsCollapsesWhitespaceStripsTrailingPunctuationAndFoldsCase() {
        assertEquals("グエンです", GrammarTextAnalyzer.normalizeAnswer("  グエンです。 "));
        assertEquals("a b", GrammarTextAnalyzer.normalizeAnswer(" A   B "));
        assertEquals("", GrammarTextAnalyzer.normalizeAnswer(null));
    }
}
