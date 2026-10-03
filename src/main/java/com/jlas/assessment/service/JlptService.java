package com.jlas.assessment.service;

import com.jlas.assessment.dto.JlptExamResponse;
import com.jlas.assessment.dto.JlptExamSummaryResponse;
import com.jlas.assessment.dto.JlptPracticeQuestionResponse;
import com.jlas.assessment.dto.JlptPracticeTypeResponse;
import java.util.List;

public interface JlptService {
    List<JlptExamSummaryResponse> listExams(String level);
    JlptExamResponse getExam(String code);
    List<JlptPracticeTypeResponse> listPracticeTypes(String level);
    List<JlptPracticeQuestionResponse> practiceQuestions(String level, String section, String typeCode, int limit);
}
