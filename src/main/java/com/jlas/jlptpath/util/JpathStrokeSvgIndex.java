package com.jlas.jlptpath.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] The one check whether a KanjiVG stroke-order file ships for a code point (preflight P8;
 * {@link JpathStrokeSvg} only builds the URL). Files are looked up on the classpath under {@code root} as
 * {@code <5 lowercase hex>.svg}; answers are cached. The KanjiVG set is not in Git (restored from
 * {@code data/dictionary-seed/kanjivg-*.zip} into {@code static/assets/kanjivg/}); when it is missing every check is
 * false and the writing practice just hides the stroke-order tab.
 */
@Component
public class JpathStrokeSvgIndex {

    public static final String DEFAULT_ROOT = "static/assets/kanjivg/kanji/";

    private final String root;
    private final Map<Integer, Boolean> cache = new ConcurrentHashMap<>();

    @Autowired
    public JpathStrokeSvgIndex() {
        this(DEFAULT_ROOT);
    }

    /** {@code root} is a classpath folder ending with '/', e.g. {@code "static/assets/kanjivg/kanji/"}. */
    public JpathStrokeSvgIndex(String root) {
        this.root = root.endsWith("/") ? root : root + "/";
    }

    public boolean exists(int codePoint) {
        return cache.computeIfAbsent(codePoint, cp -> {
            ClassLoader loader = JpathStrokeSvgIndex.class.getClassLoader();
            return loader.getResource(root + String.format("%05x.svg", cp)) != null;
        });
    }
}
