package com.jlas.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jlas.auth.entity.User;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.controller.LearningCertificatePageController;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.repository.UserLearningPathRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

class LearningCertificatePageControllerTest {

    private final UserLearningPathRepository paths = mock(UserLearningPathRepository.class);
    private final LearningCertificatePageController controller =
            new LearningCertificatePageController(paths);

    private UserPrincipal principalFor(Long userId) {
        return new UserPrincipal(userId, "u@b.com", "hash", java.util.List.of());
    }

    @Test
    void rendersForTheOwnerOfACompletedPath() {
        var owner = User.builder().id(1L).build();
        var path = UserLearningPath.builder().id(5L).user(owner).status("COMPLETED")
                .targetLevel("N4").completedAt(LocalDateTime.now()).build();
        when(paths.findById(5L)).thenReturn(Optional.of(path));
        Model model = new ExtendedModelMap();

        String view = controller.certificate(5L, principalFor(1L), model);

        assertEquals("learning/certificate", view);
        assertEquals("N4", model.getAttribute("targetLevel"));
    }

    @Test
    void rejectsAnotherUsersPath() {
        var owner = User.builder().id(1L).build();
        var path = UserLearningPath.builder().id(5L).user(owner).status("COMPLETED")
                .targetLevel("N4").completedAt(LocalDateTime.now()).build();
        when(paths.findById(5L)).thenReturn(Optional.of(path));
        Model model = new ExtendedModelMap();

        var ex = assertThrows(ApiException.class,
                () -> controller.certificate(5L, principalFor(2L), model));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void rejectsAnIncompletePath() {
        var owner = User.builder().id(1L).build();
        var path = UserLearningPath.builder().id(5L).user(owner).status("ACTIVE").targetLevel("N4").build();
        when(paths.findById(5L)).thenReturn(Optional.of(path));
        Model model = new ExtendedModelMap();

        var ex = assertThrows(ApiException.class,
                () -> controller.certificate(5L, principalFor(1L), model));

        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }
}
