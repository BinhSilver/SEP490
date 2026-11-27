package com.jlas.flashcard.service.impl;

import com.jlas.flashcard.util.KanaWords;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.flashcard.ai.HanVietAiService;
import com.jlas.flashcard.ai.HanVietAiService.CardEnrichResult;
import com.jlas.flashcard.dto.FlashcardDtos.*;
import com.jlas.flashcard.entity.*;
import com.jlas.flashcard.entity.FlashcardCardProgress.FlashcardCardProgressId;
import com.jlas.flashcard.entity.FlashcardUserSetState.FlashcardUserSetStateId;
import com.jlas.flashcard.repository.*;
import com.jlas.flashcard.service.FlashcardFileCodec;
import com.jlas.flashcard.service.FlashcardFileCodec.CardRow;
import com.jlas.flashcard.service.FlashcardService;
import com.jlas.moderation.entity.ContentComment;
import com.jlas.moderation.entity.ContentCommentRating;
import com.jlas.moderation.dto.ModerationResult;
import com.jlas.moderation.repository.ContentCommentRatingRepository;
import com.jlas.moderation.repository.ContentCommentRepository;
import com.jlas.moderation.service.ContentModerationPort;
import com.jlas.moderation.service.ModerationRateLimitService;
import com.jlas.moderation.util.ImageUploads;
import com.jlas.moderation.util.UploadFiles;
import com.jlas.moderation.service.UserActionPolicyPort;
import com.jlas.notification.service.UserNotificationPort;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import java.io.IOException;
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
    private final FlashcardSetRepository sets; private final FlashcardCardRepository cards; private final FlashcardFolderRepository folders;
    private final FlashcardCardProgressRepository progress; private final FlashcardUserSetStateRepository states;
    private final FlashcardStudySessionRepository sessions; private final FlashcardStudySessionItemRepository sessionItems;
    private final UserRepository users; private final HanVietAiService hanVietAiService;
    private final ContentCommentRepository comments; private final ContentCommentRatingRepository ratings;
    private final ContentModerationPort moderation;
    private final ModerationRateLimitService imageRateLimits;
    private final UserActionPolicyPort actionPolicy;
    private final UserNotificationPort notifications;
    private final com.jlas.flashcard.service.FlashcardStudyAccess studyAccess;
    private final FlashcardFileCodec fileCodec;
    private final com.jlas.flashcard.service.FlashcardSrsService srs;
    private final com.jlas.game.service.AvatarFrameService frames;
    private final com.jlas.common.history.ViewHistoryRepository history;
    private final FlashcardSetStatsRepository setStats;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
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
    @Override @Transactional(readOnly = true)
    public PageResponse<SetSummaryDto> favoriteSets(String title, String sort, int page, int size, Long userId) {
        login(userId);
        return page(sets.findFavorites(userId, blank(title), pageable(sort, page, size)), userId);
    }

    @Override
    public PageResponse<SetSummaryDto> historySets(String title, int page, int size, Long userId) {
        login(userId);
        Map<Long, LocalDateTime> seen = history.recent(userId, com.jlas.common.history.ViewHistoryRepository.Item.FLASHCARD_SET);
        Map<Long, FlashcardSet> byId = new HashMap<>();
        sets.findAllById(seen.keySet()).forEach(set -> byId.put(set.getId(), set));
        String query = blank(title) == null ? null : title.trim().toLowerCase(Locale.ROOT);
        // Same visibility as Favorites: the user's own sets, or public published ones that still exist.
        List<FlashcardSet> visible = seen.keySet().stream().map(byId::get).filter(Objects::nonNull)
                .filter(set -> set.getDeletedAt() == null
                        && (userId.equals(set.getOwner().getId()) || "PUBLIC".equals(set.getVisibility()) && !set.isDraft()))
                .filter(set -> query == null || set.getTitle() != null && set.getTitle().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)));
        int from = (int) Math.min(pageable.getOffset(), visible.size());
        PageResponse<SetSummaryDto> result = page(new PageImpl<>(visible.subList(from, Math.min(from + pageable.getPageSize(), visible.size())),
                pageable, visible.size()), userId);
        result.getContent().forEach(dto -> dto.setViewedAt(seen.get(dto.getId())));
        return result;
    }

    @Override
    public void removeHistory(Long setId, Long userId) {
        history.remove(login(userId), com.jlas.common.history.ViewHistoryRepository.Item.FLASHCARD_SET, setId);
    }

    @Override
    public void clearHistory(Long userId) {
        history.clear(login(userId), com.jlas.common.history.ViewHistoryRepository.Item.FLASHCARD_SET);
    }
    /** Mở một bộ cũng ghi lần truy cập, để bộ đó hiện ở mục "Xem gần đây" (Game Arena). */
    @Override
    public SetDetailDto set(Long id, Long userId) { FlashcardSet set = readable(id, userId); accessed(userId, set); return detail(set, userId); }
    @Override public SetDetailDto create(SetRequest request, Long userId) {
        // A muted user may still build private sets for studying; only public sets are community content.
        if ("PUBLIC".equals(visibility(request.getVisibility()))) actionPolicy.assertCanAct(login(userId));
        validateComplete(request, null);
        FlashcardSet set = sets.save(FlashcardSet.builder().owner(user(userId)).title(required(request.getTitle(), "Tên bộ thẻ"))
                .description(blank(request.getDescription())).thumbnailUrl(blank(request.getThumbnail())).visibility(visibility(request.getVisibility()))
                .folder(folderOrNull(request.getFolderId(), userId)).draft(false).build());
        replaceCards(set, request.getCards()); moderateSetIfPublic(set, userId); return detail(set, userId);
    }
    @Override public SetDetailDto update(Long id, SetRequest request, Long userId) {
        if ("PUBLIC".equals(visibility(request.getVisibility()))) actionPolicy.assertCanAct(login(userId));
        FlashcardSet set = owner(id, userId); validateComplete(request, set); set.setTitle(required(request.getTitle(), "Tên bộ thẻ"));
        set.setDescription(blank(request.getDescription())); set.setThumbnailUrl(blank(request.getThumbnail())); set.setVisibility(visibility(request.getVisibility()));
        set.setFolder(folderOrNull(request.getFolderId(), userId));
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
        source = readable(source.getId(), userId);
        return SyncPreviewResponse.builder().newCardCount(missingSourceCards(copy,source).size()).originalTitle(source.getTitle()).build();
    }
    @Override public SyncResponse sync(Long id, Long userId) {
        FlashcardSet copy=owner(id,userId); FlashcardSet source=copy.getSourceSet(); if(source==null) throw bad("Bộ thẻ này không phải bản sao");
        source = readable(source.getId(), userId);
        List<FlashcardCard> additions=missingSourceCards(copy,source); int position=cards.findBySetIdOrderByPositionAscIdAsc(copy.getId()).size();
        for(FlashcardCard card:additions){CardInput input=input(card); input.setPosition(position++); addCard(copy,input);}
        copy.setLastSyncedAt(LocalDateTime.now()); sets.save(copy); return SyncResponse.builder().addedCount(additions.size()).build();
    }
    @Override public SetDetailDto importText(ImportRequest request, Long userId) {
        List<CardInput> inputs = parse(request.getText()).stream().filter(ParsedLineDto::isValid).map(row -> {
            CardInput input = new CardInput(); input.setFront(row.getFront()); input.setReading(row.getReading()); input.setMeaning(row.getBack()); input.setBack(back(row.getReading(),row.getBack())); input.setHanViet(row.getHanViet()); return input;
        }).toList();
        if (inputs.isEmpty()) throw bad("Không có dòng hợp lệ. Dùng: Từ - Cách đọc : Nghĩa (Hán Việt); từ Katakana/Kana có thể bỏ cách đọc");
        return create(new SetRequest(request.getTitle(),request.getDescription(),null,request.getVisibility(),null,inputs),userId);
    }
    @Override @Transactional(readOnly = true) public ExportFileDto export(Long id, String format, boolean includeHanViet, Long userId) {
        FlashcardSet set = readable(id, userId); FlashcardFileCodec.Format type = FlashcardFileCodec.Format.of(format);
        List<CardRow> rows = cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).stream()
                .map(card -> new CardRow(card.getFront(), card.getReading(), exportMeaning(card), card.getHanViet())).toList();
        String name = StringUtils.hasText(set.getTitle()) ? set.getTitle().replaceAll("[\\\\/:*?\"<>|\r\n\t]+", "_").trim() : "flashcards";
        return ExportFileDto.builder().filename(name + "." + type.extension).contentType(type.contentType).content(fileCodec.write(rows, type, includeHanViet)).build();
    }
    /** The Nghĩa column: the stored meaning, else the back with its "reading — " prefix removed (older cards). */
    private static String exportMeaning(FlashcardCard card) {
        if (StringUtils.hasText(card.getMeaning())) return card.getMeaning();
        String back = card.getBack() == null ? "" : card.getBack();
        String prefix = StringUtils.hasText(card.getReading()) ? card.getReading() + " — " : null;
        return prefix != null && back.startsWith(prefix) ? back.substring(prefix.length()) : back;
    }
    @Override @Transactional(readOnly = true) public ImportFileResponse importFile(MultipartFile file, Long userId) {
        login(userId); if (file == null || file.isEmpty()) throw bad("Cần chọn file");
        try { String text = fileCodec.toImportText(file.getOriginalFilename(), file.getBytes()); return ImportFileResponse.builder().text(text).lineCount(text.isEmpty() ? 0 : (int) text.lines().count()).build(); }
        catch (IOException exception) { throw bad("Không đọc được file"); }
    }
    @Override
    public List<EnrichResponse> generateImportCards(String text) {
        return hanVietAiService.generateImportCards(text).stream()
                .map(result -> EnrichResponse.builder().front(result.kanji()).reading(result.reading())
                        .meaning(result.meaning()).back(result.back()).hanViet(result.hanViet()).build())
                .toList();
    }
    @Override @Transactional(readOnly = true)
    public ImportHanVietResponse enrichImport(String text) {
        String value = hanVietAiService.enrichImportText(text);
        return ImportHanVietResponse.builder().text(value).lines(parse(value)).build();
    }
    @Override @Transactional(readOnly = true)
    public EnrichResponse enrich(String term) {
        CardEnrichResult result = hanVietAiService.enrichFromKanji(required(term, "Từ vựng"));
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
        if(request==null||request.getSetId()==null||request.getCardId()==null) throw bad("Cần setId và cardId"); studyable(request.getSetId(),userId); FlashcardCard card=cards.findById(request.getCardId()).orElseThrow(()->missing("Không tìm thấy thẻ")); if(!card.getSet().getId().equals(request.getSetId())) throw bad("Thẻ không thuộc bộ này"); FlashcardCardProgress p=stateProgress(userId,card); srs.record(p,request.getQuality()==null?3:request.getQuality(),"STUDY"); progress.save(p); accessed(userId,card.getSet()); return summary(request.getSetId(),userId);
    }
    @Override @Transactional(readOnly = true)
    public StudySummaryDto summary(Long setId,Long userId) {
        studyable(setId,userId); login(userId); List<FlashcardCard> all=cards.findBySetIdOrderByPositionAscIdAsc(setId); Map<Long,FlashcardCardProgress> map=progressMap(userId,setId); List<CardDto> learning=all.stream().filter(card->"LEARNING".equals(Optional.ofNullable(map.get(card.getId())).map(FlashcardCardProgress::getStatus).orElse("NEW"))).map(card->dto(card,map.get(card.getId()))).toList();
        return StudySummaryDto.builder().total(all.size()).remembered((int)map.values().stream().filter(p->"MASTERED".equals(p.getStatus())).count()).notRemembered(learning.size()).starred((int)map.values().stream().filter(p->Boolean.TRUE.equals(p.getIsStarred())).count()).notRememberedCards(learning).build();
    }
    @Override public StudySummaryDto complete(Long setId,Long userId) { return complete(setId,"RECALL",userId); }
    @Override public StudySummaryDto complete(Long setId,String mode,Long userId) {
        login(userId);
        FlashcardSet set=studyable(setId,userId);
        boolean pathGenerated = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_path_flashcard_set WHERE set_id = ?", Integer.class, setId) > 0;
        String completedMode = mode == null ? "RECALL" : mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("RECALL", "BROWSE").contains(completedMode)) throw bad("Chế độ học flashcard không hợp lệ.");
        long total = cards.countBySetId(setId);
        Long graded = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM flashcard_card c JOIN flashcard_card_progress p"
                + " ON p.card_id = c.id AND p.user_id = ? WHERE c.set_id = ? AND p.last_reviewed_at IS NOT NULL AND p.last_quality IS NOT NULL",
                Long.class, userId, setId);
        if (total == 0 || graded == null || graded < total)
            throw bad(pathGenerated ? "Hãy chấm mức độ nhớ cho tất cả thẻ trước khi hoàn tất lộ trình."
                    : "Hãy học và chấm đủ các thẻ trước khi kết thúc lượt học.");
        sessions.save(FlashcardStudySession.builder().user(user(userId)).set(set).sessionType("FLASHCARD").mode(completedMode).status("COMPLETED").finishedAt(LocalDateTime.now()).build());
        FlashcardUserSetState state=state(userId,set); if(state.getFirstCompletedAt()==null)state.setFirstCompletedAt(LocalDateTime.now()); state.setLastAccessedAt(LocalDateTime.now()); states.save(state); return summary(setId,userId);
    }
    @Override public StudySummaryDto reset(Long setId,Long userId) { studyable(setId,userId); login(userId); for(FlashcardCardProgress p:progress.findByUserIdAndCardSetId(userId,setId)){p.setStatus("NEW");p.setReviewCount(0);p.setRepetitionCount(0);p.setIntervalDays(0);p.setEasinessFactor(com.jlas.flashcard.util.SrsUtil.DEFAULT_EASINESS);p.setLastReviewedAt(null);p.setNextReviewAt(LocalDateTime.now());} progress.flush(); return summary(setId,userId); }
    @Override public Long startQuiz(StartQuizRequest request,Long userId) {
        if(request==null||request.getSetId()==null)throw bad("Cần setId");
        String questionField=quizField(request.getQuestionField(),"KANJI"),answerField=quizField(request.getAnswerField(),"MEANING");
        if(questionField.equals(answerField))throw bad("Nội dung câu hỏi và đáp án phải khác nhau");
        FlashcardSet set=studyable(request.getSetId(),userId); Set<Long> selected=request.getSelectedCardIds()==null?Set.of():new HashSet<>(request.getSelectedCardIds()); Map<Long,FlashcardCardProgress> map=progressMap(userId,set.getId());
        List<FlashcardCard> questions=cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).stream().filter(card->selected.isEmpty()||selected.contains(card.getId())).filter(card->!request.isWrongOnly()||"LEARNING".equals(Optional.ofNullable(map.get(card.getId())).map(FlashcardCardProgress::getStatus).orElse("NEW"))).filter(card->StringUtils.hasText(quizValue(card,questionField))&&StringUtils.hasText(quizValue(card,answerField))).toList();
        if(questions.size()<4)throw bad("Cần ít nhất 4 thẻ có đủ dữ liệu cho mode đã chọn để làm bài kiểm tra");
        FlashcardStudySession session=sessions.save(FlashcardStudySession.builder().user(user(userId)).set(set).sessionType("QUIZ").mode("QUIZ_"+questionField+"_TO_"+answerField).status("IN_PROGRESS").build()); int position=0;for(FlashcardCard card:questions)sessionItems.save(FlashcardStudySessionItem.builder().session(session).card(card).position(position++).build());return session.getId();
    }
    @Override @Transactional(readOnly = true)
    public QuizQuestionDto nextQuiz(Long sessionId, Long userId) {
        FlashcardStudySession session = quiz(sessionId, userId);
        String[] fields = quizFields(session);
        List<FlashcardStudySessionItem> all = sessionItems.findBySessionIdOrderByPositionAsc(sessionId);
        FlashcardStudySessionItem item = all.stream().filter(row -> row.getIsCorrect() == null)
                .findFirst().orElse(null);
        if (item == null) return null;
        Set<String> usedTexts = new HashSet<>();
        usedTexts.add(quizValue(item.getCard(), fields[1]).toLowerCase(Locale.ROOT));
        List<FlashcardCard> pool = new ArrayList<>(cards.findBySetIdOrderByPositionAscIdAsc(session.getSet().getId()));
        pool.removeIf(card -> card.getId().equals(item.getCard().getId()));
        Collections.shuffle(pool);
        List<FlashcardCard> options = new ArrayList<>();
        for (FlashcardCard card : pool) {
            String value = quizValue(card, fields[1]);
            if (StringUtils.hasText(value) && usedTexts.add(value.toLowerCase(Locale.ROOT))) options.add(card);
            if (options.size() == 3) break;
        }
        options.add(item.getCard());
        Collections.shuffle(options);
        List<OptionDto> result = new ArrayList<>();
        char label = 'A';
        for (FlashcardCard card : options) {
            result.add(OptionDto.builder().cardId(card.getId()).label(String.valueOf(label++))
                    .text(quizValue(card, fields[1])).build());
        }
        return QuizQuestionDto.builder().sessionId(sessionId).cardId(item.getCard().getId())
                .question(quizValue(item.getCard(), fields[0])).reading(item.getCard().getReading())
                .meaning(item.getCard().getMeaning()).hanViet(item.getCard().getHanViet())
                .options(result).questionIndex(item.getPosition() + 1).totalQuestions(all.size()).build();
    }
    @Override public AnswerResultDto answer(AnswerRequest request,Long userId) { if(request==null||request.getSessionId()==null||request.getCardId()==null||request.getChosenCardId()==null)throw bad("Thiếu câu trả lời");FlashcardStudySession session=quiz(request.getSessionId(),userId);FlashcardStudySessionItem item=sessionItems.findBySessionIdAndCardId(session.getId(),request.getCardId()).orElseThrow(()->bad("Câu hỏi không hợp lệ"));if(item.getIsCorrect()!=null)throw bad("Câu hỏi đã trả lời");FlashcardCard chosen=cards.findById(request.getChosenCardId()).orElseThrow(()->bad("Đáp án không hợp lệ"));boolean right=item.getCard().getId().equals(chosen.getId());item.setChosenCard(chosen);item.setIsCorrect(right);item.setAnsweredAt(LocalDateTime.now());sessionItems.save(item);FlashcardCardProgress p=stateProgress(userId,item.getCard());srs.record(p,right?5:1,"QUIZ");progress.save(p);List<FlashcardStudySessionItem> answers=sessionItems.findBySessionIdOrderByPositionAsc(session.getId());return AnswerResultDto.builder().correct(right).correctCardId(item.getCard().getId()).correctText(quizValue(item.getCard(),quizFields(session)[1])).currentCorrect((int)answers.stream().filter(a->Boolean.TRUE.equals(a.getIsCorrect())).count()).currentWrong((int)answers.stream().filter(a->Boolean.FALSE.equals(a.getIsCorrect())).count()).build(); }
    @Override public QuizSummaryDto finishQuiz(Long sessionId,Long userId) { FlashcardStudySession session=quiz(sessionId,userId);List<FlashcardStudySessionItem> all=sessionItems.findBySessionIdOrderByPositionAsc(sessionId);int right=(int)all.stream().filter(a->Boolean.TRUE.equals(a.getIsCorrect())).count(),wrong=(int)all.stream().filter(a->Boolean.FALSE.equals(a.getIsCorrect())).count();session.setStatus("COMPLETED");session.setFinishedAt(LocalDateTime.now());sessions.save(session);int seconds=(int)Duration.between(session.getStartedAt(),session.getFinishedAt()).toSeconds();Map<Long,FlashcardCardProgress> map=progressMap(userId,session.getSet().getId());return QuizSummaryDto.builder().sessionId(sessionId).total(all.size()).correct(right).wrong(wrong).percentage(all.isEmpty()?0:right*100.0/all.size()).durationSeconds(seconds).avgSecondsPerQuestion(all.isEmpty()?0:(double)seconds/all.size()).wrongCards(all.stream().filter(a->Boolean.FALSE.equals(a.getIsCorrect())).map(a->dto(a.getCard(),map.get(a.getCard().getId()))).toList()).build(); }
    @Override @Transactional(readOnly = true)
    public PageResponse<CommentDto> comments(Long setId,int page,int size,Long userId){readable(setId,userId);Page<ContentComment> result=comments.findByTargetTypeAndTargetIdAndIsHiddenFalseOrderByCreatedAtDesc(TARGET,setId,PageRequest.of(Math.max(0,page),Math.min(Math.max(1,size),100)));Map<Long,ContentCommentRating> map=new HashMap<>();ratings.findAllById(result.getContent().stream().map(ContentComment::getId).toList()).forEach(r->map.put(r.getCommentId(),r));return PageResponse.<CommentDto>builder().content(withProfileStyles(result.getContent().stream().map(c->commentDto(c,map.get(c.getId()),userId)).toList())).page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements()).totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();}
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
        // JLAS-W0250: community comments publish immediately; a REVIEW verdict only queues a post-hoc check.
        boolean flagged = result.needsReview();
        ContentComment comment = comments.save(ContentComment.builder().targetType(TARGET).targetId(setId)
                .user(user(userId)).content(text).isHidden(false).build());
        Long logId = moderation.persistLog("FLASHCARD_COMMENT", comment.getId(), userId,
                "COMMENT", text, result);
        if (flagged) {
            moderation.enqueueReview(logId, "FLASHCARD_COMMENT", comment.getId(), userId,
                    "Bình luận bộ thẻ " + set.getTitle(), result.getSummary());
        }
        if (!set.getOwner().getId().equals(userId)) {
            String commenter = StringUtils.hasText(comment.getUser().getFullName())
                    ? comment.getUser().getFullName() : comment.getUser().getUsername();
            notifications.notifyUser(set.getOwner().getId(), "FLASHCARD_COMMENT", "Bình luận mới về bộ thẻ",
                    commenter + " đã bình luận về bộ thẻ “" + set.getTitle() + "”.",
                    "/flashcard/study.html?setId=" + setId, "FLASHCARD_COMMENT_" + comment.getId());
        }
        ContentCommentRating rating = null;
        if (request.getRating() != null) {
            if (request.getRating() < 1 || request.getRating() > 5) throw bad("Đánh giá từ 1 đến 5");
            rating = ratings.save(ContentCommentRating.builder().commentId(comment.getId())
                    .rating(request.getRating()).build());
        }
        return withProfileStyles(List.of(commentDto(comment, rating, userId))).get(0);
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
        if(request!=null&&StringUtils.hasText(request.getMemoryStatus())){String status=request.getMemoryStatus().toUpperCase(Locale.ROOT);if(!"REMEMBERED".equals(status)&&!"NOT_REMEMBERED".equals(status))throw bad("memoryStatus không hợp lệ");srs.mark(p,"REMEMBERED".equals(status));}
        progress.save(p); accessed(userId,card.getSet()); return dto(card,p);
    }
    @Override public UploadResponse upload(MultipartFile file, Long userId) {
        login(userId);
        byte[] bytes = ImageUploads.validate(file, 5L * 1024 * 1024, true);
        imageRateLimits.checkImageUpload("flashcard:" + userId);
        String extension = UploadFiles.extension(file.getOriginalFilename());
        String name = UUID.randomUUID() + "." + extension;
        Path temporary = null;
        try {
            temporary = Files.createTempFile("jlas-flashcard-", "." + extension);
            Files.write(temporary, bytes);
            ModerationResult result = moderation.scanImage(temporary, file.getOriginalFilename());
            if (!result.isSafe()) throw new ApiException(result.isBlocked() ? ErrorCode.CONTENT_BLOCKED
                    : ErrorCode.CONTENT_PENDING_REVIEW, "Ảnh chưa qua kiểm duyệt. Vui lòng chọn ảnh khác hoặc thử lại sau");
            Path directory = Paths.get(uploadPath).toAbsolutePath().normalize();
            UploadFiles.write(directory.resolve(name), bytes);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không thể lưu ảnh");
        } finally {
            if (temporary != null) UploadFiles.delete(temporary);
        }
        return UploadResponse.builder().filename(name).url("/uploads/flashcard/" + name).build();
    }

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
        if(request.getCards()==null)return;int row=1;for(CardInput input:request.getCards()){if(input!=null){length(input.getFront(),500,"Từ vựng thẻ "+row);length(input.getBack(),500,"Mặt sau thẻ "+row);length(input.getReading(),255,"Cách đọc thẻ "+row);length(input.getMeaning(),500,"Nghĩa thẻ "+row);length(input.getHanViet(),255,"Hán Việt thẻ "+row);length(input.getImage(),500,"Ảnh thẻ "+row);}row++;}
    }
    private void length(String value,int max,String label){if(value!=null&&value.length()>max)throw bad(label+" không được vượt quá "+max+" ký tự");}
    private void validateComplete(SetRequest request,FlashcardSet current){
        if(request==null)throw bad("Thiếu dữ liệu bộ thẻ");required(request.getTitle(),"Tên bộ thẻ");
        if(request.getCards()!=null){if(request.getCards().isEmpty())throw bad("Bộ thẻ phải có ít nhất 1 thẻ");int row=1;for(CardInput input:request.getCards()){validateCompleteCard(input,row++);}return;}
        if(current==null)throw bad("Bộ thẻ phải có ít nhất 1 thẻ");List<FlashcardCard> existing=cards.findBySetIdOrderByPositionAscIdAsc(current.getId());if(existing.isEmpty())throw bad("Bộ thẻ phải có ít nhất 1 thẻ");int row=1;for(FlashcardCard card:existing){if(!StringUtils.hasText(card.getFront())||!StringUtils.hasText(card.getMeaning()))throw bad("Thẻ "+row+" còn thiếu từ vựng hoặc nghĩa");row++;}
    }
    /** Only the word and its meaning are required; reading, Hán Việt and image are optional. */
    private void validateCompleteCard(CardInput input,int row){if(input==null||!StringUtils.hasText(input.getFront())||!StringUtils.hasText(input.getMeaning()))throw bad("Thẻ "+row+" còn thiếu từ vựng hoặc nghĩa");}
    private void moderateSetIfPublic(FlashcardSet set, Long userId) {
        if (!"PUBLIC".equals(set.getVisibility())) return;
        moderation.checkPublish(String.valueOf(login(userId)));
        StringBuilder content = new StringBuilder(set.getTitle());
        if (StringUtils.hasText(set.getDescription())) content.append('\n').append(set.getDescription());
        cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).forEach(card -> {
            content.append('\n').append(Optional.ofNullable(card.getFront()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getReading()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getMeaning()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getBack()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getHanViet()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getExampleJa()).orElse(""));
            content.append(' ').append(Optional.ofNullable(card.getExampleVi()).orElse(""));
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
    private PageResponse<SetSummaryDto> page(Page<FlashcardSet> source,Long userId){List<Long> ids=source.getContent().stream().map(FlashcardSet::getId).toList();Map<Long,FlashcardSetStatsRepository.Stats> stats=setStats.forSets(ids);Map<Long,FlashcardUserSetState> mine=new HashMap<>();if(userId!=null&&!ids.isEmpty())states.findByUserIdAndSetIdIn(userId,ids).forEach(row->mine.put(row.getId().getSetId(),row));Set<Long> pathIds=ids.isEmpty()?Set.of():new HashSet<>(jdbcTemplate.queryForList("SELECT set_id FROM learning_path_flashcard_set WHERE set_id IN ("+ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+")",Long.class));return PageResponse.<SetSummaryDto>builder().content(source.getContent().stream().map(set->{FlashcardSetStatsRepository.Stats row=stats.getOrDefault(set.getId(),FlashcardSetStatsRepository.Stats.NONE);return toSummary(set,userId,row.cards(),row.studies(),row.averageRating(),row.ratings(),row.comments(),mine.get(set.getId()),pathIds.contains(set.getId()));}).toList()).page(source.getNumber()).size(source.getSize()).totalElements(source.getTotalElements()).totalPages(source.getTotalPages()).first(source.isFirst()).last(source.isLast()).build();}
    private SetSummaryDto summary(FlashcardSet set,Long userId){List<ContentComment> commentList=comments.findByTargetIdOrderByCreatedAtDesc(set.getId()).stream().filter(c->TARGET.equals(c.getTargetType())&&!Boolean.TRUE.equals(c.getIsHidden())).toList();List<FlashcardUserSetState> ratingStates=states.findBySetIdAndRatingIsNotNull(set.getId());Double average=ratingStates.isEmpty()?null:ratingStates.stream().mapToInt(row->row.getRating()).average().orElse(0);FlashcardUserSetState mine=userId==null?null:states.findByUserIdAndSetId(userId,set.getId()).orElse(null);boolean pathGenerated=jdbcTemplate.queryForObject("SELECT COUNT(*) FROM learning_path_flashcard_set WHERE set_id = ?",Integer.class,set.getId())>0;return toSummary(set,userId,cards.countBySetId(set.getId()),sessions.countBySetIdAndStatus(set.getId(),"COMPLETED"),average,ratingStates.size(),commentList.size(),mine,pathGenerated);}
    private SetSummaryDto toSummary(FlashcardSet set,Long userId,long cardCount,long studyCount,Double average,int ratingCount,int commentCount,FlashcardUserSetState mine){return toSummary(set,userId,cardCount,studyCount,average,ratingCount,commentCount,mine,false);}
    private SetSummaryDto toSummary(FlashcardSet set,Long userId,long cardCount,long studyCount,Double average,int ratingCount,int commentCount,FlashcardUserSetState mine,boolean pathGenerated){boolean favorite=mine!=null&&Boolean.TRUE.equals(mine.getIsFavorite());String name=StringUtils.hasText(set.getOwner().getFullName())?set.getOwner().getFullName():set.getOwner().getUsername();return SetSummaryDto.builder().id(set.getId()).title(set.getTitle()).name(set.getTitle()).description(set.getDescription()).thumbnail(set.getThumbnailUrl()).visibility(set.getVisibility()).moderationStatus(set.getModerationStatus()).ownerId(set.getOwner().getId()).ownerName(name).folderId(set.getFolder()==null?null:set.getFolder().getId()).folderName(set.getFolder()==null?null:set.getFolder().getName()).draft(set.isDraft()).totalCards(cardCount).cardCount(cardCount).studyCount(pathGenerated?0:studyCount).avgRating(pathGenerated?null:average).ratingCount(pathGenerated?0:ratingCount).commentCount(commentCount).ownedByMe(userId!=null&&userId.equals(set.getOwner().getId())).favorited(pathGenerated?false:favorite).myRating(pathGenerated?null:(mine==null||mine.getRating()==null?null:(int)mine.getRating())).learningPathGenerated(pathGenerated).createdAt(set.getCreatedAt()).updatedAt(set.getUpdatedAt()).build();}
    private SetDetailDto detail(FlashcardSet set,Long userId){SetSummaryDto base=summary(set,userId);SetDetailDto dto=new SetDetailDto();dto.setId(base.getId());dto.setTitle(base.getTitle());dto.setName(base.getName());dto.setDescription(base.getDescription());dto.setThumbnail(base.getThumbnail());dto.setVisibility(base.getVisibility());dto.setModerationStatus(base.getModerationStatus());dto.setOwnerId(base.getOwnerId());dto.setOwnerName(base.getOwnerName());dto.setFolderId(base.getFolderId());dto.setFolderName(base.getFolderName());dto.setDraft(base.isDraft());dto.setTotalCards(base.getTotalCards());dto.setCardCount(base.getCardCount());dto.setStudyCount(base.getStudyCount());dto.setAvgRating(base.getAvgRating());dto.setRatingCount(base.getRatingCount());dto.setCommentCount(base.getCommentCount());dto.setOwnedByMe(base.isOwnedByMe());dto.setFavorited(base.isFavorited());dto.setMyRating(base.getMyRating());dto.setLearningPathGenerated(base.isLearningPathGenerated());dto.setCreatedAt(base.getCreatedAt());dto.setUpdatedAt(base.getUpdatedAt());Map<Long,FlashcardCardProgress> map=progressMap(userId,set.getId());dto.setCards(cards.findBySetIdOrderByPositionAscIdAsc(set.getId()).stream().map(card->dto(card,map.get(card.getId()))).toList());return dto;}
    private FolderDto folderDto(FlashcardFolder folder){return FolderDto.builder().id(folder.getId()).name(folder.getName()).setCount(sets.countByFolderIdAndDeletedAtIsNull(folder.getId())).build();}
    private CardDto dto(FlashcardCard card,FlashcardCardProgress state){return com.jlas.flashcard.mapper.FlashcardMapper.card(card,state);}
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
    private static final Set<String> QUIZ_FIELDS = Set.of("KANJI", "HIRAGANA", "MEANING", "HAN_VIET");

    private String quizField(String value, String fallback) {
        String field = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : fallback;
        if (!QUIZ_FIELDS.contains(field)) throw bad("Mode kiểm tra không hợp lệ");
        return field;
    }

    /** Read sessions from both branches and the original MULTIPLE_CHOICE format. */
    private String[] quizFields(FlashcardStudySession session) {
        String mode = Optional.ofNullable(session.getMode()).orElse("");
        String[] fields = null;
        if (mode.startsWith("QUIZ_") && mode.contains("_TO_")) {
            fields = mode.substring(5).split("_TO_", 2);
        } else if (mode.startsWith("MC:") && mode.contains(">")) {
            fields = mode.substring(3).split(">", 2);
        }
        if (fields != null && QUIZ_FIELDS.contains(fields[0]) && QUIZ_FIELDS.contains(fields[1])) return fields;
        return new String[]{"KANJI", "MEANING"};
    }

    /** Preserve W0250 kana eligibility and JLAS2-280's legacy reading fallback. */
    private String quizValue(FlashcardCard card, String field) {
        String value = switch (field) {
            case "KANJI" -> KanaWords.hasKanji(card.getFront()) ? card.getFront() : "";
            case "HIRAGANA" -> StringUtils.hasText(card.getReading()) ? card.getReading()
                    : card.getBack() != null && card.getBack().contains(" — ")
                        ? card.getBack().substring(0, card.getBack().indexOf(" — ")) : "";
            case "MEANING" -> display(card);
            case "HAN_VIET" -> card.getHanViet();
            default -> "";
        };
        return value == null ? "" : value.trim();
    }
    private Map<Long,FlashcardCardProgress> progressMap(Long userId,Long setId){if(userId==null)return Map.of();Map<Long,FlashcardCardProgress> result=new HashMap<>();progress.findByUserIdAndCardSetId(userId,setId).forEach(p->result.put(p.getCard().getId(),p));return result;}
    private FlashcardCardProgress stateProgress(Long userId,FlashcardCard card){FlashcardCardProgressId id=new FlashcardCardProgressId(login(userId),card.getId());return progress.findById(id).orElseGet(()->FlashcardCardProgress.builder().id(id).user(user(userId)).card(card).nextReviewAt(LocalDateTime.now()).build());}
    private FlashcardUserSetState state(Long userId,FlashcardSet set){FlashcardUserSetStateId id=new FlashcardUserSetStateId(login(userId),set.getId());return states.findById(id).orElseGet(()->FlashcardUserSetState.builder().id(id).user(user(userId)).set(set).build());}private void accessed(Long userId,FlashcardSet set){if(userId==null)return;FlashcardUserSetState state=state(userId,set);state.setLastAccessedAt(LocalDateTime.now());states.save(state);history.record(userId,com.jlas.common.history.ViewHistoryRepository.Item.FLASHCARD_SET,set.getId());}
    /** Gắn khung avatar + màu tên (mua ở Cửa hàng) cho người bình luận, một lần truy vấn cho cả trang bình luận. */
    private List<CommentDto> withProfileStyles(List<CommentDto> list) {
        Map<Long, com.jlas.game.repository.AvatarFrameJdbcRepository.Style> styles =
                frames.styles(list.stream().map(CommentDto::getUserId).toList());
        list.forEach(dto -> {
            var style = dto.getUserId() == null ? null : styles.get(dto.getUserId());
            if (style == null) return;
            dto.setAvatarFrame(style.frameCode());
            dto.setNameStyle(style.nameStyle());
        });
        return list;
    }
    private CommentDto commentDto(ContentComment comment,ContentCommentRating rating,Long userId){String name=StringUtils.hasText(comment.getUser().getFullName())?comment.getUser().getFullName():comment.getUser().getUsername();return CommentDto.builder().id(comment.getId()).userId(comment.getUser().getId()).displayName(name).avatarUrl(comment.getUser().getAvatarUrl()).content(comment.getContent()).rating(rating==null?null:rating.getRating()).ownedByMe(userId!=null&&userId.equals(comment.getUser().getId())).createdAt(comment.getCreatedAt()).build();}
    /** Import lines: "Từ - Cách đọc : Nghĩa (Hán Việt)"; a kana word may omit its reading (see KanaWords). */
    private List<ParsedLineDto> parse(String text){if(!StringUtils.hasText(text))return List.of();List<ParsedLineDto> result=new ArrayList<>();for(String raw:text.split("\\R")){if(!StringUtils.hasText(raw))continue;Optional<KanaWords.ImportLine> line=KanaWords.parseImportLine(raw);if(line.isEmpty()){result.add(ParsedLineDto.builder().raw(raw.trim()).valid(false).build());continue;}KanaWords.ImportLine row=line.get();result.add(ParsedLineDto.builder().front(row.front()).reading(row.reading()).back(row.meaning()).hanViet(row.hanViet()).raw(raw.trim()).valid(true).build());}return result;}
}
