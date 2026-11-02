package com.jlas.jlptpath.util;

/**
 * [JLAS-W0108] Stroke-order SVG of a kanji. {@code kanji.stroke_svg_uri} is empty, so the KanjiVG file is found by
 * the code point (five lowercase hex digits, e.g. {@code 04e00.svg}). Whether the file ships is answered by
 * {@link JpathStrokeSvgIndex}.
 */
public final class JpathStrokeSvg {

    private JpathStrokeSvg() {
    }

    public static String url(int codePoint) {
        return String.format("/assets/kanjivg/kanji/%05x.svg", codePoint);
    }
}
