package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathWritingView;
import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;

/** [JLAS-W0108] Stroke-order and tracing practice for path words, kanji and kana. */
public interface JpathWritingService {

    /** Writing view of a visible word, kanji or kana; grammar, hidden or unknown items → JPATH_ITEM_NOT_FOUND. */
    JpathWritingView view(Long userId, JpathItemType type, String code);

    /** Views of today's planned new items of {@code tab} that can be written, at most 30. */
    List<JpathWritingView> today(Long userId, JpathTab tab);
}
