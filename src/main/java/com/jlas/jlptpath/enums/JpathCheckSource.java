package com.jlas.jlptpath.enums;

/** [JLAS-W0108] Items a free quiz draws from: learned items, the notebook, or every visible item. */
public enum JpathCheckSource {
    LEARNED, NOTEBOOK, ALL,
    /** Owner 05/11: the weekly test — items first learned in the last 7 days. */
    WEEK
}
