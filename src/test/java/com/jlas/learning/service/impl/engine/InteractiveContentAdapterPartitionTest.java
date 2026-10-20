package com.jlas.learning.service.impl.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningMemberType;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** R2 — card-based skills draw their choice options from the batch itself, so a one-item batch can never be
 * quizzed. The default split keeps the historical chunks of ten and the same batch count; only a trailing
 * 10+1 is rebalanced, which leaves every earlier batch boundary (and its recorded progress) untouched. */
class InteractiveContentAdapterPartitionTest {
    private final InteractiveContentAdapter<Integer> adapter = new InteractiveContentAdapter<>() {
        @Override public LearningActivityType activityType() { return LearningActivityType.KANJI; }
        @Override public LearningMemberType memberType() { return LearningMemberType.KANJI; }
        @Override public String microCheckType() { return "KANJI_MICRO_CHECK"; }
        @Override public Map<Long, Integer> loadByIds(List<Long> ids) { return Map.of(); }
        @Override public List<QuestionSpec> buildQuestions(List<Integer> batch, int index, int seed) { return List.of(); }
    };

    @Test void trailingSingleItemIsRebalancedWithoutChangingBatchCountOrEarlierBoundaries() {
        assertEquals(List.of(1), sizes(1));
        assertEquals(List.of(10), sizes(10));
        assertEquals(List.of(6, 5), sizes(11));
        assertEquals(List.of(10, 2), sizes(12));
        assertEquals(List.of(10, 10), sizes(20));
        assertEquals(List.of(10, 6, 5), sizes(21));
        assertEquals(List.of(10, 10, 6, 5), sizes(31));
    }

    @Test void rebalancingKeepsEveryMemberOnceInOrder() {
        for (int n = 1; n <= 61; n++) {
            var content = IntStream.rangeClosed(1, n).boxed().toList();
            assertEquals(content, adapter.partition(content).stream().flatMap(List::stream).toList());
            assertEquals((n + 9) / 10, adapter.partition(content).size(), "batch count must stay ceil(n/10)");
        }
    }

    private List<Integer> sizes(int n) {
        return adapter.partition(IntStream.rangeClosed(1, n).boxed().toList()).stream().map(List::size).toList();
    }
}
