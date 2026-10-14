package com.jlas.flashcard.controller;

import com.jlas.auth.dto.ApiResponse;
import com.jlas.auth.security.UserPrincipal;
import com.jlas.flashcard.dto.FlashcardDtos.*;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.moderation.service.ContentModerationService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Legacy UI-compatible Flashcard API over canonical SEP490 tables. */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class FlashcardController {
    private final FlashcardService flashcards;
    private final ContentModerationService moderation;
    @GetMapping("/flashcards/public") public ResponseEntity<ApiResponse<PageResponse<SetSummaryDto>>> publicSets(@RequestParam(required=false) String title,@RequestParam(defaultValue="NEWEST") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.publicSets(title,sort,page,size,id(user)));}
    @GetMapping("/flashcards/my") public ResponseEntity<ApiResponse<PageResponse<SetSummaryDto>>> mine(@RequestParam(required=false) String title,@RequestParam(required=false) Long folderId,@RequestParam(defaultValue="NEWEST") String sort,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="12") int size,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.mySets(title,sort,page,size,folderId,id(user)));}
    @GetMapping("/flashcards/{setId}") public ResponseEntity<ApiResponse<SetDetailDto>> set(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.set(setId,id(user)));}
    @PostMapping("/flashcards") public ResponseEntity<ApiResponse<SetDetailDto>> create(@Valid @RequestBody SetRequest request,@AuthenticationPrincipal UserPrincipal user){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Created",flashcards.create(request,id(user))));}
    @PutMapping("/flashcards/{setId}") public ResponseEntity<ApiResponse<SetDetailDto>> update(@PathVariable Long setId,@Valid @RequestBody SetRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Updated",flashcards.update(setId,request,id(user)));}
    @PostMapping("/flashcards/drafts") public ResponseEntity<ApiResponse<SetDetailDto>> createDraft(@RequestBody DraftRequest request,@AuthenticationPrincipal UserPrincipal user){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Draft saved",flashcards.createDraft(request,id(user))));}
    @PutMapping("/flashcards/{setId}/draft") public ResponseEntity<ApiResponse<SetDetailDto>> updateDraft(@PathVariable Long setId,@RequestBody DraftRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Draft saved",flashcards.updateDraft(setId,request,id(user)));}
    @GetMapping("/flashcards/folders") public ResponseEntity<ApiResponse<List<FolderDto>>> folders(@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.folders(id(user)));}
    @PostMapping("/flashcards/folders") public ResponseEntity<ApiResponse<FolderDto>> createFolder(@Valid @RequestBody FolderRequest request,@AuthenticationPrincipal UserPrincipal user){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Folder created",flashcards.createFolder(request,id(user))));}
    @PutMapping("/flashcards/folders/{folderId}") public ResponseEntity<ApiResponse<FolderDto>> updateFolder(@PathVariable Long folderId,@Valid @RequestBody FolderRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Folder updated",flashcards.updateFolder(folderId,request,id(user)));}
    @DeleteMapping("/flashcards/folders/{folderId}") public ResponseEntity<ApiResponse<Void>> deleteFolder(@PathVariable Long folderId,@AuthenticationPrincipal UserPrincipal user){flashcards.deleteFolder(folderId,id(user));return ResponseEntity.ok(ApiResponse.ok("Folder deleted"));}
    @PutMapping("/flashcards/{setId}/folder") public ResponseEntity<ApiResponse<SetDetailDto>> moveToFolder(@PathVariable Long setId,@RequestBody MoveFolderRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Moved",flashcards.moveToFolder(setId,request,id(user)));}
    @DeleteMapping("/flashcards/{setId}") public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){flashcards.delete(setId,id(user));return ResponseEntity.ok(ApiResponse.ok("Deleted"));}
    @PostMapping("/flashcards/{setId}/clone") public ResponseEntity<ApiResponse<SetDetailDto>> clone(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Cloned",flashcards.cloneSet(setId,id(user))));}
    @GetMapping("/flashcards/{setId}/sync-preview") public ResponseEntity<ApiResponse<SyncPreviewResponse>> syncPreview(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.syncPreview(setId,id(user)));}
    @PostMapping("/flashcards/{setId}/sync") public ResponseEntity<ApiResponse<SyncResponse>> sync(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("Synced",flashcards.sync(setId,id(user)));}
    @PostMapping("/flashcards/import") public ResponseEntity<ApiResponse<SetDetailDto>> importText(@Valid @RequestBody ImportRequest request,@AuthenticationPrincipal UserPrincipal user){return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Imported",flashcards.importText(request,id(user))));}
    /** Downloads a readable set as CSV, TXT (tab-separated) or XLSX; all three can be imported back. */
    @GetMapping("/flashcards/{setId}/export") public ResponseEntity<byte[]> export(@PathVariable Long setId,@RequestParam(defaultValue="xlsx") String format,@RequestParam(defaultValue="true") boolean hanViet,@AuthenticationPrincipal UserPrincipal user){ExportFileDto file=flashcards.export(setId,format,hanViet,id(user));return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.getFilename(),StandardCharsets.UTF_8).build().toString()).contentType(MediaType.parseMediaType(file.getContentType())).body(file.getContent());}
    /** Converts an uploaded CSV/TXT/XLSX file into editor import text for review before applying. */
    @PostMapping(value="/flashcards/import/file",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<ApiResponse<ImportFileResponse>> importFile(@RequestParam("file") MultipartFile file,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.importFile(file,id(user)));}
    @PostMapping("/flashcards/import/generate-cards") public ResponseEntity<ApiResponse<List<EnrichResponse>>> generateImportCards(@Valid @RequestBody ImportCardsRequest request){return ok("Generated",flashcards.generateImportCards(request.getText()));}
    @PostMapping("/flashcards/import/generate-hanviet") public ResponseEntity<ApiResponse<ImportHanVietResponse>> importEnrich(@RequestBody Map<String,String> body){return ok("OK",flashcards.enrichImport(body.get("text")));}
    @PostMapping("/flashcards/cards/enrich") public ResponseEntity<ApiResponse<EnrichResponse>> enrich(@Valid @RequestBody EnrichRequest request){return ok("Generated",flashcards.enrich(request.getKanji()));}
    @PostMapping("/flashcards/cards/enrich-batch") public ResponseEntity<ApiResponse<List<EnrichResponse>>> enrichBatch(@RequestBody EnrichBatchRequest request){return ok("Generated",flashcards.enrichBatch(request.getKanjis()));}
    @PostMapping("/flashcards/cards/{cardId}/generate-hanviet") public ResponseEntity<ApiResponse<CardDto>> enrichSaved(@PathVariable Long cardId,@AuthenticationPrincipal UserPrincipal user){return ok("Generated",flashcards.enrichCard(cardId,id(user)));}
    @PostMapping("/flashcards/cards/generate-hanviet") public ResponseEntity<ApiResponse<List<CardDto>>> enrichSavedBatch(@RequestBody BatchHanVietRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Generated",flashcards.enrichCards(request,id(user)));}
    /** Denylist pre-check used by the editor before a public set is saved. */
    @PostMapping("/v1/flashcards/validate-realtime") public ResponseEntity<ApiResponse<RealtimeValidationResponse>> validateRealtime(@RequestBody RealtimeValidationRequest request){
        List<RealtimeViolation> violations=new ArrayList<>();
        addViolations(violations,"title",null,request.title());
        addViolations(violations,"description",null,request.description());
        if(request.cards()!=null) for(int i=0;i<request.cards().size();i++){
            RealtimeCard card=request.cards().get(i);
            if(card!=null){addViolations(violations,"front",i,card.front());addViolations(violations,"back",i,card.back());}
        }
        return ok("OK",new RealtimeValidationResponse(violations.isEmpty(),violations));
    }
    @GetMapping("/study/{setId}") public ResponseEntity<ApiResponse<StudySessionDto>> study(@PathVariable Long setId,@RequestParam(defaultValue="ALL") String mode,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.study(setId,mode,id(user)));}
    @PostMapping("/study/review") public ResponseEntity<ApiResponse<StudySummaryDto>> review(@RequestBody ReviewRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.review(request,id(user)));}
    @GetMapping("/study/{setId}/summary") public ResponseEntity<ApiResponse<StudySummaryDto>> summary(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.summary(setId,id(user)));}
    @PostMapping("/study/{setId}/complete") public ResponseEntity<ApiResponse<StudySummaryDto>> complete(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("Completed",flashcards.complete(setId,id(user)));}
    @PostMapping("/study/{setId}/reset") public ResponseEntity<ApiResponse<StudySummaryDto>> reset(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("Reset",flashcards.reset(setId,id(user)));}
    @PostMapping("/quiz/from-flashcard/{setId}") public ResponseEntity<ApiResponse<Map<String,Long>>> quizFromFlashcard(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){SetDetailDto set=flashcards.set(setId,id(user));return ok("OK",Map.of("vocabSetId",setId,"cardCount",(long)set.getCards().size()));}
    @GetMapping("/quiz/runtime-set/{setId}") public ResponseEntity<ApiResponse<SetDetailDto>> runtimeSet(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.set(setId,id(user)));}
    @PostMapping("/quiz/start") public ResponseEntity<ApiResponse<Map<String,Long>>> startQuiz(@RequestBody StartQuizRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Started",Map.of("id",flashcards.startQuiz(request,id(user))));}
    @GetMapping("/quiz/session/{id}/next") public ResponseEntity<?> nextQuiz(@PathVariable Long id,@AuthenticationPrincipal UserPrincipal user){QuizQuestionDto question=flashcards.nextQuiz(id,this.id(user));return question==null?ResponseEntity.noContent().build():ok("OK",question);}
    @PostMapping("/quiz/answer") public ResponseEntity<ApiResponse<AnswerResultDto>> answer(@RequestBody AnswerRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.answer(request,id(user)));}
    @PostMapping("/quiz/session/{id}/finish") public ResponseEntity<ApiResponse<QuizSummaryDto>> finish(@PathVariable Long id,@AuthenticationPrincipal UserPrincipal user){return ok("Finished",flashcards.finishQuiz(id,this.id(user)));}
    @GetMapping("/flashcards/{setId}/comments") public ResponseEntity<ApiResponse<PageResponse<CommentDto>>> comments(@PathVariable Long setId,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.comments(setId,page,size,id(user)));}
    @PostMapping("/flashcards/{setId}/comments") public ResponseEntity<ApiResponse<CommentDto>> comment(@PathVariable Long setId,@Valid @RequestBody CommentRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Created",flashcards.comment(setId,request,id(user)));}
    @DeleteMapping("/flashcards/comments/{id}") public ResponseEntity<ApiResponse<Void>> deleteComment(@PathVariable Long id,@AuthenticationPrincipal UserPrincipal user){flashcards.deleteComment(id,this.id(user));return ResponseEntity.ok(ApiResponse.ok("Deleted"));}
    @PostMapping("/flashcards/{setId}/favorite") public ResponseEntity<ApiResponse<FavoriteResponse>> favorite(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return ok("OK",flashcards.favorite(setId,id(user)));}
    @PostMapping("/flashcards/{setId}/rating") public ResponseEntity<ApiResponse<SetSummaryDto>> rate(@PathVariable Long setId,@RequestBody SetRatingRequest request,@AuthenticationPrincipal UserPrincipal user){return ok("Đã lưu đánh giá",flashcards.rate(setId,request,id(user)));}
    @PostMapping(value="/uploads/image",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<ApiResponse<UploadResponse>> upload(@RequestParam("file") MultipartFile file,@AuthenticationPrincipal UserPrincipal user){return ok("Uploaded",flashcards.upload(file,id(user)));}
    // Compatibility routes used by the copied flashcard study and quiz pages.
    @GetMapping("/vocab/sets") public List<SetSummaryDto> vocabSets(@AuthenticationPrincipal UserPrincipal user){return flashcards.mySets(null,"NEWEST",0,50,null,id(user)).getContent();}
    @GetMapping("/vocab/sets/{setId}") public SetDetailDto vocabSet(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return flashcards.set(setId,id(user));}
    @GetMapping("/vocab/sets/{setId}/filter-meta") public Map<String,List<String>> filterMeta(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){flashcards.set(setId,id(user));return Map.of("lessons",List.of());}
    @PostMapping("/vocab/sets/{setId}/reset-memory") public StudySummaryDto resetMemory(@PathVariable Long setId,@AuthenticationPrincipal UserPrincipal user){return flashcards.reset(setId,id(user));}
    @GetMapping("/vocab/sets/{setId}/by-kanji") public List<CardDto> byKanji(@PathVariable Long setId,@RequestParam(defaultValue="") String q,@AuthenticationPrincipal UserPrincipal user){return flashcards.set(setId,id(user)).getCards().stream().filter(card->card.getKanji()!=null&&card.getKanji().contains(q)).toList();}
    @GetMapping("/vocab/sets/{setId}/by-hanviet") public List<CardDto> byHanViet(@PathVariable Long setId,@RequestParam(defaultValue="") String q,@AuthenticationPrincipal UserPrincipal user){return flashcards.set(setId,id(user)).getCards().stream().filter(card->card.getHanViet()!=null&&card.getHanViet().toLowerCase().contains(q.toLowerCase())).toList();}
    @PatchMapping("/vocab/cards/{cardId}/progress") public CardDto progress(@PathVariable Long cardId,@RequestBody ProgressRequest request,@AuthenticationPrincipal UserPrincipal user){return flashcards.updateProgress(cardId,request,id(user));}
    private void addViolations(List<RealtimeViolation> violations,String field,Integer cardIndex,String value){for(ContentModerationService.DenylistMatch match:moderation.findDenylistMatches(value))violations.add(new RealtimeViolation(field,cardIndex,match.term(),match.start(),match.end()));}
    private Long id(UserPrincipal user){return user==null?null:user.userId();} private <T> ResponseEntity<ApiResponse<T>> ok(String message,T body){return ResponseEntity.ok(ApiResponse.ok(message,body));}
    public record RealtimeValidationRequest(String title,String description,List<RealtimeCard> cards){}
    public record RealtimeCard(String front,String back){}
    public record RealtimeValidationResponse(boolean valid,List<RealtimeViolation> violations){}
    public record RealtimeViolation(String field,Integer cardIndex,String term,int start,int end){}
}
