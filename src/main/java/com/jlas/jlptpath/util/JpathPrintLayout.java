package com.jlas.jlptpath.util;

import com.jlas.jlptpath.dto.JpathPrintCells;
import java.util.ArrayList;
import java.util.List;

/**
 * [JLAS-W0108] Layout of the A4 writing sheet: each row is 10 squares; a word of n characters fills n squares per copy
 * ({@code min(n, 10)}), so a row holds {@code max(1, 10 / units)} copies, 40% of them (rounded up) with the faded guide.
 * A page holds {@link #ROWS_PER_PAGE} rows.
 */
public final class JpathPrintLayout {

    public static final int SQUARES = 10;
    public static final int ROWS_PER_PAGE = 6;
    private static final double FADED_SHARE = 0.4;

    private JpathPrintLayout() {
    }

    /** Copies of a row for a text of {@code chars} characters; {@code chars < 1} → IllegalArgumentException. */
    public static JpathPrintCells cells(int chars) {
        if (chars < 1) {
            throw new IllegalArgumentException("chars must be >= 1: " + chars);
        }
        int units = Math.min(chars, SQUARES);
        int count = Math.max(1, SQUARES / units);
        int faded = (int) Math.ceil(count * FADED_SHARE);
        return new JpathPrintCells(faded, count - faded, units);
    }

    /** {@code rows} cut into pages of {@link #ROWS_PER_PAGE}. */
    public static <T> List<List<T>> pages(List<T> rows) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i += ROWS_PER_PAGE) {
            out.add(List.copyOf(rows.subList(i, Math.min(rows.size(), i + ROWS_PER_PAGE))));
        }
        return out;
    }
}
