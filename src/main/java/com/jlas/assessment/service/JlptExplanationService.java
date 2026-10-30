package com.jlas.assessment.service;

import com.jlas.assessment.dto.JlptExplanationResponse;
import com.jlas.assessment.dto.JlptExplanationRequest;

public interface JlptExplanationService {
    JlptExplanationResponse explain(JlptExplanationRequest request);
}
