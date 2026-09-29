package com.jlas.dictionary.handwriting;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Đọc path SVG của KanjiVG thành polyline. KanjiVG chỉ dùng các lệnh M/m, C/c, S/s (đã kiểm tra
 * trên toàn bộ 11.661 file) nên không cần bộ parse SVG đầy đủ.
 */
final class KanjiVgPath {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z]|-?(?:\\d+\\.?\\d*|\\.\\d+)");
    private static final int CURVE_STEPS = 8;

    /** Mỗi nét KanjiVG là một {@code <path id="kvg:XXXXX-sN" d="...">}, xuất hiện đúng thứ tự viết. */
    private static final Pattern STROKE_PATH =
            Pattern.compile("<path id=\"kvg:[0-9a-f]+(?:-[A-Za-z0-9]+)?-s\\d+\"[^>]*?\\sd=\"([^\"]+)\"");

    private KanjiVgPath() {
    }

    /** Đọc các nét (theo thứ tự viết) từ nội dung file SVG của KanjiVG. */
    static List<List<double[]>> strokesOf(String svg) {
        List<List<double[]>> strokes = new ArrayList<>();
        Matcher m = STROKE_PATH.matcher(svg);
        while (m.find()) strokes.add(flatten(m.group(1)));
        return strokes;
    }

    /** Trả về các điểm {x, y} nằm dọc path. */
    static List<double[]> flatten(String d) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(d);
        while (m.find()) tokens.add(m.group());

        List<double[]> points = new ArrayList<>();
        double x = 0, y = 0;
        double ctrlX = 0, ctrlY = 0; // control point thứ hai của đoạn cong trước, dùng cho S/s
        char cmd = 0;
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (Character.isLetter(token.charAt(0))) {
                cmd = token.charAt(0);
                if ("MmLlCcSs".indexOf(cmd) < 0) throw new IllegalArgumentException("Unsupported path command " + cmd);
                i++;
                continue;
            }
            switch (cmd) {
                case 'M', 'm' -> {
                    boolean relative = cmd == 'm' && !points.isEmpty();
                    x = (relative ? x : 0) + num(tokens, i);
                    y = (relative ? y : 0) + num(tokens, i + 1);
                    points.add(new double[]{x, y});
                    ctrlX = x;
                    ctrlY = y;
                    i += 2;
                    cmd = cmd == 'M' ? 'L' : 'l'; // cặp số thừa sau moveto là lineto ngầm định
                }
                case 'L', 'l' -> {
                    boolean relative = cmd == 'l';
                    x = (relative ? x : 0) + num(tokens, i);
                    y = (relative ? y : 0) + num(tokens, i + 1);
                    points.add(new double[]{x, y});
                    ctrlX = x;
                    ctrlY = y;
                    i += 2;
                }
                case 'C', 'c', 'S', 's' -> {
                    boolean relative = Character.isLowerCase(cmd);
                    boolean smooth = Character.toUpperCase(cmd) == 'S';
                    double ox = relative ? x : 0, oy = relative ? y : 0;
                    int a = i;
                    double x1, y1;
                    if (smooth) {
                        x1 = 2 * x - ctrlX;
                        y1 = 2 * y - ctrlY;
                    } else {
                        x1 = ox + num(tokens, a);
                        y1 = oy + num(tokens, a + 1);
                        a += 2;
                    }
                    double x2 = ox + num(tokens, a), y2 = oy + num(tokens, a + 1);
                    double x3 = ox + num(tokens, a + 2), y3 = oy + num(tokens, a + 3);
                    for (int s = 1; s <= CURVE_STEPS; s++) {
                        double t = (double) s / CURVE_STEPS, u = 1 - t;
                        points.add(new double[]{
                                u * u * u * x + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3,
                                u * u * u * y + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3});
                    }
                    ctrlX = x2;
                    ctrlY = y2;
                    x = x3;
                    y = y3;
                    i = a + 4;
                }
                default -> throw new IllegalArgumentException("Unsupported path data: " + d);
            }
        }
        return points;
    }

    private static double num(List<String> tokens, int index) {
        if (index >= tokens.size() || Character.isLetter(tokens.get(index).charAt(0))) {
            throw new IllegalArgumentException("Truncated path data");
        }
        return Double.parseDouble(tokens.get(index));
    }
}
