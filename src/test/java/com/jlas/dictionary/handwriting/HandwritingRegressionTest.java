package com.jlas.dictionary.handwriting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("slow")
class HandwritingRegressionTest {
    private static final Path SVG_DIR = Path.of("src/main/resources/static/assets/kanjivg/kanji");
    private static final Path SEED = Path.of("data/seed_jlas/kanji_data.json");
    private static final String[] VARIANTS = {"original", "tall", "wide", "merged", "split", "jitter", "sparse"};

    @Test
    void ranksJlptN5ThroughN3AcrossCanvasVariants() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(SVG_DIR) && Files.isRegularFile(SEED));
        JsonNode seed = new ObjectMapper().readTree(SEED.toFile()).path("kanji");
        Map<String, String> level = new HashMap<>();
        for (JsonNode row : seed) level.put(row.path("character").asText(), row.path("jlpt_level").asText());
        List<StrokeMatcher.Ref> refs = new ArrayList<>();
        Map<String, List<List<double[]>>> paths = new HashMap<>();
        try (var files = Files.list(SVG_DIR)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{5}\\.svg")).toList()) {
                int cp = Integer.parseInt(file.getFileName().toString().substring(0, 5), 16);
                if (cp < 0x4e00 || cp > 0x9fff) continue;
                String ch = new String(Character.toChars(cp));
                if (!level.containsKey(ch)) continue; // the DB index is based on the 2,540 seed entries
                List<List<double[]>> strokes = KanjiVgPath.strokesOf(Files.readString(file));
                if (strokes.isEmpty()) continue;
                String jlpt = level.get(ch);
                int priority = jlpt.matches("N[1-5]") ? jlpt.charAt(1) - '0' : 0;
                refs.add(StrokeMatcher.Ref.of(ch, strokes, priority));
                paths.put(ch, strokes);
            }
        }
        String selected = System.getProperty("handwriting.targets", "");
        String selectedVariants = System.getProperty("handwriting.variants", "");
        List<String> targets = paths.keySet().stream().filter(ch -> level.get(ch).matches("N[345]"))
                .filter(ch -> selected.isEmpty() || selected.contains("%04X".formatted(ch.codePointAt(0))))
                .sorted().limit(Integer.getInteger("handwriting.maxTargets", Integer.MAX_VALUE)).toList();
        assertTrue(!targets.isEmpty(), "No selected target kanji");
        long start = System.nanoTime();
        int[] top1 = new int[VARIANTS.length], top5 = new int[VARIANTS.length];
        List<String>[] failures = new List[VARIANTS.length];
        List<String>[] firstFailures = new List[VARIANTS.length];
        for (int v = 0; v < VARIANTS.length; v++) failures[v] = new ArrayList<>();
        for (int v = 0; v < VARIANTS.length; v++) firstFailures[v] = new ArrayList<>();
        for (String ch : targets) {
            for (int v = 0; v < VARIANTS.length; v++) {
                if (!selectedVariants.isEmpty() && !selectedVariants.contains(VARIANTS[v])) continue;
                List<List<double[]>> input = variant(paths.get(ch), v, ch.codePointAt(0));
                List<String> ranked = StrokeMatcher.rank(refs, input, 5);
                if (!ranked.isEmpty() && ranked.get(0).equals(ch)) top1[v]++;
                else firstFailures[v].add(ch);
                if (ranked.contains(ch)) top5[v]++;
                else failures[v].add(ch);
            }
        }
        long variantCount = java.util.Arrays.stream(VARIANTS)
                .filter(v -> selectedVariants.isEmpty() || selectedVariants.contains(v)).count();
        double ms = (System.nanoTime() - start) / 1_000_000.0 / (targets.size() * variantCount);
        System.out.printf("Handwriting index=%d targets=%d latency=%.2f ms/request%n", refs.size(), targets.size(), ms);
        for (int v = 0; v < VARIANTS.length; v++) {
            if (!selectedVariants.isEmpty() && !selectedVariants.contains(VARIANTS[v])) continue;
            System.out.printf("%s top1=%.1f%% top5=%.1f%% failures=%s%n", VARIANTS[v],
                    100.0 * top1[v] / targets.size(), 100.0 * top5[v] / targets.size(),
                    failures[v].stream().map(ch -> "U+%04X".formatted(ch.codePointAt(0))).toList());
            System.out.printf("%s top1Failures=%s%n", VARIANTS[v],
                    firstFailures[v].stream().map(ch -> "U+%04X".formatted(ch.codePointAt(0))).toList());
        }
        List<List<double[]>> sun = paths.get("日");
        assertTrue(sun != null, "日 SVG missing");
        List<List<double[]>> tall = variant(sun, 1, '日');
        assertTrue(StrokeMatcher.rank(refs, tall, 3).contains("日"), "tall 日 with four strokes");
        assertTrue(StrokeMatcher.rank(refs, fragment(tall, 6), 3).contains("日"), "tall 日 with six strokes");
        assertTrue(StrokeMatcher.rank(refs, fragment(tall, 11), 3).contains("日"), "sparse 日 with eleven strokes");
        // The seed omits 曰 and 廿; include their SVGs in this ambiguity check.
        List<StrokeMatcher.Ref> spotRefs = new ArrayList<>(refs);
        for (String ch : List.of("曰", "廿")) {
            Path file = SVG_DIR.resolve("%05x.svg".formatted(ch.codePointAt(0)));
            Assumptions.assumeTrue(Files.isRegularFile(file));
            List<List<double[]>> strokes = KanjiVgPath.strokesOf(Files.readString(file));
            paths.put(ch, strokes);
            spotRefs.add(StrokeMatcher.Ref.of(ch, strokes));
        }
        List<String> spotFailures = new ArrayList<>();
        for (String ch : "目自月耳身四皿二三一日曰甘廿土士未末人入".split("")) {
            List<List<double[]>> path = paths.get(ch);
            double[] box = spotBounds(path);
            int squeeze = "目自月耳身".contains(ch) ? 1 : "四皿二三一".contains(ch) ? 2
                    : box[2] - box[0] < box[3] - box[1] ? 1 : 2;
            for (int v : new int[]{0, squeeze}) {
                List<String> ranked = StrokeMatcher.rank(spotRefs, variant(paths.get(ch), v, ch.codePointAt(0)), 3);
                if (!ranked.contains(ch)) {
                    spotFailures.add("U+%04X %s -> %s".formatted(ch.codePointAt(0), VARIANTS[v],
                            ranked.stream().map(hit -> "U+%04X".formatted(hit.codePointAt(0))).toList()));
                }
            }
        }
        System.out.println("Handwriting spot failures=" + spotFailures);
        assertTrue(spotFailures.isEmpty(), "ambiguous and squeezed characters: " + spotFailures);
        List<List<double[]>> scribble = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            scribble.add(List.of(new double[]{5, 5 + i * 13}, new double[]{103, 103 - i * 13},
                    new double[]{5, 103 - i * 13}));
        }
        assertTrue(StrokeMatcher.rank(refs, scribble, 8).isEmpty(), "garbage scribble should be rejected");
        if (selected.isEmpty() && selectedVariants.isEmpty() && !Boolean.getBoolean("handwriting.baseline")) {
            int[] baselineTop1 = {750, 727, 675, 727, 749, 750, 750};
            int[] baselineTop5 = {750, 750, 742, 740, 750, 750, 750};
            for (int v = 0; v < VARIANTS.length; v++) {
                double threshold = v == 3 || v == 4 ? 0.85 : 0.95;
                assertTrue(top5[v] >= threshold * targets.size(), VARIANTS[v] + " top5 below " + threshold);
                if (targets.size() == 750) {
                    assertTrue(top1[v] >= baselineTop1[v], VARIANTS[v] + " top1 regressed from baseline");
                    assertTrue(top5[v] >= baselineTop5[v], VARIANTS[v] + " top5 regressed from baseline");
                }
            }
        }
    }

    private static double[] spotBounds(List<List<double[]>> strokes) {
        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (List<double[]> stroke : strokes) for (double[] point : stroke) {
            out[0] = Math.min(out[0], point[0]);
            out[1] = Math.min(out[1], point[1]);
            out[2] = Math.max(out[2], point[0]);
            out[3] = Math.max(out[3], point[1]);
        }
        return out;
    }

    private static List<List<double[]>> fragment(List<List<double[]>> strokes, int targetCount) {
        List<List<double[]>> out = new ArrayList<>(strokes);
        while (out.size() < targetCount) {
            int longest = -1;
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).size() < 4) continue;
                if (longest < 0 || out.get(i).size() > out.get(longest).size()) longest = i;
            }
            if (longest < 0) break;
            List<double[]> line = out.remove(longest);
            int middle = line.size() / 2;
            out.add(longest, new ArrayList<>(line.subList(middle, line.size())));
            out.add(longest, new ArrayList<>(line.subList(0, middle + 1)));
        }
        if (targetCount == 11) {
            int[] sparsePoints = {4, 2, 3, 4, 2, 3, 6, 2, 4, 2, 5};
            for (int i = 0; i < out.size(); i++) {
                out.set(i, samplePoints(out.get(i), sparsePoints[i]));
            }
        }
        return out;
    }

    private static List<double[]> samplePoints(List<double[]> line, int count) {
        double[] lengths = new double[line.size()];
        for (int i = 1; i < line.size(); i++) {
            lengths[i] = lengths[i - 1] + Math.hypot(line.get(i)[0] - line.get(i - 1)[0],
                    line.get(i)[1] - line.get(i - 1)[1]);
        }
        List<double[]> samples = new ArrayList<>(count);
        for (int p = 0, segment = 1; p < count; p++) {
            double distance = lengths[line.size() - 1] * p / (count - 1);
            while (segment < line.size() - 1 && lengths[segment] < distance) segment++;
            double span = lengths[segment] - lengths[segment - 1];
            double fraction = span < 1e-9 ? 0 : (distance - lengths[segment - 1]) / span;
            samples.add(new double[]{
                    line.get(segment - 1)[0] + fraction * (line.get(segment)[0] - line.get(segment - 1)[0]),
                    line.get(segment - 1)[1] + fraction * (line.get(segment)[1] - line.get(segment - 1)[1])});
        }
        return samples;
    }

    private static List<List<double[]>> variant(List<List<double[]>> source, int kind, int codepoint) {
        List<List<double[]>> strokes = new ArrayList<>();
        Random random = new Random(43000L + codepoint * 31L + kind);
        for (List<double[]> stroke : source) {
            List<double[]> points = new ArrayList<>();
            for (double[] point : stroke) {
                double x = point[0], y = point[1];
                if (kind == 1) x = 54.5 + 0.6 * (x - 54.5);
                if (kind == 2) y = 54.5 + 0.6 * (y - 54.5);
                if (kind == 5) {
                    double rx = (x - 54.5) * Math.cos(Math.toRadians(5)) - (y - 54.5) * Math.sin(Math.toRadians(5));
                    double ry = (x - 54.5) * Math.sin(Math.toRadians(5)) + (y - 54.5) * Math.cos(Math.toRadians(5));
                    x = rx + 54.5 + (random.nextDouble() * 10 - 5) * 109 / 360;
                    y = ry + 54.5 + (random.nextDouble() * 10 - 5) * 109 / 360;
                }
                // SVG -> 360 canvas -> DictionaryServiceImpl's 109 frame.
                points.add(new double[]{Math.max(0, Math.min(360, x * 360 / 109)) * 109 / 360,
                        Math.max(0, Math.min(360, y * 360 / 109)) * 109 / 360});
            }
            if (kind == 6 && points.size() > 3) points = List.of(points.get(0), points.get(points.size() / 2), points.get(points.size() - 1));
            strokes.add(points);
        }
        if (kind == 3 && strokes.size() >= 2) {
            List<double[]> joined = new ArrayList<>(strokes.get(0));
            joined.addAll(strokes.get(1));
            strokes.set(0, joined);
            strokes.remove(1);
        }
        if (kind == 4 && !strokes.isEmpty()) {
            int best = 0;
            for (int i = 1; i < strokes.size(); i++) if (strokes.get(i).size() > strokes.get(best).size()) best = i;
            List<double[]> line = strokes.get(best);
            if (line.size() >= 3) {
                int middle = line.size() / 2;
                strokes.set(best, new ArrayList<>(line.subList(0, middle + 1)));
                strokes.add(best + 1, new ArrayList<>(line.subList(middle, line.size())));
            }
        }
        return strokes;
    }
}
