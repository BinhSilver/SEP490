package com.jlas.learning.service;

import com.jlas.learning.dto.InteractiveCompositionResult;
import com.jlas.learning.dto.ScheduledLearningResource;
import com.jlas.learning.entity.UserLearningPath;
import java.util.List;

public interface InteractivePackageComposer {
    InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule);

    /**
     * F1-3 (owner 2026-10-27): {@code availableDays} is how many study days remain before the learner's exam (null: no
     * deadline). A JLPT path that does not fit them requires only the CORE words of each compiled chapter.
     */
    default InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule,
            Integer availableDays) {
        return compose(path, schedule);
    }

    /** F1-4 (owner 2026-10-28): {@code known} words/Kanji are left out of the required part ("Đã vững"). */
    default InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule,
            Integer availableDays, com.jlas.learning.dto.KnownLearningUnits known) {
        return compose(path, schedule, availableDays);
    }

    /** F1-5 (owner 2026-10-28): {@code focusSkills} (VOCABULARY, READING, LISTENING) are never trimmed by an exam date. */
    default InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule,
            Integer availableDays, com.jlas.learning.dto.KnownLearningUnits known, List<String> focusSkills) {
        return compose(path, schedule, availableDays, known);
    }
}
