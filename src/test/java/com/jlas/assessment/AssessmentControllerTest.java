package com.jlas.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jlas.assessment.controller.AssessmentController;
import com.jlas.assessment.dto.TargetOptionResponse;
import com.jlas.assessment.service.AssessmentService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AssessmentControllerTest {

    @Mock AssessmentService service;

    @Test
    void targetOptionsExposeTheServerOwnedRecommendation() {
        var option = new TargetOptionResponse("N3", false, "N4", List.of(),
                "TARGET_TOO_FAR", true);
        when(service.getTargetOptions("N5")).thenReturn(List.of(option));

        var response = new AssessmentController(service).targetOptions("N5");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).containsExactly(option);
        verify(service).getTargetOptions("N5");
    }
}
