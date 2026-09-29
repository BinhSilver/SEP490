package com.jlas.dictionary.handwriting;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Dữ liệu và bộ mô phỏng "người dùng vẽ" dùng chung cho các test nhận diện. */
public final class TestDrawings {

    private static final double FRONTEND_SIMPLIFY_EPSILON = 1.5 * StrokeMatcher.FRAME / 360;

    private TestDrawings() {
    }

    /** Nét chuẩn (theo thứ tự viết) của một Kanji có trong src/test/resources/kanjivg. */
    public static List<List<double[]>> canonical(char kanji) {
        String name = "/kanjivg/%05x.svg".formatted((int) kanji);
        try (InputStream in = TestDrawings.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing fixture " + name);
            return KanjiVgPath.strokesOf(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<StrokeMatcher.Ref> index(String kanji) {
        List<StrokeMatcher.Ref> refs = new ArrayList<>();
        for (char c : kanji.toCharArray()) refs.add(StrokeMatcher.Ref.of(String.valueOf(c), canonical(c)));
        return refs;
    }

    /**
     * Mô phỏng nét tay: lấy mẫu thưa như chuột, nhiễu từng điểm, lệch từng nét, rồi co giãn/dịch cả
     * bản vẽ. {@code sigma} tính theo đơn vị KanjiVG (khung 109), ~2 tương đương người vẽ hơi ẩu.
     */
    static List<List<double[]>> handDrawn(List<List<double[]>> strokes, Random rnd, double sigma) {
        double scale = 0.85 + rnd.nextDouble() * 0.2;
        double dx = (rnd.nextDouble() - 0.5) * 10, dy = (rnd.nextDouble() - 0.5) * 10;
        List<List<double[]>> out = new ArrayList<>();
        for (List<double[]> stroke : strokes) {
            double sx = rnd.nextGaussian() * sigma, sy = rnd.nextGaussian() * sigma;
            // Mỗi nét còn bị xoay/co giãn nhẹ quanh tâm nét, tỷ lệ theo sigma (ẩu hơn => méo hơn).
            double angle = rnd.nextGaussian() * 0.03 * sigma, stretch = 1 + rnd.nextGaussian() * 0.03 * sigma;
            double cx = stroke.stream().mapToDouble(q -> q[0]).average().orElse(0);
            double cy = stroke.stream().mapToDouble(q -> q[1]).average().orElse(0);
            double cos = Math.cos(angle) * stretch, sin = Math.sin(angle) * stretch;
            List<double[]> drawn = new ArrayList<>();
            for (int i = 0; i < stroke.size(); i += 2) {
                double[] p = stroke.get(i);
                double rx = cx + (p[0] - cx) * cos - (p[1] - cy) * sin;
                double ry = cy + (p[0] - cx) * sin + (p[1] - cy) * cos;
                double x = rx + sx + rnd.nextGaussian() * sigma * 0.5;
                double y = ry + sy + rnd.nextGaussian() * sigma * 0.5;
                drawn.add(new double[]{(x - 54.5) * scale + 54.5 + dx, (y - 54.5) * scale + 54.5 + dy});
            }
            out.add(drawn);
        }
        return out;
    }

    static <T> List<T> shuffled(List<T> list, Random rnd) {
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy, rnd);
        return copy;
    }

    /** Đảo chiều một số nét (người mới học hay vẽ ngược chiều). */
    static List<List<double[]>> reversedSome(List<List<double[]>> strokes, Random rnd, double probability) {
        List<List<double[]>> out = new ArrayList<>();
        for (List<double[]> stroke : strokes) {
            List<double[]> copy = new ArrayList<>(stroke);
            if (rnd.nextDouble() < probability) Collections.reverse(copy);
            out.add(copy);
        }
        return out;
    }

    /** Tách một nét thành hai nét liền nhau (nhấc bút giữa chừng), thay cho nét gốc. */
    static List<List<double[]>> splitOne(List<List<double[]>> strokes, Random rnd) {
        List<List<double[]>> out = new ArrayList<>(strokes);
        List<Integer> splittable = new ArrayList<>();
        for (int i = 0; i < out.size(); i++) if (out.get(i).size() >= 4) splittable.add(i);
        if (splittable.isEmpty()) return out;
        int index = splittable.get(rnd.nextInt(splittable.size()));
        List<double[]> stroke = out.remove(index);
        int cut = stroke.size() / 2;
        out.add(index, new ArrayList<>(stroke.subList(cut, stroke.size())));
        out.add(index, new ArrayList<>(stroke.subList(0, cut + 1)));
        return out;
    }

    /** Merge each consecutive pair, preserving the points the pen travels through between canonical strokes. */
    /** Tách {@code count} nét khác nhau làm đôi: mô phỏng người vẽ dùng NHIỀU nét hơn số nét chuẩn. */
    static List<List<double[]>> splitSeveral(List<List<double[]>> strokes, Random rnd, int count) {
        List<List<double[]>> out = new ArrayList<>(strokes);
        for (int i = 0; i < count; i++) {
            int before = out.size();
            out = splitOne(out, rnd);
            if (out.size() == before) break; // không còn nét nào đủ dài để tách
        }
        return out;
    }

    /** Tách mọi nét có đủ điểm làm đôi mà không thêm hay bỏ đường mực. */
    static List<List<double[]>> splitEveryStroke(List<List<double[]>> strokes) {
        List<List<double[]>> out = new ArrayList<>();
        for (List<double[]> stroke : strokes) {
            if (stroke.size() < 4) {
                out.add(new ArrayList<>(stroke));
                continue;
            }
            int cut = stroke.size() / 2;
            out.add(new ArrayList<>(stroke.subList(0, cut + 1)));
            out.add(new ArrayList<>(stroke.subList(cut, stroke.size())));
        }
        return out;
    }

    /** Cùng đường bao của 口, chỉ khác các vị trí người viết nhấc bút. */
    static List<List<List<double[]>>> mouthOutlineDrawings() {
        List<List<double[]>> strokes = canonical('口');
        List<double[]> left = strokes.get(0);
        List<double[]> topAndRight = strokes.get(1);
        List<double[]> bottom = strokes.get(2);
        List<double[]> rightAndTopReversed = reversed(topAndRight);

        List<double[]> oneStroke = merge(left, bottom, rightAndTopReversed);
        List<List<double[]>> twoStrokes = List.of(merge(left, bottom), rightAndTopReversed);

        int cut = bottom.size() / 2;
        List<List<double[]>> fourStrokes = List.of(left, topAndRight,
                new ArrayList<>(bottom.subList(0, cut + 1)),
                new ArrayList<>(bottom.subList(cut, bottom.size())));
        return List.of(List.of(oneStroke), twoStrokes, strokes, fourStrokes);
    }

    /** Hình 口 vẽ tay hơi lệch như trên canvas; bốn biến thể có cùng đường mực tuyệt đối. */
    static List<List<List<double[]>>> mouthRectangleDrawings() {
        double[] topLeft = {39, 31}, bottomLeft = {39, 75}, bottomRight = {75, 74}, topRight = {74, 31};
        List<double[]> left = List.of(topLeft, new double[]{38, 43}, new double[]{38.5, 58}, bottomLeft);
        List<double[]> bottom = List.of(bottomLeft, new double[]{49, 75.5}, new double[]{62, 75}, bottomRight);
        List<double[]> right = List.of(bottomRight, new double[]{75, 61}, new double[]{75.5, 45}, topRight);
        List<double[]> top = List.of(topRight, new double[]{62, 30}, new double[]{50, 31}, topLeft);
        return List.of(
                List.of(merge(left, bottom, right, top)),
                List.of(left, merge(reversed(top), reversed(right), reversed(bottom))),
                List.of(left, merge(top, TestDrawings.reversed(right)), bottom),
                List.of(left, bottom, right, top));
    }

    static List<List<double[]>> mergeConsecutivePairs(List<List<double[]>> strokes) {
        List<List<double[]>> out = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i += 2) {
            if (i + 1 < strokes.size()) out.add(merge(strokes.get(i), strokes.get(i + 1)));
            else out.add(new ArrayList<>(strokes.get(i)));
        }
        return out;
    }

    @SafeVarargs
    static List<double[]> merge(List<double[]>... strokes) {
        List<double[]> out = new ArrayList<>();
        for (List<double[]> stroke : strokes) out.addAll(stroke);
        return out;
    }

    static List<double[]> reversed(List<double[]> stroke) {
        List<double[]> out = new ArrayList<>(stroke);
        Collections.reverse(out);
        return out;
    }

    /** Mirrors handwriting-core.js simplify() after converting its 1.5 px epsilon to the 109-unit frame. */
    static List<List<double[]>> frontendSimplified(List<List<double[]>> strokes) {
        return strokes.stream().map(TestDrawings::frontendSimplifiedStroke).toList();
    }

    private static List<double[]> frontendSimplifiedStroke(List<double[]> points) {
        if (points.size() <= 2) return new ArrayList<>(points);
        boolean[] keep = new boolean[points.size()];
        keep[0] = keep[points.size() - 1] = true;
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, points.size() - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int first = range[0], last = range[1], index = -1;
            double maxDistance = 0;
            for (int i = first + 1; i < last; i++) {
                double distance = perpendicularDistance(points.get(i), points.get(first), points.get(last));
                if (distance > maxDistance) {
                    maxDistance = distance;
                    index = i;
                }
            }
            if (index != -1 && maxDistance > FRONTEND_SIMPLIFY_EPSILON) {
                keep[index] = true;
                stack.push(new int[]{first, index});
                stack.push(new int[]{index, last});
            }
        }
        List<double[]> simplified = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) if (keep[i]) simplified.add(points.get(i));
        return simplified;
    }

    private static double perpendicularDistance(double[] point, double[] start, double[] end) {
        double dx = end[0] - start[0], dy = end[1] - start[1];
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) return Math.hypot(point[0] - start[0], point[1] - start[1]);
        double t = Math.max(0, Math.min(1,
                ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / lengthSquared));
        return Math.hypot(point[0] - (start[0] + t * dx), point[1] - (start[1] + t * dy));
    }

    /** Nguệch ngoạc: {@code strokes} nét, mỗi nét là đường gấp khúc ngẫu nhiên khắp khung. */
    static List<List<double[]>> scribble(Random rnd, int strokes, int pointsPerStroke) {
        List<List<double[]>> out = new ArrayList<>();
        for (int s = 0; s < strokes; s++) {
            List<double[]> line = new ArrayList<>();
            for (int p = 0; p < pointsPerStroke; p++) line.add(new double[]{rnd.nextDouble() * 109, rnd.nextDouble() * 109});
            out.add(line);
        }
        return out;
    }

    /** Một nét ngắn ngẫu nhiên không thuộc chữ nào, mô phỏng nét thừa. */
    static List<double[]> strayStroke(Random rnd) {
        double x = 20 + rnd.nextDouble() * 60, y = 20 + rnd.nextDouble() * 60;
        return List.of(new double[]{x, y}, new double[]{x + 15, y + 4}, new double[]{x + 30, y + 9});
    }
}
