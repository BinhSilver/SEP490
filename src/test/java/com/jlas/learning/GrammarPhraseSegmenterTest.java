package com.jlas.learning;

import com.jlas.learning.service.impl.grammar.GrammarPhraseSegmenter;
import com.jlas.learning.service.impl.grammar.GrammarPhraseSegmenter.Practice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owner 2026-10-21: communication grammar examples are plain Japanese without spaces, so the ordering exercise showed one
 * tile holding the whole sentence and the fill-blank hid the whole sentence. Practice must use phrase chunks and blank the
 * grammar pattern itself. */
class GrammarPhraseSegmenterTest {

    @Test void anUnspacedSentenceBecomesSeveralChunksWithThePatternAsTheBlank() {
        String sentence = "来週、要件定義のレビュー会議をお願いしたいのですが、ご都合はいかがでしょうか。";
        Practice practice = GrammarPhraseSegmenter.practice(sentence, "〜をお願いしたいのですが");

        assertTrue(practice.parts().size() >= 4, "chunks: " + practice.parts());
        assertTrue(practice.parts().size() <= GrammarPhraseSegmenter.MAX_PARTS, "chunks: " + practice.parts());
        assertEquals(sentence, practice.text(), "chunks rebuild the sentence exactly");
        assertEquals("をお願いしたいのですが", practice.answer(), "the blank is the grammar pattern");
        assertFalse(practice.spaced());
        assertTrue(practice.prompt().contains("_____"));
        assertFalse(practice.prompt().contains("お願いしたい"), practice.prompt());
    }

    @Test void aMultiSentenceExamplePractisesTheSentenceThatUsesThePattern() {
        String example = "初めまして。VJソリューションズのグエンと申します。本プロジェクトでブリッジSEを担当いたします。";

        Practice introduce = GrammarPhraseSegmenter.practice(example, "〜と申します");
        assertEquals("VJソリューションズのグエンと申します。", introduce.text());
        assertEquals("と申します", introduce.answer());

        Practice role = GrammarPhraseSegmenter.practice(example, "〜を担当いたします");
        assertEquals("本プロジェクトでブリッジSEを担当いたします。", role.text());
        assertEquals("を担当いたします", role.answer());
        assertTrue(role.parts().size() >= 3, "chunks: " + role.parts());
    }

    @Test void chunksKeepParticlesWithTheirWordAndNeverSplitInsideAWord() {
        Practice practice = GrammarPhraseSegmenter.practice(
                "会員登録機能は予定どおり完了し、現在、単体テストを実施中です。", "予定どおり");
        assertEquals("予定どおり", practice.answer());
        for (String part : practice.parts()) {
            assertFalse(part.matches("^[はがをにでとものへ、。].*"), "a chunk starts with a particle: " + practice.parts());
        }
    }

    @Test void anExampleWithoutThePatternStillGivesAPracticeWithARealBlank() {
        Practice practice = GrammarPhraseSegmenter.practice("こちらこそ、いつもお世話になっております。", "私、〇〇が承りました");
        assertTrue(practice.parts().size() >= 2, "chunks: " + practice.parts());
        assertTrue(practice.blankIndex() >= 0 && practice.blankIndex() < practice.parts().size());
        assertEquals("こちらこそ、いつもお世話になっております。", practice.text());
    }

    @Test void aPatternThatIsTheWholeSentenceStillGivesSeveralTilesAndBlanksItsEnding() {
        Practice practice = GrammarPhraseSegmenter.practice("承知いたしました。", "承知いたしました");
        assertTrue(practice.parts().size() >= 2, "chunks: " + practice.parts());
        assertEquals("承知いたしました。", practice.text());
        assertNotEquals(practice.text(), practice.answer(), "the blank is not the whole sentence");

        Practice longer = GrammarPhraseSegmenter.practice("申し訳ございません。ファムはただいま会議中でございます。11時には終わる予定です。",
                "ファムはただいま会議中でございます");
        assertTrue(longer.parts().size() >= 3, "chunks: " + longer.parts());
        assertTrue(longer.answer().startsWith("ございます") || longer.answer().contains("ございます"), longer.parts().toString());
    }

    @Test void aConjugatedPatternIsFoundByItsLeadingPart() {
        Practice practice = GrammarPhraseSegmenter.practice("三日前から咳が止まらなくて、夜眠れないんです。", "〜が止まらない");
        assertTrue(practice.answer().contains("止まら"), practice.parts().toString());
    }

    @Test void noTileIsPunctuationOnly() {
        Practice practice = GrammarPhraseSegmenter.practice(
                "一定時間が経過したら自動で解除する方式と、管理者が手動で解除する方式が考えられますが、どちらをご想定でしょうか。",
                "どちらをご想定でしょうか");
        for (String part : practice.parts()) assertFalse(part.matches("[、。！？]+"), "tiles: " + practice.parts());
        assertTrue(practice.answer().startsWith("どちらをご想定でしょうか"), practice.parts().toString());
    }

    @Test void anAlreadySpacedJlptExampleKeepsItsOwnUnits() {
        Practice practice = GrammarPhraseSegmenter.practice("わたしは グエンです。", "N1 は N2 です");
        assertEquals(List.of("わたしは", "グエンです。"), practice.parts());
        assertTrue(practice.spaced());
        assertEquals("わたしは グエンです。", practice.text());
    }
}
