package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathItemType;
import com.jlas.jlptpath.enums.JpathRating;
import com.jlas.jlptpath.enums.JpathStudyAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * [JLAS-W0108] One button press on a study card. {@code clientEventId} is made by the page (uuid) and makes the event
 * idempotent; {@code position} is the queue index of the card the learner saw; {@code typed} is the retyped reading
 * (kana or romaji); {@code rating} is ignored for grammar.
 */
public record JpathStudyEventInput(@NotBlank @Size(max = 40) String clientEventId, JpathItemType itemType,
                                   String itemCode, JpathStudyAction action, JpathRating rating, String typed,
                                   Long elapsedMs, int position) {
}
