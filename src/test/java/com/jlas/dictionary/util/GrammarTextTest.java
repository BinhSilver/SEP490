package com.jlas.dictionary.util;

import com.jlas.jlptpath.entity.JpathGrammar;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrammarTextTest {
    @Test
    void normalizesPlaceholdersSpacesAndTildesForRanking() {
        var exact = JpathGrammar.builder().pattern("〜 ために").familyKey("ために").meaningVi("để làm gì").build();
        var prefix = JpathGrammar.builder().pattern("ためには").familyKey("ためには").build();
        var contains = JpathGrammar.builder().pattern("別〜ために").familyKey("別ために別").build();
        var meaning = JpathGrammar.builder().pattern("別").familyKey("別").meaningVi("Vì mục đích").build();
        assertThat(GrammarText.normalize("　~ た め に ")).isEqualTo("ために");
        assertThat(GrammarText.normalize("N + ～ために")).isEqualTo("ために");
        assertThat(GrammarText.normalize("Vietnamese")).isEqualTo("vietnamese");
        assertThat(GrammarText.rank(exact, List.of(), "~ ために")).isZero();
        assertThat(GrammarText.rank(prefix, List.of(), "ために")).isEqualTo(1);
        assertThat(GrammarText.rank(contains, List.of(), "ために")).isEqualTo(2);
        assertThat(GrammarText.rank(meaning, List.of(), "mục đích")).isEqualTo(3);
    }

    @Test
    void parsesFuriganaAndPreservesPlainRuns() {
        assertThat(GrammarText.furiganaTokens("車(くるま)を買(か)うために", null)).containsExactly(
                new GrammarText.Token("車", "くるま"), new GrammarText.Token("を", null),
                new GrammarText.Token("買", "か"), new GrammarText.Token("うために", null));
        assertThat(GrammarText.furiganaTokens(null, "日本語です"))
                .containsExactly(new GrammarText.Token("日本語です", null));
    }
}
