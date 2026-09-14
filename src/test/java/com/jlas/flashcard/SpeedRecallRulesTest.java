package com.jlas.flashcard;
import com.jlas.flashcard.speedrecall.SpeedRecallRules;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SpeedRecallRulesTest {
    @Test void acceptsKanaNormalizationAndKanjiOnlyQuestions() {
        assertEquals("ガッコウ", SpeedRecallRules.normalize("　ｶﾞｯｺｳ　"));
        assertTrue(SpeedRecallRules.eligible(" 学校 ", " がっこう "));
        assertTrue(SpeedRecallRules.eligible("行く", "いく"));
        assertTrue(SpeedRecallRules.eligible("学校", "ｶﾞｯｺｳ"));
        assertFalse(SpeedRecallRules.eligible("hello", "はろー"));
        assertFalse(SpeedRecallRules.eligible("学校", "gakkou"));
        assertFalse(SpeedRecallRules.eligible("学校", "がっこう / ガッコウ"));
        assertFalse(SpeedRecallRules.eligible("学校", null));
    }
    @Test void boundariesAreBasedOnStreakBeforeCurrentQuestion() {
        assertArrayEquals(new int[]{6000,6000,5000,5000,4000,4000,3000},
            java.util.stream.IntStream.of(0,4,5,9,10,19,20).map(SpeedRecallRules::timeLimit).toArray());
        assertEquals(155, SpeedRecallRules.score(0,6000,0));
        assertEquals(130, SpeedRecallRules.score(3000,6000,0));
        assertEquals(105, SpeedRecallRules.score(6000,6000,0));
        assertEquals(250, SpeedRecallRules.score(0,3000,25));
    }
}
