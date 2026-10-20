package com.jlas.learning.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LearningDailyGoalPlanAdapterTest {
    @Mock UserLearningPathRepository paths;
    @Mock LearningPathPackageRepository packages;
    @Mock LearningActivityMemberRepository members;

    @Test void derivesTargetsFromFirstActionablePackage() {
        var adapter = new LearningDailyGoalPlanAdapter(paths, packages, members);
        var path = UserLearningPath.builder().id(8L).user(User.builder().id(7L).build())
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).status("ACTIVE").build();
        var locked = LearningPathPackage.builder().id(10L).learningPath(path).status(LearningPackageStatus.LOCKED)
                .estimatedMinutes(40).orderIndex(2).build();
        var available = LearningPathPackage.builder().id(9L).learningPath(path).status(LearningPackageStatus.AVAILABLE)
                .estimatedMinutes(25).orderIndex(1).build();
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByLearningPathIdOrderByOrderIndex(8L)).thenReturn(List.of(available, locked));
        when(members.countByActivityLearningPackageIdAndMemberType(9L, LearningMemberType.VOCABULARY_ENTRY))
                .thenReturn(12L);

        var result = adapter.resolve(7L).orElseThrow();
        assertEquals(12, result.vocabularyTarget());
        assertEquals(1, result.quizTarget());
        assertEquals(25, result.minutesTarget());
        assertEquals("ACTIVE_PATH", result.source());
    }

    @Test void reportsTransparentEmptyWhenNoActionablePackage() {
        var adapter = new LearningDailyGoalPlanAdapter(paths, packages, members);
        var path = UserLearningPath.builder().id(8L).build();
        when(paths.findByUserIdAndStatusAndExperienceVersion(7L, "ACTIVE", LearningExperienceVersion.INTERACTIVE_V2))
                .thenReturn(Optional.of(path));
        when(packages.findByLearningPathIdOrderByOrderIndex(8L)).thenReturn(List.of());
        assertTrue(adapter.resolve(7L).isEmpty());
    }
}
