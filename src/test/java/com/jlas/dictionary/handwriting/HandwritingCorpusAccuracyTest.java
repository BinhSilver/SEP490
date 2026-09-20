package com.jlas.dictionary.handwriting;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Đo độ chính xác nhận diện trên dữ liệu KanjiVG thật khi bị "vẽ ẩu": sai thứ tự, thiếu nét,
 * thừa nét, vẽ dở. Chỉ chạy khi thư mục SVG đã được khôi phục (nó bị gitignore, xem
 * data/dictionary-seed/README.md); nếu không sẽ bị bỏ qua thay vì làm hỏng CI.
 *
 * <p>Mặc định index 1/3 số file KanjiVG cơ bản (cỡ tương đương bảng {@code kanji} production). Truyền
 * {@code -Dhandwriting.charset=<file utf-8>} để giới hạn theo đúng tập Kanji trong bảng {@code kanji}.</p>
 */
class HandwritingCorpusAccuracyTest {

    private static final Path SVG_DIR = Path.of("src", "main", "resources", "static", "assets", "kanjivg", "kanji");
    private static final int SAMPLES = 150;
    /** ~4/109 ≈ 3,7% cạnh canvas: người vẽ chuột khá ẩu, khó hơn nhiều so với nhiễu nhẹ. */
    private static final double SIGMA = 4;

    private static List<StrokeMatcher.Ref> index;
    private static List<String> samples;
    private static Map<String, List<List<double[]>>> strokesByChar;

