package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.TagAssessmentResult;
import com.jlas.learning.service.LearningProfileSource;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local demo adapter (profile {@code local-seed}): fabricates a finalized profile from the
 * authenticated user so the flow can run before the survey/Smart Test modules are delivered.
 * Never active in production.
 */
@Component
@Profile("local-seed")
@RequiredArgsConstructor
public class LocalLearningProfileSource implements LearningProfileSource {
    private static final List<String> LEVELS = List.of("N5", "N4", "N3", "N2", "N1");
    private final UserRepository users;

    @Override
    public LearningProfileSnapshot loadForUser(Long userId, Long assessmentAttemptId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found."));
        String assessed = LEVELS.contains(user.getJapaneseLevel()) ? user.getJapaneseLevel() : "N4";
        return new LearningProfileSnapshot(userId, assessmentAttemptId, 1L, "local-demo-v1",
                true, true, assessed, targetFor(assessed), "JLPT", 30,
                List.of(new TagAssessmentResult("PARTICLE", 5, 4), new TagAssessmentResult("VERB", 5, 2)));
    }

    private static String targetFor(String assessed) {
        int index = LEVELS.indexOf(assessed);
        return index + 1 < LEVELS.size() ? LEVELS.get(index + 1) : LEVELS.get(index);
    }
}
