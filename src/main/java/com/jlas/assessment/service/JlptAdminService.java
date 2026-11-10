package com.jlas.assessment.service;

import com.jlas.assessment.dto.JlptAdminExamDetailResponse;
import com.jlas.assessment.dto.JlptAdminExamRequest;
import com.jlas.assessment.dto.JlptAdminExamSummaryResponse;
import com.jlas.assessment.dto.JlptAdminQuestionRequest;
import java.util.List;

public interface JlptAdminService {
    List<JlptAdminExamSummaryResponse> listExams();
    JlptAdminExamDetailResponse getExam(String code);
    JlptAdminExamSummaryResponse createExam(JlptAdminExamRequest request);
    JlptAdminExamSummaryResponse updateExam(String code, JlptAdminExamRequest request);
    long createQuestion(String examCode, JlptAdminQuestionRequest request);
    void updateQuestion(long questionId, JlptAdminQuestionRequest request);
    void updateQuestionAudio(long questionId, String audioUrl);
    void deleteQuestion(long questionId);
    long duplicateQuestion(long questionId);
}
