package com.jlas.flashcard.service.impl;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.flashcard.ai.HanVietAiService;
import com.jlas.flashcard.ai.HanVietAiService.CardEnrichResult;
import com.jlas.flashcard.dto.FlashcardDtos.*;
import com.jlas.flashcard.entity.*;
import com.jlas.flashcard.entity.FlashcardCardProgress.FlashcardCardProgressId;
import com.jlas.flashcard.entity.FlashcardUserSetState.FlashcardUserSetStateId;
import com.jlas.flashcard.repository.*;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.moderation.entity.ContentComment;
import com.jlas.moderation.entity.ContentCommentRating;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.repository.ContentCommentRatingRepository;
import com.jlas.moderation.repository.ContentCommentRepository;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.moderation.service.UserActionPolicyPort;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Canonical-schema port of the legacy Flashcard library, study and quiz flows. */
@Service
@RequiredArgsConstructor
@Transactional
public class FlashcardServiceImpl implements FlashcardService {
    private static final String TARGET = "FLASHCARD_SET";
    private static final Pattern IMPORT = Pattern.compile("^\\s*(.+?)\\s*-\\s*(.+?)\\s*:\\s*(.+?)(?:\\s*\\(([^)]*)\\))?\\s*$");
    private final FlashcardSetRepository sets; private final FlashcardCardRepository cards; private final FlashcardFolderRepository folders;
    private final FlashcardCardProgressRepository progress; private final FlashcardUserSetStateRepository states;
    private final FlashcardStudySessionRepository sessions; private final FlashcardStudySessionItemRepository sessionItems;
    private final UserRepository users; private final HanVietAiService hanVietAiService;
    private final ContentCommentRepository comments; private final ContentCommentRatingRepository ratings;
    private final ContentModerationPort moderation;
    private final UserActionPolicyPort actionPolicy;
    private final UserNotificationPort notifications;
    private final com.jlas.flashcard.service.FlashcardStudyAccess studyAccess;
    @Value("${upload.path.flashcard:uploads/flashcard}") private String uploadPath;

