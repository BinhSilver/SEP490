package com.jlas.flashcard.service;

import com.jlas.flashcard.dto.FlashcardDtos.*;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface FlashcardService {
    PageResponse<SetSummaryDto> publicSets(String title, String sort, int page, int size, Long userId);
    PageResponse<SetSummaryDto> mySets(String title, String sort, int page, int size, Long folderId, Long userId);
    PageResponse<SetSummaryDto> favoriteSets(String title, String sort, int page, int size, Long userId);
    /** Tab Lịch sử: bộ người dùng đã mở và vẫn xem được, lần mở gần nhất trước. */
    PageResponse<SetSummaryDto> historySets(String title, int page, int size, Long userId);
    void removeHistory(Long setId, Long userId);
    void clearHistory(Long userId);
    SetDetailDto set(Long id, Long userId); SetDetailDto create(SetRequest request, Long userId);
    SetDetailDto update(Long id, SetRequest request, Long userId); void delete(Long id, Long userId);
    SetDetailDto createDraft(DraftRequest request, Long userId); SetDetailDto updateDraft(Long id, DraftRequest request, Long userId);
    List<FolderDto> folders(Long userId); FolderDto createFolder(FolderRequest request, Long userId);
    FolderDto updateFolder(Long id, FolderRequest request, Long userId); void deleteFolder(Long id, Long userId);
    SetDetailDto moveToFolder(Long setId, MoveFolderRequest request, Long userId);
    SetDetailDto cloneSet(Long id, Long userId); SetDetailDto importText(ImportRequest request, Long userId);
    ExportFileDto export(Long id, String format, boolean includeHanViet, Long userId); ImportFileResponse importFile(MultipartFile file, Long userId);
    SyncPreviewResponse syncPreview(Long id, Long userId); SyncResponse sync(Long id, Long userId);
    List<EnrichResponse> generateImportCards(String text);
    ImportHanVietResponse enrichImport(String text); EnrichResponse enrich(String term);
    List<EnrichResponse> enrichBatch(List<String> terms); CardDto enrichCard(Long cardId, Long userId);
    List<CardDto> enrichCards(BatchHanVietRequest request, Long userId);
    StudySessionDto study(Long setId, String mode, Long userId); StudySummaryDto review(ReviewRequest request, Long userId);
    StudySummaryDto summary(Long setId, Long userId); StudySummaryDto complete(Long setId, Long userId);
    StudySummaryDto complete(Long setId, String mode, Long userId); StudySummaryDto reset(Long setId, Long userId);
    Long startQuiz(StartQuizRequest request, Long userId); QuizQuestionDto nextQuiz(Long sessionId, Long userId);
    AnswerResultDto answer(AnswerRequest request, Long userId); QuizSummaryDto finishQuiz(Long sessionId, Long userId);
    PageResponse<CommentDto> comments(Long setId, int page, int size, Long userId);
    CommentDto comment(Long setId, CommentRequest request, Long userId); void deleteComment(Long commentId, Long userId);
    FavoriteResponse favorite(Long setId, Long userId); SetSummaryDto rate(Long setId, SetRatingRequest request, Long userId); UploadResponse upload(MultipartFile file, Long userId);
    CardDto updateProgress(Long cardId, ProgressRequest request, Long userId);
}
