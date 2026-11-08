package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathExtraLinksView;

/** [JLAS-W0108] The "Luyện thêm" strip of the JLPT path page and the done screen. */
public interface JpathExtraLinksService {

    /** Links for the learner's open level (no ACTIVE path → level null, mock link not open). */
    JpathExtraLinksView links(Long userId);
}
