package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathPrintRequest;
import com.jlas.jlptpath.dto.JpathPrintSheet;

/**
 * [JLAS-W0108] A4 writing sheets of the JLPT path: today's new items, the list as filtered (at most
 * {@link #MAX_ITEMS}) or a kana table. A bad scope, tab, script, level or radical → {@code VALIDATION_FAILED}.
 */
public interface JpathPrintService {

    int MAX_ITEMS = 120;

    JpathPrintSheet sheet(Long userId, JpathPrintRequest request);
}
