package com.jlas.learning;

import com.jlas.auth.security.UserPrincipal;
import com.jlas.learning.controller.InteractiveLearningPathController;
import com.jlas.learning.dto.InteractiveLearningPathResponse;
import com.jlas.learning.service.InteractiveLearningPathService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InteractiveLearningPathControllerTest {
    @Test void currentUsesAuthenticatedPrincipalIdentityAndSharedEnvelope() {
        var service = mock(InteractiveLearningPathService.class);
        var response = new InteractiveLearningPathResponse(1L, "Path", "JLPT", "N5", "N4", 30,
                0, 0, 0, null, List.of());
        when(service.current(7L)).thenReturn(response);
        var controller = new InteractiveLearningPathController(service);

        var body = controller.current(new UserPrincipal(7L, "learner", "", List.of())).getBody();

        assertNotNull(body);
        assertTrue(body.isSuccess());
        assertSame(response, body.getData());
        verify(service).current(7L);
    }
}
