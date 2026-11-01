package com.jlas.jlptpath.util;

/**
 * [JLAS-W0108] Stroke-order SVG of a kanji. {@code kanji.stroke_svg_uri} is empty, so the KanjiVG file is found by
 * the code point (five lowercase hex digits, e.g. {@code 04e00.svg}).
 */
public final class JpathStrokeSvg {

    private static final String STATIC_ROOT = "/static";

    private JpathStrokeSvg() {
    }

    public static String url(int codePoint) {
        return String.format("/assets/kanjivg/kanji/%05x.svg", codePoint);
    }

    /** Whether the KanjiVG file for this code point ships with the app's static assets. */
    public static boolean exists(int codePoint) {
        return JpathStrokeSvg.class.getResource(STATIC_ROOT + url(codePoint)) != null;
    }
}
