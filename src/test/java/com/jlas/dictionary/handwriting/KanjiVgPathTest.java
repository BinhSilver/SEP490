package com.jlas.dictionary.handwriting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KanjiVgPathTest {

    @Test
    void parsesAbsoluteMoveAndRelativeCubic() {
        // KanjiVG viết số liền nhau kiểu "6.07-0.29" (không có dấu phẩy trước số âm).
        List<double[]> points = KanjiVgPath.flatten("M54.5,15.79c0,6.07-0.29,55.49-0.29,60.55");

        assertArrayEquals(new double[]{54.5, 15.79}, points.get(0), 1e-9);
        assertArrayEquals(new double[]{54.21, 76.34}, points.get(points.size() - 1), 1e-9);
        assertEquals(9, points.size(), "1 điểm đầu + 8 điểm trên đường cong");
    }

    @Test
    void smoothCurveReflectsPreviousControlPoint() {
        // c kết thúc tại (10,0) với control thứ hai (5,5) => control đầu của s phải là (15,-5).
        List<double[]> withSmooth = KanjiVgPath.flatten("M0,0c0,0,5,5,10,0s5,5,10,0");
        List<double[]> explicit = KanjiVgPath.flatten("M0,0c0,0,5,5,10,0c5,-5,5,5,10,0");

        assertEquals(explicit.size(), withSmooth.size());
        for (int i = 0; i < explicit.size(); i++) {
            assertArrayEquals(explicit.get(i), withSmooth.get(i), 1e-9);
        }
    }

    @Test
    void leadingLowercaseMoveIsAbsolute() {
        assertArrayEquals(new double[]{3, 4}, KanjiVgPath.flatten("m3,4c1,1,2,2,3,3").get(0), 1e-9);
    }

    @Test
    void rejectsUnsupportedOrTruncatedPathData() {
        assertThrows(IllegalArgumentException.class, () -> KanjiVgPath.flatten("M0,0Z"));
        assertThrows(IllegalArgumentException.class, () -> KanjiVgPath.flatten("M0,0c1,2,3"));
    }

    @Test
    void readsStrokeIdsOfVariantFilesToo() {
        // File biến thể như 065e5-Kaisho.svg dùng id "kvg:065e5-Kaisho-s1"; index chấp nhận tên file này.
        String svg = "<g id=\"kvg:StrokePaths_065e5-Kaisho\">"
                + "<path id=\"kvg:065e5-Kaisho-s1\" kvg:type=\"㇑\" d=\"M10,10c0,5,0,20,0,30\"/>"
                + "<path id=\"kvg:065e5-Kaisho-s2\" d=\"M10,10c10,0,20,0,30,0\"/></g>";

        List<List<double[]>> strokes = KanjiVgPath.strokesOf(svg);

        assertEquals(2, strokes.size());
        assertArrayEquals(new double[]{10, 10}, strokes.get(1).get(0), 1e-9);
    }

    @Test
    void readsStrokesInWritingOrderFromRealKanjiVgFile() {
        List<List<double[]>> strokes = TestDrawings.canonical('日');

        assertEquals(4, strokes.size());
        assertTrue(strokes.stream().allMatch(s -> s.size() >= 2));
        assertArrayEquals(new double[]{31.5, 24.5}, strokes.get(0).get(0), 1e-9); // nét 1: nét dọc bên trái
        assertArrayEquals(new double[]{34.23, 86.5}, strokes.get(3).get(0), 1e-9); // nét 4: nét ngang dưới cùng
    }
}