    @Override @Transactional(readOnly = true)
    public PageResponse<SetSummaryDto> publicSets(String title, String sort, int page, int size, Long userId) {
        return page(sets.findPublic(blank(title), pageable(sort, page, size)), userId);
    }
    @Override @Transactional(readOnly = true)
    public PageResponse<SetSummaryDto> mySets(String title, String sort, int page, int size, Long folderId, Long userId) {
        login(userId);
        if (folderId != null && folderId > 0) folder(folderId, userId);
        return page(sets.findMine(userId, blank(title), folderId, pageable(sort, page, size)), userId);
    }
    /** Mở một bộ cũng ghi lần truy cập, để bộ đó hiện ở mục "Xem gần đây" (Game Arena). */
    @Override
    public SetDetailDto set(Long id, Long userId) { FlashcardSet set = readable(id, userId); accessed(userId, set); return detail(set, userId); }
    @Override public SetDetailDto create(SetRequest request, Long userId) {
        actionPolicy.assertCanAct(login(userId));
        validateComplete(request, null);
        FlashcardSet set = sets.save(FlashcardSet.builder().owner(user(userId)).title(required(request.getTitle(), "Tên bộ thẻ"))
                .description(blank(request.getDescription())).thumbnailUrl(blank(request.getThumbnail())).visibility(visibility(request.getVisibility())).draft(false).build());
        replaceCards(set, request.getCards()); moderateSetIfPublic(set, userId); return detail(set, userId);
    }
    @Override public SetDetailDto update(Long id, SetRequest request, Long userId) {
        actionPolicy.assertCanAct(login(userId));
        FlashcardSet set = owner(id, userId); validateComplete(request, set); set.setTitle(required(request.getTitle(), "Tên bộ thẻ"));
        set.setDescription(blank(request.getDescription())); set.setThumbnailUrl(blank(request.getThumbnail())); set.setVisibility(visibility(request.getVisibility()));
        set.setDraft(false); sets.save(set); if (request.getCards() != null) replaceCards(set, request.getCards()); moderateSetIfPublic(set, userId); return detail(set, userId);
    }
    @Override public void delete(Long id, Long userId) { FlashcardSet set = owner(id, userId); set.setVisibility("PRIVATE"); set.setDeletedAt(LocalDateTime.now()); sets.save(set); }
    @Override public SetDetailDto createDraft(DraftRequest request, Long userId) {
        DraftRequest value = request == null ? new DraftRequest() : request;
        validateDraft(value);
        FlashcardSet set = FlashcardSet.builder().owner(user(userId)).title(draftTitle(value.getTitle()))
                .description(blank(value.getDescription())).thumbnailUrl(blank(value.getThumbnail()))
                .visibility(visibility(value.getVisibility())).folder(folderOrNull(value.getFolderId(), userId)).draft(true).build();
        sets.save(set);
        if (value.getCards() != null) replaceDraftCards(set, value.getCards());
        return detail(set, userId);
    }
    @Override public SetDetailDto updateDraft(Long id, DraftRequest request, Long userId) {
        DraftRequest value = request == null ? new DraftRequest() : request;
        validateDraft(value);
        FlashcardSet set = owner(id, userId);
        set.setTitle(draftTitle(value.getTitle()));
        set.setDescription(blank(value.getDescription()));
        set.setThumbnailUrl(blank(value.getThumbnail()));
        if (value.getVisibility() != null) set.setVisibility(visibility(value.getVisibility()));
        if (value.getFolderId() != null) set.setFolder(folderOrNull(value.getFolderId(), userId));
        set.setDraft(true);
        sets.save(set);
        if (value.getCards() != null) replaceDraftCards(set, value.getCards());
        return detail(set, userId);
    }
    @Override @Transactional(readOnly = true) public List<FolderDto> folders(Long userId) {
        login(userId);
        return folders.findByOwnerIdOrderByNameAsc(userId).stream().map(this::folderDto).toList();
    }
    @Override public FolderDto createFolder(FolderRequest request, Long userId) {
        String name = required(request == null ? null : request.getName(), "Tên thư mục");
        if (folders.existsByOwnerIdAndNameIgnoreCase(login(userId), name)) throw bad("Tên thư mục đã tồn tại");
        return folderDto(folders.save(FlashcardFolder.builder().owner(user(userId)).name(name).build()));
    }
    @Override public FolderDto updateFolder(Long id, FolderRequest request, Long userId) {
        FlashcardFolder value = folder(id, userId);
        String name = required(request == null ? null : request.getName(), "Tên thư mục");
        if (!value.getName().equalsIgnoreCase(name) && folders.existsByOwnerIdAndNameIgnoreCase(userId, name)) throw bad("Tên thư mục đã tồn tại");
        value.setName(name);
        return folderDto(folders.save(value));
    }
    @Override public void deleteFolder(Long id, Long userId) { folders.delete(folder(id, userId)); }
    @Override public SetDetailDto moveToFolder(Long setId, MoveFolderRequest request, Long userId) {
        FlashcardSet set = owner(setId, userId);
        set.setFolder(folderOrNull(request == null ? null : request.getFolderId(), userId));
        return detail(sets.save(set), userId);
    }
    @Override public SetDetailDto cloneSet(Long id, Long userId) {
        FlashcardSet source = readable(id, userId); FlashcardSet copy = sets.save(FlashcardSet.builder().owner(user(userId)).title(source.getTitle()+" (copy)")
                .description(source.getDescription()).thumbnailUrl(source.getThumbnailUrl()).visibility("PRIVATE").sourceSet(source).clonedAt(LocalDateTime.now()).build());
        List<CardInput> inputs = cards.findBySetIdOrderByPositionAscIdAsc(source.getId()).stream().map(this::input).toList(); replaceCards(copy, inputs); return detail(copy, userId);
    }
    @Override @Transactional(readOnly = true) public SyncPreviewResponse syncPreview(Long id, Long userId) {
        FlashcardSet copy=owner(id,userId); FlashcardSet source=copy.getSourceSet(); if(source==null) throw bad("Bộ thẻ này không phải bản sao");
        return SyncPreviewResponse.builder().newCardCount(missingSourceCards(copy,source).size()).originalTitle(source.getTitle()).build();
    }
    @Override public SyncResponse sync(Long id, Long userId) {
        FlashcardSet copy=owner(id,userId); FlashcardSet source=copy.getSourceSet(); if(source==null) throw bad("Bộ thẻ này không phải bản sao");
        List<FlashcardCard> additions=missingSourceCards(copy,source); int position=cards.findBySetIdOrderByPositionAscIdAsc(copy.getId()).size();
        for(FlashcardCard card:additions){CardInput input=input(card); input.setPosition(position++); addCard(copy,input);}
        copy.setLastSyncedAt(LocalDateTime.now()); sets.save(copy); return SyncResponse.builder().addedCount(additions.size()).build();
    }
    @Override public SetDetailDto importText(ImportRequest request, Long userId) {
        List<CardInput> inputs = parse(request.getText()).stream().filter(ParsedLineDto::isValid).map(row -> {
            CardInput input = new CardInput(); input.setFront(row.getFront()); input.setReading(row.getReading()); input.setMeaning(row.getBack()); input.setBack(back(row.getReading(),row.getBack())); input.setHanViet(row.getHanViet()); return input;
        }).toList();
        if (inputs.isEmpty()) throw bad("Không có dòng hợp lệ. Dùng: Kanji - Hiragana : Nghĩa (Hán Việt)");
        return create(new SetRequest(request.getTitle(),request.getDescription(),null,request.getVisibility(),inputs),userId);
    }
    @Override @Transactional(readOnly = true)
    public ImportHanVietResponse enrichImport(String text) {
        String value = hanVietAiService.enrichImportText(text);
        return ImportHanVietResponse.builder().text(value).lines(parse(value)).build();
    }
    @Override @Transactional(readOnly = true)
    public EnrichResponse enrich(String term) {
        CardEnrichResult result = hanVietAiService.enrichFromKanji(required(term, "Kanji"));
        return EnrichResponse.builder().front(result.kanji()).reading(result.reading()).meaning(result.meaning())
                .back(result.back()).hanViet(result.hanViet()).build();
    }
    @Override @Transactional(readOnly = true)
    public List<EnrichResponse> enrichBatch(List<String> terms) {
        if (terms == null || terms.isEmpty()) return List.of();
        return hanVietAiService.enrichBatchFromKanji(terms).stream()
                .map(result -> EnrichResponse.builder().front(result.kanji()).reading(result.reading())
                        .meaning(result.meaning()).back(result.back()).hanViet(result.hanViet()).build())
                .toList();
    }
    @Override public CardDto enrichCard(Long cardId, Long userId) {
        FlashcardCard card=cards.findById(cardId).orElseThrow(()->missing("Không tìm thấy thẻ"));
        owner(card.getSet().getId(),userId);
        CardEnrichResult data=hanVietAiService.enrichFromKanji(required(card.getFront(), "Kanji"));
        if(StringUtils.hasText(data.reading())) card.setReading(data.reading());
        if(StringUtils.hasText(data.meaning())) card.setMeaning(data.meaning());
        if(StringUtils.hasText(data.back())) card.setBack(data.back());
        if(StringUtils.hasText(data.hanViet())) card.setHanViet(data.hanViet());
        return dto(cards.save(card),null);
    }
    @Override public List<CardDto> enrichCards(BatchHanVietRequest request, Long userId) {
        if(request==null||request.getCardIds()==null||request.getCardIds().isEmpty()) return List.of();
        List<FlashcardCard> selected=cards.findAllById(request.getCardIds());
        for(FlashcardCard card:selected) owner(card.getSet().getId(),userId);
        List<CardDto> result=new ArrayList<>();
        for(FlashcardCard card:selected){
            if(!StringUtils.hasText(card.getFront())){result.add(dto(card,null));continue;}
            CardEnrichResult data=hanVietAiService.enrichFromKanji(card.getFront().trim());
            if(StringUtils.hasText(data.back())&&(!StringUtils.hasText(card.getBack())||card.getBack().trim().equals(card.getFront().trim()))) card.setBack(data.back());
            if(StringUtils.hasText(data.reading())) card.setReading(data.reading());
            if(StringUtils.hasText(data.meaning())) card.setMeaning(data.meaning());
            if(StringUtils.hasText(data.hanViet())) card.setHanViet(data.hanViet());
            result.add(dto(cards.save(card),null));
        }
        return result;
    }
    @Override @Transactional(readOnly = true)
    public StudySessionDto study(Long setId,String mode,Long userId) {
        FlashcardSet set=studyable(setId,userId); Map<Long,FlashcardCardProgress> map=progressMap(userId,setId); String selected=mode==null?"ALL":mode.toUpperCase(Locale.ROOT); List<FlashcardCard> all=cards.findBySetIdOrderByPositionAscIdAsc(setId);
        List<CardDto> selectedCards=all.stream().filter(card->!"LEARNING".equals(selected)||!"MASTERED".equals(Optional.ofNullable(map.get(card.getId())).map(FlashcardCardProgress::getStatus).orElse("NEW"))).map(card->dto(card,map.get(card.getId()))).toList();
        return StudySessionDto.builder().setId(setId).title(set.getTitle()).mode(selected).cards(selectedCards).total(all.size()).learning((int)map.values().stream().filter(p->"LEARNING".equals(p.getStatus())).count()).mastered((int)map.values().stream().filter(p->"MASTERED".equals(p.getStatus())).count()).build();
    }
    @Override public StudySummaryDto review(ReviewRequest request,Long userId) {
        if(request==null||request.getSetId()==null||request.getCardId()==null) throw bad("Cần setId và cardId"); studyable(request.getSetId(),userId); FlashcardCard card=cards.findById(request.getCardId()).orElseThrow(()->missing("Không tìm thấy thẻ")); if(!card.getSet().getId().equals(request.getSetId())) throw bad("Thẻ không thuộc bộ này"); FlashcardCardProgress p=stateProgress(userId,card); apply(p,request.getQuality()==null?3:request.getQuality()); progress.save(p); accessed(userId,card.getSet()); return summary(request.getSetId(),userId);
    }
    @Override @Transactional(readOnly = true)
    public StudySummaryDto summary(Long setId,Long userId) {
        studyable(setId,userId); login(userId); List<FlashcardCard> all=cards.findBySetIdOrderByPositionAscIdAsc(setId); Map<Long,FlashcardCardProgress> map=progressMap(userId,setId); List<CardDto> learning=all.stream().filter(card->"LEARNING".equals(Optional.ofNullable(map.get(card.getId())).map(FlashcardCardProgress::getStatus).orElse("NEW"))).map(card->dto(card,map.get(card.getId()))).toList();
        return StudySummaryDto.builder().total(all.size()).remembered((int)map.values().stream().filter(p->"MASTERED".equals(p.getStatus())).count()).notRemembered(learning.size()).starred((int)map.values().stream().filter(p->Boolean.TRUE.equals(p.getIsStarred())).count()).notRememberedCards(learning).build();
    }
    @Override public StudySummaryDto complete(Long setId,Long userId) { FlashcardSet set=studyable(setId,userId); sessions.save(FlashcardStudySession.builder().user(user(userId)).set(set).sessionType("FLASHCARD").mode("ALL").status("COMPLETED").finishedAt(LocalDateTime.now()).build()); FlashcardUserSetState state=state(userId,set); if(state.getFirstCompletedAt()==null)state.setFirstCompletedAt(LocalDateTime.now()); state.setLastAccessedAt(LocalDateTime.now()); states.save(state); return summary(setId,userId); }
    @Override public StudySummaryDto reset(Long setId,Long userId) { studyable(setId,userId); login(userId); for(FlashcardCardProgress p:progress.findByUserIdAndCardSetId(userId,setId)){p.setStatus("NEW");p.setReviewCount(0);p.setRepetitionCount(0);p.setIntervalDays(0);p.setLastReviewedAt(null);p.setNextReviewAt(LocalDateTime.now());} progress.flush(); return summary(setId,userId); }
    @Override public Long startQuiz(StartQuizRequest request,Long userId) {
        if(request==null||request.getSetId()==null)throw bad("Cần setId"); FlashcardSet set=studyable(request.getSetId(),userId); String questionField=quizField(request.getQuestionField(),"KANJI"); String answerField=quizField(request.getAnswerField(),"MEANING"); if(questionField.equals(answerField))throw bad("Nội dung đáp án phải khác nội dung câu hỏi"); Set<Long> selected=request.getSelectedCardIds()==null?Set.of():new HashSet<>(request.getSelectedCardIds()); Map<Long,FlashcardCardProgress> map=progressMap(userId,set.getId()); List<FlashcardCard> questions=cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).stream().filter(card->selected.isEmpty()||selected.contains(card.getId())).filter(card->!request.isWrongOnly()||"LEARNING".equals(Optional.ofNullable(map.get(card.getId())).map(FlashcardCardProgress::getStatus).orElse("NEW"))).filter(card->quizText(card,questionField)!=null&&quizText(card,answerField)!=null).toList(); if(questions.isEmpty())throw bad("Không có thẻ nào có đủ nội dung câu hỏi và đáp án đã chọn"); FlashcardStudySession session=sessions.save(FlashcardStudySession.builder().user(user(userId)).set(set).sessionType("QUIZ").mode(QUIZ_MODE_PREFIX+questionField+QUIZ_MODE_SEPARATOR+answerField).status("IN_PROGRESS").build()); int position=0;for(FlashcardCard card:questions)sessionItems.save(FlashcardStudySessionItem.builder().session(session).card(card).position(position++).build());return session.getId();
    }
    @Override @Transactional(readOnly = true)
    public QuizQuestionDto nextQuiz(Long sessionId,Long userId) { FlashcardStudySession session=quiz(sessionId,userId); List<FlashcardStudySessionItem> all=sessionItems.findBySessionIdOrderByPositionAsc(sessionId); FlashcardStudySessionItem item=all.stream().filter(row->row.getIsCorrect()==null).findFirst().orElse(null);if(item==null)return null; String[] fields=quizFields(session.getMode()); String correctText=quizText(item.getCard(),fields[1]); Set<String> usedTexts=new HashSet<>(); usedTexts.add(String.valueOf(correctText).toLowerCase(Locale.ROOT)); List<FlashcardCard> pool=new ArrayList<>(cards.findBySetIdOrderByPositionAscIdAsc(session.getSet().getId()));pool.removeIf(card->card.getId().equals(item.getCard().getId()));Collections.shuffle(pool);List<FlashcardCard> options=new ArrayList<>();for(FlashcardCard card:pool){String text=quizText(card,fields[1]);if(text!=null&&usedTexts.add(text.toLowerCase(Locale.ROOT)))options.add(card);if(options.size()==3)break;}options.add(item.getCard());Collections.shuffle(options);List<OptionDto> result=new ArrayList<>();char label='A';for(FlashcardCard card:options)result.add(OptionDto.builder().cardId(card.getId()).label(String.valueOf(label++)).text(quizText(card,fields[1])).build());return QuizQuestionDto.builder().sessionId(sessionId).cardId(item.getCard().getId()).question(quizText(item.getCard(),fields[0])).reading(item.getCard().getReading()).meaning(item.getCard().getMeaning()).hanViet(item.getCard().getHanViet()).options(result).questionIndex(item.getPosition()+1).totalQuestions(all.size()).build(); }
    @Override public AnswerResultDto answer(AnswerRequest request,Long userId) { if(request==null||request.getSessionId()==null||request.getCardId()==null||request.getChosenCardId()==null)throw bad("Thiếu câu trả lời");FlashcardStudySession session=quiz(request.getSessionId(),userId);FlashcardStudySessionItem item=sessionItems.findBySessionIdAndCardId(session.getId(),request.getCardId()).orElseThrow(()->bad("Câu hỏi không hợp lệ"));if(item.getIsCorrect()!=null)throw bad("Câu hỏi đã trả lời");FlashcardCard chosen=cards.findById(request.getChosenCardId()).orElseThrow(()->bad("Đáp án không hợp lệ"));boolean right=item.getCard().getId().equals(chosen.getId());item.setChosenCard(chosen);item.setIsCorrect(right);item.setAnsweredAt(LocalDateTime.now());sessionItems.save(item);FlashcardCardProgress p=stateProgress(userId,item.getCard());apply(p,right?5:1);progress.save(p);List<FlashcardStudySessionItem> answers=sessionItems.findBySessionIdOrderByPositionAsc(session.getId());return AnswerResultDto.builder().correct(right).correctCardId(item.getCard().getId()).correctText(quizText(item.getCard(),quizFields(session.getMode())[1])).currentCorrect((int)answers.stream().filter(a->Boolean.TRUE.equals(a.getIsCorrect())).count()).currentWrong((int)answers.stream().filter(a->Boolean.FALSE.equals(a.getIsCorrect())).count()).build(); }
    @Override public QuizSummaryDto finishQuiz(Long sessionId,Long userId) { FlashcardStudySession session=quiz(sessionId,userId);List<FlashcardStudySessionItem> all=sessionItems.findBySessionIdOrderByPositionAsc(sessionId);int right=(int)all.stream().filter(a->Boolean.TRUE.equals(a.getIsCorrect())).count(),wrong=(int)all.stream().filter(a->Boolean.FALSE.equals(a.getIsCorrect())).count();session.setStatus("COMPLETED");session.setFinishedAt(LocalDateTime.now());sessions.save(session);int seconds=(int)Duration.between(session.getStartedAt(),session.getFinishedAt()).toSeconds();Map<Long,FlashcardCardProgress> map=progressMap(userId,session.getSet().getId());return QuizSummaryDto.builder().sessionId(sessionId).total(all.size()).correct(right).wrong(wrong).percentage(all.isEmpty()?0:right*100.0/all.size()).durationSeconds(seconds).avgSecondsPerQuestion(all.isEmpty()?0:(double)seconds/all.size()).wrongCards(all.stream().filter(a->Boolean.FALSE.equals(a.getIsCorrect())).map(a->dto(a.getCard(),map.get(a.getCard().getId()))).toList()).build(); }
    @Override @Transactional(readOnly = true)
    public PageResponse<CommentDto> comments(Long setId,int page,int size,Long userId){readable(setId,userId);Page<ContentComment> result=comments.findByTargetTypeAndTargetIdAndIsHiddenFalseOrderByCreatedAtDesc(TARGET,setId,PageRequest.of(Math.max(0,page),Math.min(Math.max(1,size),100)));Map<Long,ContentCommentRating> map=new HashMap<>();ratings.findAllById(result.getContent().stream().map(ContentComment::getId).toList()).forEach(r->map.put(r.getCommentId(),r));return PageResponse.<CommentDto>builder().content(result.getContent().stream().map(c->commentDto(c,map.get(c.getId()),userId)).toList()).page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();}
    @Override
    public CommentDto comment(Long setId, CommentRequest request, Long userId) {
        FlashcardSet set = readable(setId, userId);
        actionPolicy.assertCanAct(login(userId));
        String text = required(request.getContent(), "Nội dung");
        moderation.checkComment(String.valueOf(login(userId)));
        ModerationResult result = moderation.scanText(text);
        if (result.isBlocked()) {
            moderation.persistLog("FLASHCARD_COMMENT", setId, userId, "COMMENT", text, result);
            throw new ApiException(ErrorCode.CONTENT_BLOCKED, result.getSummary());
        }
        boolean pending = result.needsReview();
        ContentComment comment = comments.save(ContentComment.builder().targetType(TARGET).targetId(setId)
                .user(user(userId)).content(text).isHidden(pending).build());
        Long logId = moderation.persistLog("FLASHCARD_COMMENT", comment.getId(), userId,
                "COMMENT", text, result);
        if (pending) {
            moderation.enqueueReview(logId, "FLASHCARD_COMMENT", comment.getId(), userId,
                    "Bình luận bộ thẻ " + set.getTitle(), result.getSummary());
        }
        if (!pending && !set.getOwner().getId().equals(userId)) {
            String commenter = StringUtils.hasText(comment.getUser().getFullName())
                    ? comment.getUser().getFullName() : comment.getUser().getUsername();
            notifications.notifyUser(set.getOwner().getId(), "FLASHCARD_COMMENT", "Bình luận mới về bộ thẻ",
                    commenter + " đã bình luận về bộ thẻ “" + set.getTitle() + "”.",
                    "/flashcard/set.html?id=" + setId, "FLASHCARD_COMMENT_" + comment.getId());
        }
        ContentCommentRating rating = null;
        if (!pending && request.getRating() != null) {
            if (request.getRating() < 1 || request.getRating() > 5) throw bad("Đánh giá từ 1 đến 5");
            rating = ratings.save(ContentCommentRating.builder().commentId(comment.getId())
                    .rating(request.getRating()).build());
        }
        return commentDto(comment, rating, userId);
    }
    @Override public void deleteComment(Long id,Long userId){ContentComment comment=comments.findById(id).orElseThrow(()->missing("Không tìm thấy bình luận"));if(!comment.getUser().getId().equals(login(userId)))throw forbidden("Không có quyền xóa bình luận");ratings.deleteById(id);comments.delete(comment);}
    @Override public FavoriteResponse favorite(Long setId,Long userId){FlashcardUserSetState state=state(userId,readable(setId,userId));state.setIsFavorite(!Boolean.TRUE.equals(state.getIsFavorite()));states.save(state);return FavoriteResponse.builder().favorited(state.getIsFavorite()).build();}
    @Override public SetSummaryDto rate(Long setId, SetRatingRequest request, Long userId){
        FlashcardSet set=readable(setId,userId);Long current=login(userId);if(set.getOwner().getId().equals(current))throw bad("Không thể tự đánh giá bộ thẻ của mình");
        int value=request==null||request.getRating()==null?0:request.getRating();if(value<1||value>5)throw bad("Đánh giá phải từ 1 đến 5 sao");
        FlashcardUserSetState state=state(current,set);state.setRating((byte)value);states.save(state);return summary(set,current);
    }
    @Override public CardDto updateProgress(Long cardId,ProgressRequest request,Long userId){
        FlashcardCard card=cards.findById(cardId).orElseThrow(()->missing("Không tìm thấy thẻ")); readable(card.getSet().getId(),userId); FlashcardCardProgress p=stateProgress(userId,card);
        if(request!=null&&request.getStarred()!=null)p.setIsStarred(request.getStarred());
        if(request!=null&&StringUtils.hasText(request.getMemoryStatus())){String status=request.getMemoryStatus().toUpperCase(Locale.ROOT);if("REMEMBERED".equals(status)){p.setStatus("MASTERED");p.setRepetitionCount(Math.max(3,Optional.ofNullable(p.getRepetitionCount()).orElse(0)));p.setLastQuality((byte)5);}else if("NOT_REMEMBERED".equals(status)){p.setStatus("LEARNING");p.setRepetitionCount(0);p.setLastQuality((byte)1);}else throw bad("memoryStatus không hợp lệ");p.setLastReviewedAt(LocalDateTime.now());p.setNextReviewAt(LocalDateTime.now().plusDays("REMEMBERED".equals(status)?6:1));}
        progress.save(p); accessed(userId,card.getSet()); return dto(card,p);
    }
    @Override public UploadResponse upload(MultipartFile file,Long userId){login(userId);if(file==null||file.isEmpty())throw bad("Cần chọn ảnh");String type=Optional.ofNullable(file.getContentType()).orElse("").toLowerCase(Locale.ROOT);if(!Set.of("image/jpeg","image/png","image/gif","image/webp").contains(type)||file.getSize()>5*1024*1024)throw bad("Ảnh phải là JPEG/PNG/GIF/WEBP và không quá 5MB");String ext=type.equals("image/png")?".png":type.equals("image/gif")?".gif":type.equals("image/webp")?".webp":".jpg",name=UUID.randomUUID()+ext;try{Path directory=Paths.get(uploadPath).toAbsolutePath().normalize();Files.createDirectories(directory);file.transferTo(directory.resolve(name));}catch(IOException exception){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Không thể lưu ảnh",exception);}return UploadResponse.builder().filename(name).url("/uploads/flashcard/"+name).build();}

    private FlashcardSet active(Long id){FlashcardSet set=sets.findById(id).orElseThrow(()->missing("Không tìm thấy bộ thẻ"));if(set.getDeletedAt()!=null)throw missing("Không tìm thấy bộ thẻ");return set;}
    private FlashcardSet readable(Long id,Long userId){return studyAccess.readable(id,userId);}
    private FlashcardSet studyable(Long id,Long userId){return studyAccess.studyable(id,userId);}
    private FlashcardSet owner(Long id,Long userId){FlashcardSet set=active(id);if(set.getOwner().getId().equals(login(userId)))return set;throw forbidden("Chỉ chủ sở hữu được thay đổi");}
    private FlashcardFolder folder(Long id,Long userId){return folders.findByIdAndOwnerId(id,login(userId)).orElseThrow(()->missing("Không tìm thấy thư mục"));}
    private FlashcardFolder folderOrNull(Long id,Long userId){return id==null||id==0?null:folder(id,userId);}
    private FlashcardStudySession quiz(Long id,Long userId){FlashcardStudySession session=sessions.findById(id).orElseThrow(()->missing("Không tìm thấy phiên quiz"));if(!"QUIZ".equals(session.getSessionType())||!session.getUser().getId().equals(login(userId)))throw forbidden("Không có quyền truy cập");return session;}
    private User user(Long id){return users.findById(login(id)).orElseThrow(()->missing("Không tìm thấy người dùng"));} private Long login(Long id){if(id==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Vui lòng đăng nhập");return id;}
    private ResponseStatusException missing(String message){return new ResponseStatusException(HttpStatus.NOT_FOUND,message);}private ResponseStatusException forbidden(String message){return new ResponseStatusException(HttpStatus.FORBIDDEN,message);}private ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
    private String required(String value,String label){if(!StringUtils.hasText(value))throw bad(label+" không được để trống");return value.trim();}private String blank(String value){return StringUtils.hasText(value)?value.trim():null;}private String visibility(String value){return "PUBLIC".equalsIgnoreCase(value)?"PUBLIC":"PRIVATE";}private Pageable pageable(String sort,int page,int size){return PageRequest.of(Math.max(0,page),Math.min(Math.max(1,size),50),Sort.by(Sort.Direction.DESC,"RECENTLY_UPDATED".equalsIgnoreCase(sort)?"updatedAt":"createdAt"));}
    private String draftTitle(String value){String title=blank(value);return title==null?"":title;}
    private void validateDraft(DraftRequest request){
        length(request.getTitle(),100,"Tên bộ thẻ"); length(request.getDescription(),500,"Mô tả"); length(request.getThumbnail(),500,"Ảnh đại diện");
        if(request.getCards()==null)return;int row=1;for(CardInput input:request.getCards()){if(input!=null){length(input.getFront(),500,"Kanji thẻ "+row);length(input.getBack(),500,"Mặt sau thẻ "+row);length(input.getReading(),255,"Cách đọc thẻ "+row);length(input.getMeaning(),500,"Nghĩa thẻ "+row);length(input.getHanViet(),255,"Hán Việt thẻ "+row);length(input.getImage(),500,"Ảnh thẻ "+row);}row++;}
    }
    private void length(String value,int max,String label){if(value!=null&&value.length()>max)throw bad(label+" không được vượt quá "+max+" ký tự");}
    private void validateComplete(SetRequest request,FlashcardSet current){
        if(request==null)throw bad("Thiếu dữ liệu bộ thẻ");required(request.getTitle(),"Tên bộ thẻ");
        if(request.getCards()!=null){if(request.getCards().isEmpty())throw bad("Bộ thẻ phải có ít nhất 1 thẻ");int row=1;for(CardInput input:request.getCards()){validateCompleteCard(input,row++);}return;}
        if(current==null)throw bad("Bộ thẻ phải có ít nhất 1 thẻ");List<FlashcardCard> existing=cards.findBySetIdOrderByPositionAscIdAsc(current.getId());if(existing.isEmpty())throw bad("Bộ thẻ phải có ít nhất 1 thẻ");int row=1;for(FlashcardCard card:existing){if(!StringUtils.hasText(card.getFront())||!StringUtils.hasText(card.getReading())||!StringUtils.hasText(card.getMeaning()))throw bad("Thẻ "+row+" còn thiếu Kanji, cách đọc hoặc nghĩa");row++;}
    }
    private void validateCompleteCard(CardInput input,int row){if(input==null||!StringUtils.hasText(input.getFront())||!StringUtils.hasText(input.getReading())||!StringUtils.hasText(input.getMeaning()))throw bad("Thẻ "+row+" còn thiếu Kanji, cách đọc hoặc nghĩa");}
    private void moderateSetIfPublic(FlashcardSet set, Long userId) {
        if (!"PUBLIC".equals(set.getVisibility())) return;
        moderation.checkPublish(String.valueOf(login(userId)));
        StringBuilder content = new StringBuilder(set.getTitle());
        if (StringUtils.hasText(set.getDescription())) content.append('\n').append(set.getDescription());
        cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).forEach(card -> {
            content.append('\n').append(Optional.ofNullable(card.getFront()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getReading()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getMeaning()).orElse(""));
        });
        ModerationResult result = moderation.scanText(content.toString());
        if (result.isBlocked()) {
            throw new ApiException(ErrorCode.CONTENT_BLOCKED, result.getSummary());
        }
        set.setModerationStatus(result.getVerdict().name());
        set.setModerationSummary(result.getSummary());
        set.setModerationCheckedAt(LocalDateTime.now());
        if (result.needsReview()) set.setVisibility("PRIVATE");
        sets.save(set);
        Long logId = moderation.persistLog("FLASHCARD_SET", set.getId(), userId,
                "PUBLISH", content.toString(), result);
        if (result.needsReview()) {
            moderation.enqueueReview(logId, "FLASHCARD_SET", set.getId(), userId,
                    set.getTitle(), result.getSummary());
        }
    }
    private PageResponse<SetSummaryDto> page(Page<FlashcardSet> source,Long userId){return PageResponse.<SetSummaryDto>builder().content(source.getContent().stream().map(set->summary(set,userId)).toList()).page(source.getNumber()).size(source.getSize()).totalElements(source.getTotalElements()).totalPages(source.getTotalPages()).first(source.isFirst()).last(source.isLast()).build();}
    private SetSummaryDto summary(FlashcardSet set,Long userId){List<ContentComment> commentList=comments.findByTargetIdOrderByCreatedAtDesc(set.getId()).stream().filter(c->TARGET.equals(c.getTargetType())&&!Boolean.TRUE.equals(c.getIsHidden())).toList();List<FlashcardUserSetState> ratingStates=states.findBySetIdAndRatingIsNotNull(set.getId());Double average=ratingStates.isEmpty()?null:ratingStates.stream().mapToInt(row->row.getRating()).average().orElse(0);FlashcardUserSetState mine=userId==null?null:states.findByUserIdAndSetId(userId,set.getId()).orElse(null);boolean favorite=mine!=null&&Boolean.TRUE.equals(mine.getIsFavorite());String name=StringUtils.hasText(set.getOwner().getFullName())?set.getOwner().getFullName():set.getOwner().getUsername();long cardCount=cards.countBySetId(set.getId());return SetSummaryDto.builder().id(set.getId()).title(set.getTitle()).name(set.getTitle()).description(set.getDescription()).thumbnail(set.getThumbnailUrl()).visibility(set.getVisibility()).moderationStatus(set.getModerationStatus()).ownerId(set.getOwner().getId()).ownerName(name).folderId(set.getFolder()==null?null:set.getFolder().getId()).folderName(set.getFolder()==null?null:set.getFolder().getName()).draft(set.isDraft()).totalCards(cardCount).cardCount(cardCount).studyCount(sessions.countBySetIdAndStatus(set.getId(),"COMPLETED")).avgRating(average).ratingCount(ratingStates.size()).commentCount(commentList.size()).ownedByMe(userId!=null&&userId.equals(set.getOwner().getId())).favorited(favorite).myRating(mine==null||mine.getRating()==null?null:(int)mine.getRating()).createdAt(set.getCreatedAt()).updatedAt(set.getUpdatedAt()).build();}
    private SetDetailDto detail(FlashcardSet set,Long userId){SetSummaryDto base=summary(set,userId);SetDetailDto dto=new SetDetailDto();dto.setId(base.getId());dto.setTitle(base.getTitle());dto.setName(base.getName());dto.setDescription(base.getDescription());dto.setThumbnail(base.getThumbnail());dto.setVisibility(base.getVisibility());dto.setModerationStatus(base.getModerationStatus());dto.setOwnerId(base.getOwnerId());dto.setOwnerName(base.getOwnerName());dto.setFolderId(base.getFolderId());dto.setFolderName(base.getFolderName());dto.setDraft(base.isDraft());dto.setTotalCards(base.getTotalCards());dto.setCardCount(base.getCardCount());dto.setStudyCount(base.getStudyCount());dto.setAvgRating(base.getAvgRating());dto.setRatingCount(base.getRatingCount());dto.setCommentCount(base.getCommentCount());dto.setOwnedByMe(base.isOwnedByMe());dto.setFavorited(base.isFavorited());dto.setMyRating(base.getMyRating());dto.setCreatedAt(base.getCreatedAt());dto.setUpdatedAt(base.getUpdatedAt());Map<Long,FlashcardCardProgress> map=progressMap(userId,set.getId());dto.setCards(cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).stream().map(card->dto(card,map.get(card.getId()))).toList());return dto;}
    private FolderDto folderDto(FlashcardFolder folder){return FolderDto.builder().id(folder.getId()).name(folder.getName()).setCount(sets.countByFolderIdAndDeletedAtIsNull(folder.getId())).build();}
    private CardDto dto(FlashcardCard card,FlashcardCardProgress state){return CardDto.builder().id(card.getId()).front(card.getFront()).back(card.getBack()).kanji(card.getFront()).hiragana(card.getReading()).reading(card.getReading()).meaning(card.getMeaning()).hanViet(card.getHanViet()).image(card.getImageUrl()).jlptLevel(card.getJlptLevel()).wordType(card.getWordType()).exampleJa(card.getExampleJa()).exampleVi(card.getExampleVi()).position(Optional.ofNullable(card.getPosition()).orElse(0)).starred(state!=null&&Boolean.TRUE.equals(state.getIsStarred())).memoryStatus(memoryStatus(state)).build();}
    private String memoryStatus(FlashcardCardProgress state){if(state==null)return "NEW";return "MASTERED".equals(state.getStatus())?"REMEMBERED":"LEARNING".equals(state.getStatus())?"NOT_REMEMBERED":"NEW";}
    private CardInput input(FlashcardCard card){CardInput input=new CardInput();input.setId(card.getId());input.setFront(card.getFront());input.setBack(card.getBack());input.setReading(card.getReading());input.setMeaning(card.getMeaning());input.setHanViet(card.getHanViet());input.setImage(card.getImageUrl());input.setJlptLevel(card.getJlptLevel());input.setWordType(card.getWordType());input.setExampleJa(card.getExampleJa());input.setExampleVi(card.getExampleVi());input.setPosition(card.getPosition());return input;}
    private void replaceCards(FlashcardSet set,List<CardInput> inputs){reconcileCards(set,inputs,false);}
    private void replaceDraftCards(FlashcardSet set,List<CardInput> inputs){reconcileCards(set,inputs,true);}
    private void reconcileCards(FlashcardSet set,List<CardInput> inputs,boolean allowIncomplete){
        List<FlashcardCard> existing=cards.findBySetIdOrderByPositionAscIdAsc(set.getId());Map<Long,FlashcardCard> byId=new HashMap<>();for(int i=0;i<existing.size();i++){FlashcardCard card=existing.get(i);byId.put(card.getId(),card);card.setPosition(1_000_000+i);}cards.saveAll(existing);cards.flush();
        int position=0;if(inputs!=null)for(CardInput input:inputs){if(input==null)continue;FlashcardCard card=input.getId()==null?null:byId.remove(input.getId());if(card==null)card=FlashcardCard.builder().set(set).build();applyCard(card,input,position++,allowIncomplete);cards.save(card);}if(!byId.isEmpty())cards.deleteAll(byId.values());
    }
    private void applyCard(FlashcardCard card,CardInput input,int position,boolean allowIncomplete){String front=blank(input.getFront()),reading=blank(input.getReading()),meaning=blank(input.getMeaning()),value=blank(input.getBack());if(value==null)value=back(reading,meaning);if(!allowIncomplete&&value==null)value=front;card.setFront(front);card.setBack(value);card.setReading(reading);card.setMeaning(meaning);card.setHanViet(blank(input.getHanViet()));card.setImageUrl(blank(input.getImage()));card.setJlptLevel(blank(input.getJlptLevel()));card.setWordType(blank(input.getWordType()));card.setExampleJa(blank(input.getExampleJa()));card.setExampleVi(blank(input.getExampleVi()));card.setPosition(position);}
    private void addCard(FlashcardSet set,CardInput input){FlashcardCard card=FlashcardCard.builder().set(set).build();applyCard(card,input,input.getPosition()==null?0:input.getPosition(),false);cards.save(card);}
    private List<FlashcardCard> missingSourceCards(FlashcardSet copy,FlashcardSet source){Set<String> existing=cards.findBySetIdOrderByPositionAscIdAsc(copy.getId()).stream().map(this::cardKey).collect(java.util.stream.Collectors.toSet());return cards.findBySetIdOrderByPositionAscIdAsc(source.getId()).stream().filter(card->!existing.contains(cardKey(card))).toList();}
    private String cardKey(FlashcardCard card){return card.getFront()+"\u0000"+Optional.ofNullable(card.getReading()).orElse("")+"\u0000"+card.getBack();}
    private String back(String reading,String meaning){if(!StringUtils.hasText(reading))return meaning;if(!StringUtils.hasText(meaning))return reading;return reading+" — "+meaning;}private String display(FlashcardCard card){return StringUtils.hasText(card.getMeaning())?card.getMeaning():card.getBack();}
    /** Quiz trắc nghiệm hỏi/đáp theo 2 trong 4 trường của thẻ; lưu ở cột mode dạng "MC:KANJI>MEANING". */
    private static final String QUIZ_MODE_PREFIX = "MC:";
    private static final String QUIZ_MODE_SEPARATOR = ">";
    private static final Set<String> QUIZ_FIELDS = Set.of("KANJI", "HIRAGANA", "MEANING", "HAN_VIET");
    private String quizField(String value, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        String field = value.trim().toUpperCase(Locale.ROOT);
        if (!QUIZ_FIELDS.contains(field)) throw bad("Nội dung câu hỏi hoặc đáp án không hợp lệ");
        return field;
    }
    /** Phiên quiz cũ (mode MULTIPLE_CHOICE) giữ cách cũ: hỏi Kanji, đáp nghĩa. */
    private static String[] quizFields(String mode) {
        if (mode != null && mode.startsWith(QUIZ_MODE_PREFIX) && mode.contains(QUIZ_MODE_SEPARATOR)) {
            String[] parts = mode.substring(QUIZ_MODE_PREFIX.length()).split(QUIZ_MODE_SEPARATOR, 2);
            if (QUIZ_FIELDS.contains(parts[0]) && QUIZ_FIELDS.contains(parts[1])) return parts;
        }
        return new String[]{"KANJI", "MEANING"};
    }
    private String quizText(FlashcardCard card, String field) {
        String value = switch (field) {
            case "HIRAGANA" -> StringUtils.hasText(card.getReading()) ? card.getReading()
                    : card.getBack() != null && card.getBack().contains(" — ") ? card.getBack().substring(0, card.getBack().indexOf(" — ")) : null;
            case "MEANING" -> display(card);
            case "HAN_VIET" -> card.getHanViet();
            default -> card.getFront();
        };
        return StringUtils.hasText(value) ? value.trim() : null;
    }
    private Map<Long,FlashcardCardProgress> progressMap(Long userId,Long setId){if(userId==null)return Map.of();Map<Long,FlashcardCardProgress> result=new HashMap<>();progress.findByUserIdAndCardSetId(userId,setId).forEach(p->result.put(p.getCard().getId(),p));return result;}
    private FlashcardCardProgress stateProgress(Long userId,FlashcardCard card){FlashcardCardProgressId id=new FlashcardCardProgressId(login(userId),card.getId());return progress.findById(id).orElseGet(()->FlashcardCardProgress.builder().id(id).user(user(userId)).card(card).nextReviewAt(LocalDateTime.now()).build());}
    private void apply(FlashcardCardProgress p,int quality){int value=Math.max(0,Math.min(5,quality));int repetitions=Optional.ofNullable(p.getRepetitionCount()).orElse(0),interval=Optional.ofNullable(p.getIntervalDays()).orElse(0);if(value<3){repetitions=0;interval=1;p.setStatus("LEARNING");}else{repetitions++;interval=repetitions==1?1:repetitions==2?6:Math.max(1,interval*2);p.setStatus(repetitions>=3?"MASTERED":"LEARNING");}p.setRepetitionCount(repetitions);p.setIntervalDays(interval);p.setReviewCount(Optional.ofNullable(p.getReviewCount()).orElse(0)+1);p.setLastQuality((byte)value);p.setLastReviewedAt(LocalDateTime.now());p.setNextReviewAt(LocalDateTime.now().plusDays(interval));if(p.getEasinessFactor()==null)p.setEasinessFactor(new BigDecimal("2.50"));}
    private FlashcardUserSetState state(Long userId,FlashcardSet set){FlashcardUserSetStateId id=new FlashcardUserSetStateId(login(userId),set.getId());return states.findById(id).orElseGet(()->FlashcardUserSetState.builder().id(id).user(user(userId)).set(set).build());}private void accessed(Long userId,FlashcardSet set){if(userId==null)return;FlashcardUserSetState state=state(userId,set);state.setLastAccessedAt(LocalDateTime.now());states.save(state);}
    private CommentDto commentDto(ContentComment comment,ContentCommentRating rating,Long userId){String name=StringUtils.hasText(comment.getUser().getFullName())?comment.getUser().getFullName():comment.getUser().getUsername();return CommentDto.builder().id(comment.getId()).userId(comment.getUser().getId()).displayName(name).content(comment.getContent()).rating(rating==null?null:rating.getRating()).ownedByMe(userId!=null&&userId.equals(comment.getUser().getId())).createdAt(comment.getCreatedAt()).build();}
    private List<ParsedLineDto> parse(String text){if(!StringUtils.hasText(text))return List.of();List<ParsedLineDto> result=new ArrayList<>();for(String raw:text.split("\\R")){if(!StringUtils.hasText(raw))continue;Matcher matcher=IMPORT.matcher(raw.trim());if(!matcher.matches()){result.add(ParsedLineDto.builder().raw(raw.trim()).valid(false).build());continue;}result.add(ParsedLineDto.builder().front(matcher.group(1).trim()).reading(matcher.group(2).trim()).back(matcher.group(3).trim()).hanViet(blank(matcher.group(4))).raw(raw.trim()).valid(true).build());}return result;}
}
