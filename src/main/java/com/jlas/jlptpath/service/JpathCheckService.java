package com.jlas.jlptpath.service;

import com.jlas.jlptpath.dto.JpathAnswerRequest;
import com.jlas.jlptpath.dto.JpathAnswerResult;
import com.jlas.jlptpath.dto.JpathCheckIssueRequest;
import com.jlas.jlptpath.dto.JpathCheckResult;
import com.jlas.jlptpath.dto.JpathCheckSubmitRequest;
import com.jlas.jlptpath.dto.JpathCheckView;
import com.jlas.jlptpath.dto.JpathPairPickRequest;
import com.jlas.jlptpath.dto.JpathPairPickResult;
import java.nio.file.Path;

/**
 * [JLAS-W0108] Checks of the JLPT path (micro-check, group exercise, kana row): issue, answer and submit with
 * server-side scoring (policy #17, #18, #21). Every call loads the check by (token, userId) and the session by
 * (id, userId) — another learner's token or session reads like an unknown one (JPATH_CHECK_NOT_FOUND /
 * JPATH_SESSION_NOT_FOUND) and nothing is written (plan decision 43). Answers never leave the server before their
 * question is locked.
 */
public interface JpathCheckService {

    /**
     * Issues the check of the session's current step (kind and tab come from the step), or returns the one already
     * issued for it (same token). After a failed submit the step gets a new attempt (attempt_no + 1, new token and
     * seed).
     */
    JpathCheckView issue(Long userId, JpathCheckIssueRequest request);

    JpathCheckView get(Long userId, String token);

    /** One pick on a pair board; a wrong pick costs one unit of the board. */
    JpathPairPickResult pick(Long userId, String token, JpathPairPickRequest request);

    /** Locks one question; answering it again returns the first verdict. */
    JpathAnswerResult answer(Long userId, String token, JpathAnswerRequest request);

    /** Scores and applies the check once; a second submit returns the stored result. */
    JpathCheckResult submit(Long userId, String token, JpathCheckSubmitRequest request);

    /** The audio file of the AUDIO_MCQ question with {@code slot}, resolved under the content audio store. */
    Path audio(Long userId, String token, int slot);
}
