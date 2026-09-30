package com.jlas.dictionary.handwriting;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrokeMatcherTest {

    private static final String ALL = "口日作休体本木末未三二一川土士目白語";
    private static final List<StrokeMatcher.Ref> INDEX = TestDrawings.index(ALL);

    private static List<String> rank(List<List<double[]>> drawing) {
        return StrokeMatcher.rank(INDEX, drawing, 8);
    }

    private static List<String> top3(List<List<double[]>> drawing) {
        return StrokeMatcher.rank(INDEX, drawing, 3);
    }

    private static List<List<double[]>> drawn(char kanji, long seed) {
        return TestDrawings.handDrawn(TestDrawings.canonical(kanji), new Random(seed), 1.5);
    }

    private static List<List<List<double[]>>> mergedSunDrawings() {
        List<List<double[]>> s = TestDrawings.canonical('日');
        List<double[]> cap = TestDrawings.merge(TestDrawings.reversed(s.get(0)), s.get(1));
        List<double[]> closedBox = TestDrawings.merge(s.get(1), TestDrawings.reversed(s.get(3)),
                TestDrawings.reversed(s.get(0)));
        return List.of(
                List.of(cap, s.get(2), s.get(3)),
                List.of(s.get(0), s.get(1), TestDrawings.merge(s.get(2), s.get(3))),
                List.of(closedBox, s.get(2)),
                List.of(cap, TestDrawings.merge(s.get(2), s.get(3))),
                List.of(s.get(0), TestDrawings.merge(s.get(1), s.get(2), s.get(3))),
                List.of(TestDrawings.merge(s.get(0), s.get(1), s.get(2)), s.get(3)),
                List.of(TestDrawings.merge(closedBox, s.get(2))));
    }

    @Test
    void emptyCanvasHasNoCandidates() {
        assertTrue(rank(List.of()).isEmpty());
    }

    @Test
    void neverReturnsMoreThanTheLimit() {
        assertEquals(3, StrokeMatcher.rank(INDEX, TestDrawings.canonical('日'), 3).size());
        assertTrue(StrokeMatcher.rank(INDEX, TestDrawings.canonical('日'), 0).isEmpty());
    }

    @Test
    void everyFixtureKanjiIsRecognisedFromItsOwnHandDrawnStrokes() {
        for (char kanji : ALL.toCharArray()) {
            List<List<double[]>> drawing = drawn(kanji, 7);
            List<String> ranked = rank(drawing);
            assertEquals(String.valueOf(kanji), ranked.get(0), "top-1 for " + kanji + " but got " + ranked);
        }
    }

    @Test
    void singleStrokeStillProducesCandidates() {
        List<String> ranked = rank(List.of(TestDrawings.canonical('日').get(0)));

        assertFalse(ranked.isEmpty());
    }

    @Test
    void sunIsRecognisedWithCanonicalStrokeBoundaries() {
        assertEquals("日", rank(TestDrawings.canonical('日')).get(0));
    }

    @Test
    void mouthOutlineIsRecognisedRegardlessOfOneTwoThreeOrFourStrokeBoundaries() {
        int strokeCount = 0;
        for (List<List<double[]>> drawing : TestDrawings.mouthOutlineDrawings()) {
            strokeCount++;
            List<String> ranked = rank(drawing);
            assertFalse(ranked.isEmpty(), strokeCount + " strokes returned no candidates");
            assertEquals("口", ranked.get(0), strokeCount + " strokes -> " + ranked);
        }
    }

    @Test
    void handDrawnMouthRectangleIsRecognisedAcrossOneTwoThreeAndFourStrokeBoundaries() {
        int strokeCount = 0;
        for (List<List<double[]>> dense : TestDrawings.mouthRectangleDrawings()) {
            strokeCount++;
            List<List<double[]>> drawing = TestDrawings.frontendSimplified(dense);
            List<String> ranked = rank(drawing);
            assertFalse(ranked.isEmpty(), strokeCount + " strokes returned no candidates");
            assertEquals("口", ranked.get(0), strokeCount + " strokes -> " + ranked);
        }
    }

    @Test
    void sunIsRecognisedWhenCanonicalStrokesAreMerged() {
        int variant = 0;
        for (List<List<double[]>> drawing : mergedSunDrawings()) {
            variant++;
            assertSunInTop3(variant, "exact dense", drawing);
            assertSunInTop3(variant, "exact RDP", TestDrawings.frontendSimplified(drawing));

            for (double sigma : new double[]{1.5, 3.0}) {
                List<List<double[]>> noisy = TestDrawings.handDrawn(drawing, new Random(37), sigma);
                assertSunInTop3(variant, "sigma " + sigma + " dense", noisy);
                assertSunInTop3(variant, "sigma " + sigma + " RDP", TestDrawings.frontendSimplified(noisy));
            }
        }
    }

    private static void assertSunInTop3(int variant, String representation, List<List<double[]>> drawing) {
        List<String> ranked = top3(drawing);
        System.out.println("[handwriting] sun variant " + variant + " strokes=" + drawing.size() + " "
                + representation + " points=" + drawing.stream().mapToInt(List::size).sum()
                + " rank=" + (ranked.indexOf("日") + 1));
        assertTrue(ranked.contains("日"), "variant " + variant + ", " + drawing.size() + " strokes "
                + representation + " -> " + ranked);
    }

    @Test
    void partialAndWrongOrderMergedSunAreNotRejected() {
        List<List<double[]>> canonical = TestDrawings.canonical('日');
        assertTrue(rank(canonical.subList(0, 2)).contains("日"));

        List<List<double[]>> merged = mergedSunDrawings().get(2);
        List<List<double[]>> shuffled = TestDrawings.shuffled(merged, new Random(5));
        assertTrue(rank(shuffled).contains("日"), shuffled.size() + " shuffled strokes -> " + rank(shuffled));
    }

    @Test
    void partialDrawingRanksKanjisThatContainThoseStrokes() {
        // 2 nét đầu của 休 là bộ 亻: ứng viên phải nằm trong họ 亻, không phải chữ không liên quan.
        List<List<double[]>> firstTwo = drawn('休', 3).subList(0, 2);

        List<String> top3 = top3(firstTwo);

        assertTrue(top3.contains("休") || top3.contains("体") || top3.contains("作"), top3.toString());
    }

    @Test
    void incorrectStrokeOrderIsTolerated() {
        for (char kanji : new char[]{'作', '語', '日', '本'}) {
            List<List<double[]>> scrambled = TestDrawings.shuffled(drawn(kanji, 11), new Random(5));

            List<String> top3 = top3(scrambled);

            assertTrue(top3.contains(String.valueOf(kanji)), kanji + " -> " + top3);
        }
    }

    @Test
    void extraStrokeIsTolerated() {
        List<List<double[]>> withStray = new ArrayList<>(drawn('日', 2));
        withStray.add(TestDrawings.strayStroke(new Random(1)));

        assertTrue(top3(withStray).contains("日"));
    }

    @Test
    void missingStrokeIsTolerated() {
        List<List<double[]>> withoutLast = drawn('語', 4);
        withoutLast = withoutLast.subList(0, withoutLast.size() - 1);

        assertTrue(top3(withoutLast).contains("語"));
    }

    @Test
    void visuallySimilarKanjiAreToldApart() {
        assertEquals("末", rank(drawn('末', 21)).get(0));
        assertEquals("未", rank(drawn('未', 21)).get(0));
        assertEquals("土", rank(drawn('土', 21)).get(0));
        assertEquals("士", rank(drawn('士', 21)).get(0));
    }

    @Test
    void differentSizeAndOffsetStillMatchesOnceDrawingIsComplete() {
        List<List<double[]>> small = new ArrayList<>();
        for (List<double[]> stroke : drawn('作', 9)) {
            List<double[]> s = new ArrayList<>();
            for (double[] p : stroke) s.add(new double[]{p[0] * 0.6 + 8, p[1] * 0.6 + 30});
            small.add(s);
        }

        assertTrue(top3(small).contains("作"));
    }

    @Test
    void strokesDrawnInTheOppositeDirectionAreStillRecognised() {
        for (char kanji : new char[]{'日', '作', '本', '語'}) {
            List<List<double[]>> reversed = TestDrawings.reversedSome(drawn(kanji, 13), new Random(2), 1.0);

            assertTrue(top3(reversed).contains(String.valueOf(kanji)), kanji + " -> " + top3(reversed));
        }
    }

    /**
     * Vẽ NHIỀU nét hơn số nét chuẩn (tách nét) không được làm mất chữ. Kiểm tra cho mọi chữ trong
     * index chứ không riêng một chữ, vì đây là tính chất của thuật toán chứ không của chữ nào.
     */
    @Test
    void drawingWithMoreStrokesThanTheCanonicalCountStillFindsTheKanji() {
        for (char kanji : ALL.toCharArray()) {
            // Chữ chỉ 1-2 nét mà tách đôi hết thì bản vẽ trùng hình với chữ khác (二 thành 4 đoạn ngang
            // giống 三/四) và các mảnh nét quá ngắn so với nét chuẩn — giới hạn đã biết, xem javadoc lớp.
            if (TestDrawings.canonical(kanji).size() < 3) continue;
            for (int extra = 1; extra <= 3; extra++) {
                List<List<double[]>> drawing =
                        TestDrawings.splitSeveral(drawn(kanji, 17), new Random(extra), extra);
                if (drawing.size() <= TestDrawings.canonical(kanji).size()) continue; // chữ quá ít nét để tách

                // Top-8 là đúng thứ giao diện hiển thị; tách nhiều nét có thể trùng hình với chữ khác
                // (vẽ 二 thành 4 đoạn ngang trông như 三/四) nên đòi top-3 là quá chặt.
                List<String> ranked = rank(drawing);

                assertTrue(ranked.contains(String.valueOf(kanji)),
                        kanji + " vẽ bằng " + drawing.size() + " nét (chuẩn "
                                + TestDrawings.canonical(kanji).size() + ") -> " + ranked);
            }
        }
    }

    /** Đúng tình huống người dùng báo: 日 có 4 nét chuẩn nhưng được vẽ bằng 5, 6 rồi 7 nét. */
    @Test
    void sunDrawnWithFiveSixOrSevenStrokesIsStillACandidate() {
        for (int extra = 1; extra <= 3; extra++) {
            List<List<double[]>> drawing =
                    TestDrawings.splitSeveral(TestDrawings.canonical('日'), new Random(3), extra);
            assertEquals(4 + extra, drawing.size(), "cần đúng " + (4 + extra) + " nét để kiểm tra");

            for (List<List<double[]>> variant : List.of(drawing, TestDrawings.frontendSimplified(drawing))) {
                List<String> ranked = top3(variant);
                assertTrue(ranked.contains("日"), (4 + extra) + " nét -> " + ranked);
            }
        }
    }

    /**
     * Một nét đơn khớp hoàn hảo KHÔNG được đè cả hình vẽ: chữ một nét như 一 không bao giờ được xếp
     * trên chữ mà toàn bộ bản vẽ thể hiện. Kiểm tra trên mọi chữ nhiều nét trong index.
     */
    @Test
    void aSinglePerfectlyMatchingStrokeNeverOutranksTheWholeShape() {
        String singleStrokeKanji = "一";
        for (char kanji : ALL.toCharArray()) {
            List<List<double[]>> canonical = TestDrawings.canonical(kanji);
            if (canonical.size() < 3) continue;
            for (List<List<double[]>> drawing : List.of(canonical, TestDrawings.mergeConsecutivePairs(canonical))) {
                List<String> ranked = rank(drawing);
                int shape = ranked.indexOf(String.valueOf(kanji));
                int singleStroke = ranked.indexOf(singleStrokeKanji);

                assertTrue(shape >= 0, kanji + " biến mất khỏi ứng viên: " + ranked);
                assertTrue(singleStroke < 0 || shape < singleStroke,
                        kanji + " (" + drawing.size() + " nét) phải xếp trên " + singleStrokeKanji + ": " + ranked);
            }
        }
    }

    /**
     * Bản vẽ chưa xong phải cho NHIỀU ứng viên hợp lý, không bị ép về một chữ duy nhất — người dùng
     * còn đang vẽ tiếp nên hệ thống chưa được phép "chốt".
     */
    @Test
    void partialDrawingKeepsSeveralCandidatesOpen() {
        for (char kanji : ALL.toCharArray()) {
            List<List<double[]>> canonical = TestDrawings.canonical(kanji);
            if (canonical.size() < 4) continue;
            List<List<double[]>> partial = canonical.subList(0, canonical.size() / 2);

            List<String> ranked = rank(partial);

            assertTrue(ranked.size() >= 3,
                    "vẽ dở " + kanji + " (" + partial.size() + "/" + canonical.size()
                            + " nét) chỉ cho " + ranked.size() + " ứng viên: " + ranked);
        }
    }

    /**
     * RECALL: vẽ liền một nét (không nhấc bút) là cách người mới học hay dùng. Bản vẽ như vậy KHÔNG
     * được cho danh sách rỗng — người dùng sẽ thấy "Chưa tìm thấy chữ phù hợp" dù hình hoàn toàn hợp lý.
     */
    @Test
    void oneContinuousStrokeAlwaysYieldsCandidates() {
        for (char kanji : ALL.toCharArray()) {
            List<List<double[]>> canonical = TestDrawings.canonical(kanji);
            // Giới hạn đã biết: một nét vẽ chỉ được phép gộp tối đa 8 nét chuẩn, nên chữ nhiều nét hơn
            // (ví dụ 語 14 nét) vẽ liền một mạch vẫn cho danh sách rỗng. Xem mục giới hạn ở javadoc lớp.
            if (canonical.size() > 8) continue;
            List<double[]> single = TestDrawings.merge(canonical.toArray(new List[0]));

            for (List<List<double[]>> drawing : List.of(List.of(single),
                    TestDrawings.frontendSimplified(List.of(single)))) {
                assertFalse(rank(drawing).isEmpty(),
                        kanji + " vẽ liền một nét (" + canonical.size() + " nét chuẩn) -> không có ứng viên nào");
            }
        }
    }

    @Test
    void junkInputYieldsNoCandidatesInsteadOfConfidentWrongAnswers() {
        List<double[]> cornerTap = List.of(new double[]{5, 5});
        assertTrue(rank(List.of(cornerTap)).isEmpty(), "a tap in the corner is not a kanji stroke");

        Random rnd = new Random(4);
        int rejected = 0, simplifiedRejected = 0;
        for (int i = 0; i < 50; i++) {
            List<List<double[]>> scribble = TestDrawings.scribble(rnd, 2 + i % 3, 8);
            if (rank(scribble).isEmpty()) rejected++;
            if (rank(TestDrawings.frontendSimplified(scribble)).isEmpty()) simplifiedRejected++;
        }
        assertTrue(rejected >= 45, "wiggly scribbles should almost always be rejected, rejected=" + rejected);
        assertTrue(simplifiedRejected >= 45,
                "RDP wiggly scribbles should almost always be rejected, rejected=" + simplifiedRejected);
    }

    @Test
    void aTapWhereAKanjiDotWouldBeStillProducesCandidates() {
        assertFalse(rank(List.of(List.of(new double[]{54, 22}))).isEmpty());
    }

    @Test
    void degenerateStrokesDoNotThrow() {
        List<double[]> dot = List.of(new double[]{50, 50});
        List<double[]> stationary = List.of(new double[]{50, 50}, new double[]{50, 50});

        assertDoesNotThrow(() -> rank(List.of(dot)));
        assertDoesNotThrow(() -> rank(List.of(stationary, dot)));
    }

}
