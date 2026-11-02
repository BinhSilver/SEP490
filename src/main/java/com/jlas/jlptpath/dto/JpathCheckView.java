package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;

/**
 * [JLAS-W0108] An issued check as the learner sees it: public questions (no answers, no item codes) and the
 * questions already locked. {@code status} is ISSUED, SUBMITTED or EXPIRED.
 */
public record JpathCheckView(String token, JpathCheckKind kind, JpathTab tab, int attemptNo, boolean timerOn,
                             String status, JpathCheckIssueRequest issue, List<JpathQuestionView> questions,
                             List<JpathAnsweredView> answered) {
}
