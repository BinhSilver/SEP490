package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.dto.TagAssessmentResult;
import com.jlas.learning.mapper.LearningMapper;
import com.jlas.learning.repository.LessonProgressRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningPathRepository;
import com.jlas.learning.service.LearningProfileSource;
import com.jlas.learning.service.LearningResourceSelectionService;
import com.jlas.learning.service.LearningScheduleService;
import com.jlas.learning.service.LearningScopeSource;
import com.jlas.learning.service.impl.LearningServiceImpl;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Verifies the optional profile/scope ports degrade to 503 instead of failing to start. */
class LearningSourceAvailabilityTest {

    @Test
    void createReturnsServiceUnavailableWhenNoProfileSourceIsConfigured() {
        var factory = new StaticListableBeanFactory();
        var users = mock(UserRepository.class);
        when(users.findForCreditUpdate(7L)).thenReturn(Optional.of(new User()));
        var service = service(factory.getBeanProvider(LearningProfileSource.class),
                factory.getBeanProvider(LearningScopeSource.class), users);

        var exception = assertThrows(ApiException.class, () -> service.create(7L, 91L));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    @Test
    void createReturnsServiceUnavailableWhenNoScopeSourceIsConfigured() {
        var factory = new StaticListableBeanFactory();
        var profileSource = mock(LearningProfileSource.class);
        factory.addBean("profileSource", profileSource);
        var users = mock(UserRepository.class);
        when(users.findForCreditUpdate(7L)).thenReturn(Optional.of(new User()));
        when(profileSource.loadForUser(7L, 91L)).thenReturn(new LearningProfileSnapshot(7L, 91L, 10L, "rev-1",
                true, true, "N4", "N4", "JLPT", 30, List.of(new TagAssessmentResult("PARTICLE", 5, 4))));
        var service = service(factory.getBeanProvider(LearningProfileSource.class),
                factory.getBeanProvider(LearningScopeSource.class), users);

        var exception = assertThrows(ApiException.class, () -> service.create(7L, 91L));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
    }

    private LearningServiceImpl service(ObjectProvider<LearningProfileSource> profiles,
            ObjectProvider<LearningScopeSource> scopes, UserRepository users) {
        return new LearningServiceImpl(users,
                mock(UserLearningPathRepository.class),
                mock(UserLearningPathItemRepository.class),
                mock(LessonProgressRepository.class),
                mock(LearningResourceSelectionService.class),
                mock(LearningScheduleService.class),
                mock(LearningMapper.class),
                profiles, scopes, mock(EntityManager.class));
    }
}
