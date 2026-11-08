package com.jlas.jlptpath.dto;

import com.jlas.jlptpath.enums.JpathCheckKind;
import com.jlas.jlptpath.enums.JpathTab;
import java.util.List;

/**
 * [JLAS-W0108] An issued check as the learner sees it: public questions (no answers, no item codes) and the
 * questions already locked. {@code status} is ISSUED, SUBMITTED or EXPIRED; SKIPPED (no token, no questions) when
 * the session step had nothing to ask and the server moved on — {@code next} then says where to go (null otherwise).
 * {@code skill}: the sets of a skill check (Plan 3), null for the other kinds.
 */
public record JpathCheckView(String token, JpathCheckKind kind, JpathTab tab, int attemptNo, boolean timerOn,
                             String status, JpathCheckIssueRequest issue, List<JpathQuestionView> questions,
                             List<JpathAnsweredView> answered, JpathCheckNext next, JpathSkillCheckView skill) {

    public JpathCheckView(String token, JpathCheckKind kind, JpathTab tab, int attemptNo, boolean timerOn,
                          String status, JpathCheckIssueRequest issue, List<JpathQuestionView> questions,
                          List<JpathAnsweredView> answered, JpathCheckNext next) {
        this(token, kind, tab, attemptNo, timerOn, status, issue, questions, answered, next, null);
    }
}
