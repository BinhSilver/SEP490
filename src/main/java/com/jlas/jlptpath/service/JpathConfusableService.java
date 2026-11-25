package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathConfusablePairView;
import java.util.List;

/** [JLAS-W0108] Confusable pairs the learner can practise (page "Phân biệt chữ dễ nhầm"). */
public interface JpathConfusableService {

    /**
     * Pairs of {@code tab} (VOCAB or KANJI, any case) whose two sides are visible and learned (first learned, or
     * KNOWN), most wrong check answers first, then code A; another tab → VALIDATION_FAILED.
     */
    List<JpathConfusablePairView> pairs(Long userId, String tab);

    /**
     * {@code preview} true: every visible pair of {@code tab}, learned or not, ordered by level then code A
     * (page "Xem trước"); false: same as {@link #pairs(Long, String)}.
     */
    List<JpathConfusablePairView> pairs(Long userId, String tab, boolean preview);
}