    @BeforeAll
    static void loadCorpus() throws IOException {
        assumeTrue(Files.isDirectory(SVG_DIR), "KanjiVG SVG chưa được khôi phục — bỏ qua test độ chính xác");
        String charset = System.getProperty("handwriting.charset");
        String allowed = charset == null ? null : Files.readString(Path.of(charset), StandardCharsets.UTF_8);

        strokesByChar = new LinkedHashMap<>();
        int position = 0; // không có danh sách thì lấy 1/3 file để index cỡ ~ production (2.136 chữ)
        try (Stream<Path> files = Files.list(SVG_DIR)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f]{5}\\.svg")) continue;
                int codePoint = Integer.parseInt(name.substring(0, 5), 16);
                // KanjiVG cũng chứa kana, chữ Latin, dấu câu; bảng kanji của J-LAS chỉ có chữ Hán.
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) continue;
                String character = new String(Character.toChars(codePoint));
                if (allowed != null ? !allowed.contains(character)
                        : (++position % 3 != 0 && !"口".equals(character))) continue;
                List<List<double[]>> strokes = KanjiVgPath.strokesOf(Files.readString(file, StandardCharsets.UTF_8));
                if (!strokes.isEmpty()) strokesByChar.put(character, strokes);
            }
        }
        index = new ArrayList<>();
        strokesByChar.forEach((c, s) -> index.add(StrokeMatcher.Ref.of(c, s)));
        List<String> all = new ArrayList<>(strokesByChar.keySet());
        int step = Math.max(1, all.size() / SAMPLES);
        samples = new ArrayList<>();
        for (int i = 0; i < all.size(); i += step) samples.add(all.get(i));
    }

    private interface Scenario {
        /** Trả về bản vẽ giả lập, hoặc null nếu chữ này không áp dụng được. */
        List<List<double[]>> draw(List<List<double[]>> canonical, Random rnd);
    }

    private static double[] evaluate(String name, Scenario scenario) {
        Random rnd = new Random(42);
        int total = 0, top1 = 0, top8 = 0;
        for (String character : samples) {
            List<List<double[]>> drawing = scenario.draw(strokesByChar.get(character), rnd);
            if (drawing == null) continue;
            total++;
            List<String> ranked = StrokeMatcher.rank(index, drawing, 8);
            if (!ranked.isEmpty() && ranked.get(0).equals(character)) top1++;
            if (ranked.contains(character)) top8++;
        }
        double[] rates = {(double) top1 / total, (double) top8 / total};
        System.out.printf("[handwriting] %-28s n=%d top1=%.1f%% top8=%.1f%% (index=%d)%n",
                name, total, rates[0] * 100, rates[1] * 100, index.size());
        return rates;
    }

    @Test
    void recognisesCarelessDrawingsAcrossTheCorpus() {
        double[] correct = evaluate("correct order",
                (s, r) -> TestDrawings.handDrawn(s, r, SIGMA));
        double[] shuffled = evaluate("wrong stroke order",
                (s, r) -> TestDrawings.shuffled(TestDrawings.handDrawn(s, r, SIGMA), r));
        double[] missing = evaluate("one stroke missing",
                (s, r) -> {
                    if (s.size() < 4) return null;
                    List<List<double[]>> d = new ArrayList<>(TestDrawings.handDrawn(s, r, SIGMA));
                    d.remove(r.nextInt(d.size()));
                    return d;
                });
        double[] extra = evaluate("one extra stroke",
                (s, r) -> {
                    List<List<double[]>> d = new ArrayList<>(TestDrawings.handDrawn(s, r, SIGMA));
                    d.add(r.nextInt(d.size() + 1), TestDrawings.strayStroke(r));
                    return d;
                });
        double[] partial = evaluate("first half of strokes",
                (s, r) -> s.size() < 6 ? null : TestDrawings.handDrawn(s, r, SIGMA).subList(0, (s.size() + 1) / 2));

        double reversed = evaluate("half the strokes reversed",
                (s, r) -> TestDrawings.reversedSome(TestDrawings.handDrawn(s, r, SIGMA), r, 0.5))[1];
        double split = evaluate("one stroke split in two",
                (s, r) -> TestDrawings.splitOne(TestDrawings.handDrawn(s, r, SIGMA), r))[1];
        // Người dùng vẽ hẳn nhiều nét hơn số nét chuẩn (tách 3 nét) — số nét không được là bộ lọc cứng.
        double severalSplit = evaluate("three strokes split",
                (s, r) -> s.size() < 4 ? null : TestDrawings.splitSeveral(TestDrawings.handDrawn(s, r, SIGMA), r, 3))[1];

        // Nguệch ngoạc không được cho ra ứng viên "tự tin": kiểm tra trên toàn bộ index.
        Random rnd = new Random(11);
        int trials = 100, rejected = 0, simplifiedRejected = 0;
        for (int i = 0; i < trials; i++) {
            List<List<double[]>> scribble = TestDrawings.scribble(rnd, 2 + i % 4, 8);
            if (StrokeMatcher.rank(index, scribble, 8).isEmpty()) rejected++;
            if (StrokeMatcher.rank(index, TestDrawings.frontendSimplified(scribble), 8).isEmpty()) {
                simplifiedRejected++;
            }
        }
        System.out.printf("[handwriting] wiggly scribbles rejected: %d/%d%n", rejected, trials);
        System.out.printf("[handwriting] RDP wiggly scribbles rejected: %d/%d%n", simplifiedRejected, trials);

        // Lưu ý: dữ liệu mô phỏng suy ra từ chính KanjiVG nên đây chỉ là test hồi quy, KHÔNG phải số đo
        // độ chính xác với chữ viết tay thật (xem Known Limitations trong báo cáo).
        // Ngưỡng đặt dưới số đo thực tế (≈100/100/100/95-98/82-92%) để bắt hồi quy mà không flaky.
        assertTrue(correct[0] >= 0.95, "correct order top1 = " + correct[0]);
        assertTrue(correct[1] >= 0.97, "correct order top8 = " + correct[1]);
        assertTrue(shuffled[1] >= 0.97, "wrong order top8 = " + shuffled[1]);
        assertTrue(missing[1] >= 0.95, "missing top8 = " + missing[1]);
        assertTrue(extra[1] >= 0.94, "extra top8 = " + extra[1]);
        assertTrue(partial[1] >= 0.75, "partial top8 = " + partial[1]);
        assertTrue(reversed >= 0.97, "reversed-direction top8 = " + reversed);
        assertTrue(split >= 0.88, "split-stroke top8 = " + split);
        assertTrue(severalSplit >= 0.88, "three-strokes-split top8 = " + severalSplit);
        assertTrue(rejected >= 90, "junk rejected = " + rejected + "/" + trials);
        assertTrue(simplifiedRejected >= 90,
                "RDP junk rejected = " + simplifiedRejected + "/" + trials);
    }

    @Test
    void recognisesDrawingsWithConsecutiveStrokesMerged() {
        double[] merged = evaluate("consecutive pairs merged",
                (s, r) -> TestDrawings.frontendSimplified(
                        TestDrawings.handDrawn(TestDrawings.mergeConsecutivePairs(s), r, 3)));

        assertTrue(merged[1] >= 0.70, "merged-stroke top8 = " + merged[1]);
    }

    @Test
    void mouthOutlineRanksFirstAcrossOneTwoThreeAndFourStrokeBoundariesInTheCorpus() {
        int strokeCount = 0;
        for (List<List<double[]>> drawing : TestDrawings.mouthOutlineDrawings()) {
            strokeCount++;
            List<String> ranked = StrokeMatcher.rank(index, drawing, 8);
            assertTrue(strokeCount < 4
                            ? !ranked.isEmpty() && "口".equals(ranked.get(0))
                            : ranked.contains("口"),
                    strokeCount + " strokes -> " + ranked);
        }
    }

    @Test
    void handDrawnMouthRectangleRanksFirstAcrossStrokeBoundariesInTheCorpus() {
        int strokeCount = 0;
        for (List<List<double[]>> dense : TestDrawings.mouthRectangleDrawings()) {
            strokeCount++;
            List<List<double[]>> drawing = TestDrawings.frontendSimplified(dense);
            List<String> ranked = StrokeMatcher.rank(index, drawing, 8);
            assertTrue(!ranked.isEmpty() && "口".equals(ranked.get(0)), strokeCount + " strokes -> " + ranked);
        }
    }

    @Test
    void recognisesSameInkWhenEveryStrokeIsSplitAtAnExtraPenLift() {
        double[] splitEveryStroke = evaluate("every stroke split",
                (s, r) -> TestDrawings.frontendSimplified(
                        TestDrawings.handDrawn(TestDrawings.splitEveryStroke(s), r, 3)));

        assertTrue(splitEveryStroke[1] >= 0.80, "every-stroke-split top8 = " + splitEveryStroke[1]);
    }

    @Test
    void recognitionOverTheWholeIndexIsFastEnoughForRealtime() {
        // Chi phí phụ thuộc số nét người dùng đã vẽ (nhiều nét => ghép cặp lâu hơn nhưng ít ứng viên
        // qua được bước loại nhanh), nên đo nhiều cỡ và lấy lớn nhất.
        double worst = 0;
        for (int strokeCount : new int[]{1, 3, 6, 10, 14, 20}) {
            List<List<double[]>> canonical = strokesByChar.values().stream()
                    .filter(s -> s.size() == strokeCount).findFirst().orElse(null);
            if (canonical == null) continue;
            List<List<double[]>> drawing = TestDrawings.handDrawn(canonical, new Random(1), SIGMA);
            StrokeMatcher.rank(index, drawing, 8); // warm-up JIT

            long start = System.nanoTime();
            int runs = 20;
            for (int i = 0; i < runs; i++) StrokeMatcher.rank(index, drawing, 8);
            double millis = (System.nanoTime() - start) / 1e6 / runs;
            System.out.printf("[handwriting] %2d strokes over %d kanji: %.1f ms%n", strokeCount, index.size(), millis);
            worst = Math.max(worst, millis);

            List<List<double[]>> merged = TestDrawings.frontendSimplified(
                    TestDrawings.handDrawn(TestDrawings.mergeConsecutivePairs(canonical), new Random(1), 3));
            StrokeMatcher.rank(index, merged, 8);
            start = System.nanoTime();
            for (int i = 0; i < runs; i++) StrokeMatcher.rank(index, merged, 8);
            millis = (System.nanoTime() - start) / 1e6 / runs;
            System.out.printf("[handwriting] %2d -> %2d merged over %d kanji: %.1f ms%n",
                    strokeCount, merged.size(), index.size(), millis);
            worst = Math.max(worst, millis);
        }
        assertTrue(worst < 100, "recognition too slow for realtime: " + worst + " ms");
    }
}
