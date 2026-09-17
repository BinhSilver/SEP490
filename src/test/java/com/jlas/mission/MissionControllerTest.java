package com.jlas.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.mission.controller.MissionController;
import com.jlas.mission.dto.DailyGoalProgress;
import com.jlas.mission.dto.MissionSummaryResponse;
import com.jlas.mission.service.MissionService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MissionControllerTest {

    @Test
    void summaryWrapsMissionState() {
        MissionSummaryResponse summary = new MissionSummaryResponse(40, 3, 7,
                new DailyGoalProgress(10, 15, 1, 1, 20, 20, true));
        MissionService service = userId -> {
            assertEquals(1L, userId);
            return summary;
        };
        var controller = new MissionController(service);

        var response = controller.summary(new UserPrincipal(1L, "learner@test.local", null, List.of()));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        ApiResponse<MissionSummaryResponse> body = response.getBody();
        assertTrue(body.isSuccess());
        assertEquals(summary, body.getData());
    }
}
