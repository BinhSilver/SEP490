package com.jlas.dictionary.service.impl;

import com.jlas.auth.service.AvatarUploadService;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.AttachVocabularyRequest;
import com.jlas.dictionary.dto.DialogueChunkInput;
import com.jlas.dictionary.dto.DialogueChunkResponse;
import com.jlas.dictionary.dto.DialogueLineResponse;
import com.jlas.dictionary.dto.DialogueLineUpsertRequest;
import com.jlas.dictionary.dto.DialogueRoleResponse;
import com.jlas.dictionary.dto.DialogueRoleUpsertRequest;
import com.jlas.dictionary.dto.DialogueSceneResponse;
import com.jlas.dictionary.dto.DialogueSceneUpsertRequest;
import com.jlas.dictionary.dto.ExampleSentenceResponse;
import com.jlas.dictionary.dto.ExampleSentenceUpsertRequest;
import com.jlas.dictionary.dto.GrammarPointResponse;
import com.jlas.dictionary.dto.GrammarPointUpsertRequest;
import com.jlas.dictionary.dto.KanjiReadingResponse;
import com.jlas.dictionary.dto.KanjiResponse;
import com.jlas.dictionary.dto.LessonKanjiRequest;
import com.jlas.dictionary.dto.LessonSummaryResponse;
import com.jlas.dictionary.dto.LessonUpsertRequest;
import com.jlas.dictionary.dto.ReorderRequest;
import com.jlas.dictionary.dto.SectionResponse;
import com.jlas.dictionary.dto.SectionUpsertRequest;
import com.jlas.dictionary.dto.PagedResponse;
import com.jlas.dictionary.dto.TagResponse;
import com.jlas.dictionary.dto.VocabularyCreateRequest;
import com.jlas.dictionary.dto.VocabularySearchItem;
import com.jlas.dictionary.dto.TagUpsertRequest;
import com.jlas.dictionary.dto.TextbookResponse;
import com.jlas.dictionary.dto.TextbookUnitsResponse;
import com.jlas.dictionary.dto.TextbookUpsertRequest;
import com.jlas.dictionary.dto.UnitResponse;
import com.jlas.dictionary.dto.UnitUpsertRequest;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.ContentVocabulary;
import com.jlas.dictionary.entity.DialogueLineChunk;
import com.jlas.dictionary.entity.DialogueLineDetail;
import com.jlas.dictionary.entity.DialogueRole;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.KanjiReading;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.LessonKanji;
import com.jlas.dictionary.entity.LessonSection;
import com.jlas.dictionary.entity.Tag;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.entity.VocabularyKanji;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.mapper.DictionaryMapper;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import com.jlas.dictionary.repository.ContentVoiceRepository;
import com.jlas.dictionary.repository.DialogueLineChunkRepository;
import com.jlas.dictionary.repository.DialogueLineDetailRepository;
import com.jlas.dictionary.repository.DialogueRoleRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiReadingRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.LessonKanjiRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.LessonSectionRepository;
import com.jlas.dictionary.repository.TagRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.repository.VocabularyKanjiRepository;
import com.jlas.dictionary.repository.UnitRepository;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.util.ContentOrdering;
import com.jlas.dictionary.util.DictionaryUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quản trị cây nội dung số hoá. Quyền đã chặn ở controller bằng @PreAuthorize("hasRole('CONTENT_CREATOR')")
 * nên trong này không kiểm vai lần nữa; mọi truy vấn cố ý trả cả bản ghi ARCHIVED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ContentAdminServiceImpl implements ContentAdminService {

    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String CONTENT_TYPE_GRAMMAR = "GRAMMAR";
    private static final String SECTION_TYPE_EXAMPLE = "EXAMPLE";
    private static final String SECTION_TYPE_EXPLANATION = "EXPLANATION";
    private static final String CONTENT_TYPE_READING = "READING";
    private static final String CONTENT_TYPE_LISTENING = "LISTENING";
    private static final String EXAMPLE_SECTION_TITLE = "Câu ví dụ";
    private static final String DIALOGUE_SECTION_PREFIX = "Hội thoại: ";
    private static final String VOCABULARY_SECTION_TITLE = "Từ vựng";
    private static final String VOCABULARY_ITEM_TITLE = "Danh sách từ vựng";
    private static final int MAX_SEARCH_PAGE_SIZE = 100;
    private static final String USAGE_SCOPE_TEXTBOOK = "TEXTBOOK";
    /** Khớp resource handler trong WebMvcConfig; URL lưu trong textbook.thumbnail_url. */
    private static final String THUMBNAIL_URL_PREFIX = "/uploads/textbook-thumbnails/";

    private final TextbookRepository textbookRepository;
    private final UnitRepository unitRepository;
    private final LessonRepository lessonRepository;
    private final LessonSectionRepository lessonSectionRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final ContentItemRepository contentItemRepository;
    private final ContentVocabularyRepository contentVocabularyRepository;
    private final VocabularyEntryRepository vocabularyEntryRepository;
    private final KanjiRepository kanjiRepository;
    private final KanjiReadingRepository kanjiReadingRepository;
    private final LessonKanjiRepository lessonKanjiRepository;
    private final VocabularyKanjiRepository vocabularyKanjiRepository;
    private final DialogueRoleRepository dialogueRoleRepository;
    private final DialogueLineDetailRepository dialogueLineDetailRepository;
    private final DialogueLineChunkRepository dialogueLineChunkRepository;
    private final ContentVoiceRepository contentVoiceRepository;
    private final TagRepository tagRepository;
    private final DictionaryMapper dictionaryMapper;

    /** Thư mục lưu ảnh thumbnail giáo trình (upload.path.textbook-thumbnail). */
    @Value("${upload.path.textbook-thumbnail:uploads/textbook-thumbnails}")
    private String thumbnailUploadPath;

    // ── Đọc ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TextbookResponse> listAllTextbooks() {
        List<Textbook> textbooks = textbookRepository.findAllByOrderByIdAsc();
        if (textbooks.isEmpty()) return Collections.emptyList();

        Map<Long, Long> unitCountByTextbook = new LinkedHashMap<>();
        for (Object[] row : textbookRepository.countUnitsByTextbookIds(
                textbooks.stream().map(Textbook::getId).toList())) {
            unitCountByTextbook.put((Long) row[0], (Long) row[1]);
        }
        return textbooks.stream()
                .map(t -> dictionaryMapper.toTextbookResponse(t, unitCountByTextbook.getOrDefault(t.getId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public TextbookUnitsResponse getTextbookTree(Long textbookId) {
        Textbook textbook = requireTextbook(textbookId);
        List<Unit> units = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbookId);
        // findByTextbookId trả MỌI trạng thái — đúng nhu cầu màn hình quản lý.
        List<Lesson> lessons = lessonRepository.findByTextbookId(textbookId);

        Map<Long, List<LessonSummaryResponse>> lessonsByUnit = new LinkedHashMap<>();
        for (Lesson lesson : lessons) {
            lessonsByUnit.computeIfAbsent(lesson.getUnit().getId(), k -> new ArrayList<>())
                    .add(dictionaryMapper.toLessonSummaryResponse(lesson));
        }
        return TextbookUnitsResponse.builder()
                .textbook(dictionaryMapper.toTextbookResponse(textbook, (long) units.size()))
                .units(units.stream()
                        .map(u -> dictionaryMapper.toUnitResponse(
                                u, lessonsByUnit.getOrDefault(u.getId(), Collections.emptyList())))
                        .toList())
                .build();
    }

    // ── Giáo trình ───────────────────────────────────────────────────────────

    @Override
    public TextbookResponse createTextbook(TextbookUpsertRequest request) {
        Textbook textbook = new Textbook();
        applyTextbook(textbook, request);
        // status để entity tự đặt mặc định DRAFT: giáo trình mới không bao giờ công bố ngay.
        return dictionaryMapper.toTextbookResponse(textbookRepository.save(textbook), 0L);
    }

    @Override
    public TextbookResponse updateTextbook(Long textbookId, TextbookUpsertRequest request) {
        Textbook textbook = requireTextbook(textbookId);
        // Giáo trình đã lưu trữ thì phải khôi phục trước mới sửa được, cùng quy tắc với nội dung bên trong.
        // KHÔNG khoá mất đường khôi phục: nút đó đi qua PATCH /textbooks/{id}/status
        // (DictionaryService.updateTextbookStatus), không qua phương thức này.
        requireNotArchived(textbook);
        applyTextbook(textbook, request);
        long unitCount = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbookId).size();
        return dictionaryMapper.toTextbookResponse(textbookRepository.save(textbook), unitCount);
    }

    private void applyTextbook(Textbook textbook, TextbookUpsertRequest request) {
        textbook.setTitle(request.getTitle().trim());
        textbook.setDescription(blankToNull(request.getDescription()));
        // jlpt_level để trống = giáo trình không theo cấp độ (bảng chữ cái, giáo trình chủ đề).
        textbook.setJlptLevel(blankToNull(request.getJlptLevel()));
        // Hai trường của thumbnail tự sinh; để trống = frontend dùng mặc định (日, màu theo hash tên).
        textbook.setThumbnailKanji(blankToNull(request.getThumbnailKanji()));
        String themeColor = blankToNull(request.getThemeColor());
        textbook.setThemeColor(themeColor == null ? null : themeColor.toLowerCase(Locale.ROOT));
        // Trạng thái KHÔNG đổi ở đây: công bố / lưu trữ đi qua PATCH /textbooks/{id}/status.
        // thumbnail_url cũng KHÔNG đổi ở đây: ảnh đi qua POST/DELETE /textbooks/{id}/thumbnail.
    }

    // ── Thumbnail giáo trình ─────────────────────────────────────────────────

    @Override
    public TextbookResponse uploadTextbookThumbnail(Long textbookId, MultipartFile file) {
        Textbook textbook = requireTextbook(textbookId);
        requireNotArchived(textbook);
        String extension = validateThumbnail(file);

        Path directory = thumbnailDirectory();
        // Tên file chỉ gồm UUID + đuôi trong whitelist nên không thể chứa ../; vẫn kiểm startsWith cho chắc.
        String filename = UUID.randomUUID() + "." + extension;
        Path target = directory.resolve(filename).normalize();
        if (!target.startsWith(directory)) throw new ApiException(ErrorCode.BAD_REQUEST, "Đường dẫn lưu ảnh không hợp lệ.");
        try {
            Files.createDirectories(directory);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không lưu được ảnh thumbnail, vui lòng thử lại.");
        }

        String oldUrl = textbook.getThumbnailUrl();
        textbook.setThumbnailUrl(THUMBNAIL_URL_PREFIX + filename);
        TextbookResponse response = saveTextbookResponse(textbook);
        // Commit xong mới xoá ảnh cũ; nếu rollback thì xoá ảnh mới để không sinh file mồ côi.
        deleteThumbnailAfterCompletion(oldUrl, THUMBNAIL_URL_PREFIX + filename);
        return response;
    }

    @Override
    public TextbookResponse removeTextbookThumbnail(Long textbookId) {
        Textbook textbook = requireTextbook(textbookId);
        requireNotArchived(textbook);
        String oldUrl = textbook.getThumbnailUrl();
        textbook.setThumbnailUrl(null);
        TextbookResponse response = saveTextbookResponse(textbook);
        deleteThumbnailAfterCompletion(oldUrl, null);
        return response;
    }

    private TextbookResponse saveTextbookResponse(Textbook textbook) {
        long unitCount = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbook.getId()).size();
        return dictionaryMapper.toTextbookResponse(textbookRepository.save(textbook), unitCount);
    }

    /**
     * Kiểm file ảnh, trả về đuôi file đã chuẩn hoá. Dùng lại whitelist và kiểm magic bytes của avatar
     * (AvatarUploadService) — content-type từ trình duyệt chỉ là điều kiện phụ, nội dung file mới quyết định.
     */
    private static String validateThumbnail(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Vui lòng chọn một ảnh.");
        if (file.getSize() > AvatarUploadService.MAX_SIZE) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, "Ảnh thumbnail tối đa 2MB.");
        }
        String extension = AvatarUploadService.extension(file.getOriginalFilename());
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!AvatarUploadService.EXTENSIONS.contains(extension) || !AvatarUploadService.MIME_TYPES.contains(contentType)) {
            throw new ApiException(ErrorCode.INVALID_IMAGE);
        }
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException exception) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không đọc được file ảnh.");
        }
        if (read < head.length || !AvatarUploadService.hasImageSignature(head, extension)) {
            throw new ApiException(ErrorCode.INVALID_IMAGE, "Nội dung file không phải ảnh JPG, PNG hoặc WEBP hợp lệ.");
        }
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private Path thumbnailDirectory() {
        return Paths.get(thumbnailUploadPath).toAbsolutePath().normalize();
    }

    /**
     * Sau khi transaction kết thúc: commit → xoá ảnh cũ, rollback → xoá ảnh vừa ghi. Không có transaction
     * (gọi ngoài Spring) thì coi như đã commit.
     */
    private void deleteThumbnailAfterCompletion(String urlOnCommit, String urlOnRollback) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteThumbnailFile(urlOnCommit);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deleteThumbnailFile(status == STATUS_COMMITTED ? urlOnCommit : urlOnRollback);
            }
        });
    }

    /**
     * Chỉ xoá file do chính tính năng này tạo: URL phải bắt đầu bằng /uploads/textbook-thumbnails/, tên file
     * không chứa dấu phân cách thư mục, và đường dẫn sau normalize vẫn nằm trong thư mục thumbnail.
     */
    private void deleteThumbnailFile(String url) {
        if (url == null || !url.startsWith(THUMBNAIL_URL_PREFIX)) return;
        String filename = url.substring(THUMBNAIL_URL_PREFIX.length());
        if (filename.isEmpty() || filename.contains("/") || filename.contains("\\") || filename.contains("..")) return;
        Path directory = thumbnailDirectory();
        Path target = directory.resolve(filename).normalize();
        if (!target.startsWith(directory)) return;
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            log.warn("Không xoá được thumbnail cũ {}: {}", target, exception.getMessage());
        }
    }

    // ── Unit ─────────────────────────────────────────────────────────────────

    @Override
    public UnitResponse createUnit(Long textbookId, UnitUpsertRequest request) {
        Textbook textbook = requireTextbook(textbookId);
        requireNotArchived(textbook);

        Unit unit = new Unit();
        unit.setTextbook(textbook);
        unit.setTitle(request.getTitle().trim());
        unit.setDescription(blankToNull(request.getDescription()));
        unit.setOrderIndex(nextOrderIndex(unitRepository.findMaxOrderIndexByTextbookId(textbookId)));
        return dictionaryMapper.toUnitResponse(unitRepository.save(unit));
    }

    @Override
    public UnitResponse updateUnit(Long unitId, UnitUpsertRequest request) {
        Unit unit = requireUnit(unitId);
        requireNotArchived(unit.getTextbook());
        unit.setTitle(request.getTitle().trim());
        unit.setDescription(blankToNull(request.getDescription()));
        // Không đổi textbook cha: id cha nằm trên URL lúc tạo, PUT không có đường chuyển unit sang giáo trình khác.
        return dictionaryMapper.toUnitResponse(unitRepository.save(unit));
    }

    /**
     * Unit là cấp DUY NHẤT trong cây này bị xoá cứng, vì bảng unit không có cột status nên không thể
     * lưu trữ như textbook và lesson (quy ước: không đổi schema cho ticket này). Bù lại, chỉ cho xoá
     * khi unit đã rỗng — fk_lesson_unit là ON DELETE CASCADE, xoá unit còn bài sẽ kéo theo bài, nội dung
     * và cả lesson_progress của học viên.
     * <p>
     * countLessonsByUnitId đếm cả bài ARCHIVED: bài đã lưu trữ vẫn là dữ liệu phải giữ, không được coi
     * unit là rỗng chỉ vì bài đang bị ẩn khỏi danh sách.
     */
    @Override
    public void deleteUnit(Long unitId) {
        Unit unit = requireUnit(unitId);
        long lessonCount = unitRepository.countLessonsByUnitId(unitId);
        if (lessonCount > 0) {
            // Thông báo phải mô tả ĐÚNG lối thoát đang có thật. Lưu trữ bài KHÔNG làm phần rỗng
            // (countLessonsByUnitId đếm cả bài đã lưu trữ), và không có chức năng chuyển bài sang phần khác.
            throw new ApiException(ErrorCode.CONFLICT,
                    "Phần \"" + unit.getTitle() + "\" còn " + lessonCount + " bài học nên không xoá được. "
                            + "Chỉ xoá được phần rỗng: hãy xoá từng bài bên trong trước (bài đã có người học "
                            + "hoặc đã có nội dung thì không xoá được, khi đó hãy lưu trữ phần nội dung thay vì xoá).");
        }
        // Phần rỗng bài vẫn có thể có quiz gắn thẳng vào unit (assessment_quiz.unit_id, CASCADE).
        long quizCount = unitRepository.countAssessmentQuizzes(unitId);
        if (quizCount > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Phần \"" + unit.getTitle() + "\" đang gắn " + quizCount + " bài kiểm tra nên không xoá được. "
                            + "Hãy gỡ bài kiểm tra khỏi phần này trước.");
        }
        unitRepository.delete(unit);
    }

    /**
     * Xoá cứng bài học — lối duy nhất để dọn bài tạo nhầm, và là tiền đề để xoá được phần rỗng.
     * <p>
     * MỌI khoá ngoại trỏ tới lesson(id) đều ON DELETE CASCADE: lesson_progress, user_learning_path_item,
     * assessment_quiz, lesson_section (kéo theo content_item), grammar_point, dialogue_role,
     * conversion_rule, lesson_tag. Một lệnh DELETE là mất sạch, không khôi phục được. Nên chặn theo
     * bốn nhóm dưới đây, mỗi nhóm một thông báo riêng để người dùng biết chính xác vướng ở đâu:
     * <ol>
     *   <li>Bài đã công bố hoặc đã lưu trữ → chỉ xoá bài còn ở trạng thái nháp.</li>
     *   <li>Đã có học viên học hoặc bài nằm trong lộ trình → không bao giờ xoá, phải lưu trữ.</li>
     *   <li>Có quiz gắn vào (bảng assessment_*) → gỡ quiz trước.</li>
     *   <li>Đã có nội dung biên soạn → lưu trữ thay vì xoá, tránh mất công biên soạn vì một cú bấm.</li>
     * </ol>
     */
    @Override
    public void deleteLesson(Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));
        String name = "Bài \"" + lesson.getTitle() + "\" ";

        if (!STATUS_DRAFT.equals(lesson.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    name + "đang ở trạng thái " + lesson.getStatus() + " nên không xoá được. "
                            + "Chỉ xoá được bài còn ở trạng thái nháp; bài đã công bố thì hãy lưu trữ.");
        }
        long progress = lessonRepository.countLearnerProgress(lessonId);
        if (progress > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    name + "đã có " + progress + " học viên học nên không xoá được — xoá sẽ mất luôn tiến độ của họ. "
                            + "Hãy lưu trữ bài này thay vì xoá.");
        }
        long pathItems = lessonRepository.countLearningPathItems(lessonId);
        if (pathItems > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    name + "đang nằm trong lộ trình học của " + pathItems + " học viên nên không xoá được. "
                            + "Hãy lưu trữ bài này thay vì xoá.");
        }
        long quizzes = lessonRepository.countAssessmentQuizzes(lessonId);
        if (quizzes > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    name + "đang gắn " + quizzes + " bài kiểm tra nên không xoá được. "
                            + "Hãy gỡ bài kiểm tra khỏi bài học này trước.");
        }
        long content = lessonRepository.countEditorialContent(lessonId);
        if (content > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    name + "đã có " + content + " mục nội dung (phần nội dung, ngữ pháp, vai hội thoại) "
                            + "nên không xoá được. Hãy lưu trữ bài này thay vì xoá.");
        }
        lessonRepository.delete(lesson);
    }

    // ── Bài học ──────────────────────────────────────────────────────────────

    @Override
    public LessonSummaryResponse createLesson(Long unitId, LessonUpsertRequest request) {
        Unit unit = requireUnit(unitId);
        requireNotArchived(unit.getTextbook());

        Lesson lesson = new Lesson();
        lesson.setUnit(unit);
        applyLesson(lesson, request);
        lesson.setOrderIndex(nextOrderIndex(lessonRepository.findMaxOrderIndexByUnitId(unitId)));
        // status để entity tự đặt mặc định DRAFT; công bố đi qua PATCH và bị chặn nếu giáo trình chưa công bố.
        return dictionaryMapper.toLessonSummaryResponse(lessonRepository.save(lesson));
    }

    @Override
    public LessonSummaryResponse updateLesson(Long lessonId, LessonUpsertRequest request) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));
        requireNotArchived(lesson.getUnit().getTextbook());
        applyLesson(lesson, request);
        // Không đổi unit cha ở đây, cùng lý do như updateUnit.
        return dictionaryMapper.toLessonSummaryResponse(lessonRepository.save(lesson));
    }

    private void applyLesson(Lesson lesson, LessonUpsertRequest request) {
        // knowledge_tag_id là NOT NULL + FK: tag phải có thật, nếu không DB sẽ từ chối bằng lỗi 500 khó hiểu.
        // Không ép tagType phải là KNOWLEDGE để không chặn dữ liệu cũ đang dùng loại tag khác.
        Tag tag = tagRepository.findById(request.getKnowledgeTagId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Không tìm thấy điểm kiến thức có id " + request.getKnowledgeTagId()));
        lesson.setKnowledgeTag(tag);
        lesson.setTitle(request.getTitle().trim());
        lesson.setDescription(blankToNull(request.getDescription()));
        lesson.setLessonType(request.getLessonType());
        lesson.setSkill(request.getSkill());
        lesson.setSituationTag(blankToNull(request.getSituationTag()));
        lesson.setEstimatedMinutes(request.getEstimatedMinutes());
        lesson.setIsReview(Boolean.TRUE.equals(request.getIsReview()));
    }

    // ── Sắp xếp lại thứ tự ───────────────────────────────────────────────────

    @Override
    public List<UnitResponse> reorderUnits(Long textbookId, ReorderRequest request) {
        requireNotArchived(requireTextbook(textbookId));
        List<Unit> units = unitRepository.findByTextbookIdOrderByOrderIndexAscIdAsc(textbookId);
        List<Unit> ordered = ContentOrdering.sortByRequestedIds(units, Unit::getId, request.getOrderedIds(), "phần");

        ContentOrdering.renumber(ordered, Unit::getOrderIndex, Unit::setOrderIndex, unitRepository::saveAllAndFlush);
        return ordered.stream().map(dictionaryMapper::toUnitResponse).toList();
    }

    @Override
    public List<LessonSummaryResponse> reorderLessons(Long unitId, ReorderRequest request) {
        requireNotArchived(requireUnit(unitId).getTextbook());
        List<Lesson> lessons = lessonRepository.findByUnitIdOrderByOrderIndexAscIdAsc(unitId);
        List<Lesson> ordered = ContentOrdering.sortByRequestedIds(lessons, Lesson::getId, request.getOrderedIds(), "bài học");

        ContentOrdering.renumber(ordered, Lesson::getOrderIndex, Lesson::setOrderIndex, lessonRepository::saveAllAndFlush);
        return ordered.stream().map(dictionaryMapper::toLessonSummaryResponse).toList();
    }

    // ── Phần nội dung trong bài (lesson_section) ─────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<SectionResponse> listSections(Long lessonId) {
        requireLesson(lessonId);
        return lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lessonId).stream()
                .map(this::toSectionResponseWithCount)
                .toList();
    }

    @Override
    public SectionResponse createSection(Long lessonId, SectionUpsertRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());

        LessonSection section = new LessonSection();
        section.setLesson(lesson);
        section.setTitle(request.getTitle().trim());
        section.setSectionType(request.getSectionType());
        section.setOrderIndex(nextOrderIndex(lessonSectionRepository.findMaxOrderIndexByLessonId(lessonId)));
        // Phần mới luôn rỗng nội dung nên khỏi đếm, trả thẳng 0.
        return dictionaryMapper.toSectionResponse(lessonSectionRepository.save(section), 0L);
    }

    @Override
    public SectionResponse updateSection(Long sectionId, SectionUpsertRequest request) {
        LessonSection section = requireSection(sectionId);
        requireNotArchived(section.getLesson().getUnit().getTextbook());
        section.setTitle(request.getTitle().trim());
        section.setSectionType(request.getSectionType());
        // Không đổi bài cha: id cha nằm trên URL lúc tạo, cùng quy tắc với unit và lesson.
        return toSectionResponseWithCount(lessonSectionRepository.save(section));
    }

    @Override
    public void deleteSection(Long sectionId) {
        LessonSection section = requireSection(sectionId);
        long itemCount = lessonSectionRepository.countContentItemsBySectionId(sectionId);
        if (itemCount > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Phần nội dung \"" + section.getTitle() + "\" còn " + itemCount + " mục nên không xoá được. "
                            + "Hãy xoá từng mục nội dung bên trong trước.");
        }
        lessonSectionRepository.delete(section);
    }

    @Override
    public List<SectionResponse> reorderSections(Long lessonId, ReorderRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        List<LessonSection> sections = lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lessonId);
        List<LessonSection> ordered = ContentOrdering.sortByRequestedIds(
                sections, LessonSection::getId, request.getOrderedIds(), "phần nội dung");

        ContentOrdering.renumber(ordered, LessonSection::getOrderIndex, LessonSection::setOrderIndex,
                lessonSectionRepository::saveAllAndFlush);
        return ordered.stream().map(this::toSectionResponseWithCount).toList();
    }

    private SectionResponse toSectionResponseWithCount(LessonSection section) {
        return dictionaryMapper.toSectionResponse(section,
                lessonSectionRepository.countContentItemsBySectionId(section.getId()));
    }

    // ── Ngữ pháp (grammar_point) ─────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<GrammarPointResponse> listGrammarPoints(Long lessonId) {
        requireLesson(lessonId);
        return grammarPointRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lessonId).stream()
                .map(this::toGrammarPointWithCount)
                .toList();
    }

    @Override
    public GrammarPointResponse createGrammarPoint(Long lessonId, GrammarPointUpsertRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());

        GrammarPoint point = new GrammarPoint();
        point.setLesson(lesson);
        applyGrammarPoint(point, request);
        point.setOrderIndex(nextOrderIndex(grammarPointRepository.findMaxOrderIndexByLessonId(lessonId)));
        // Mẫu mới chưa có câu ví dụ nào nên khỏi đếm.
        return dictionaryMapper.toGrammarPointResponse(grammarPointRepository.save(point), 0L);
    }

    @Override
    public GrammarPointResponse updateGrammarPoint(Long grammarPointId, GrammarPointUpsertRequest request) {
        GrammarPoint point = requireGrammarPoint(grammarPointId);
        requireNotArchived(point.getLesson().getUnit().getTextbook());
        applyGrammarPoint(point, request);
        // Không đổi bài cha: id cha nằm trên URL lúc tạo.
        return toGrammarPointWithCount(grammarPointRepository.save(point));
    }

    private void applyGrammarPoint(GrammarPoint point, GrammarPointUpsertRequest request) {
        point.setPattern(request.getPattern().trim());
        point.setMeaning(request.getMeaning().trim());
        point.setExample(blankToNull(request.getExample()));
        point.setNote(blankToNull(request.getNote()));
        if (request.getTagId() == null) {
            point.setTag(null);
        } else {
            point.setTag(tagRepository.findById(request.getTagId())
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                            "Không tìm thấy tag có id " + request.getTagId())));
        }
    }

    @Override
    public void deleteGrammarPoint(Long grammarPointId) {
        GrammarPoint point = requireGrammarPoint(grammarPointId);
        requireNotArchived(point.getLesson().getUnit().getTextbook());
        long examples = contentItemRepository.countByGrammarPointId(grammarPointId);
        if (examples > 0) {
            // fk_content_item_grammar là ON DELETE SET NULL: xoá thẳng sẽ khiến các câu ví dụ này mất
            // liên kết trong im lặng và rơi vào nhóm "Câu ví dụ khác" ở màn hình học.
            throw new ApiException(ErrorCode.CONFLICT,
                    "Mẫu ngữ pháp \"" + point.getPattern() + "\" còn " + examples + " câu ví dụ nên không xoá được. "
                            + "Hãy xoá các câu ví dụ đó, hoặc chuyển chúng sang mẫu khác trước.");
        }
        grammarPointRepository.delete(point);
    }

    @Override
    public List<GrammarPointResponse> reorderGrammarPoints(Long lessonId, ReorderRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        List<GrammarPoint> points = grammarPointRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lessonId);
        List<GrammarPoint> ordered = ContentOrdering.sortByRequestedIds(
                points, GrammarPoint::getId, request.getOrderedIds(), "mẫu ngữ pháp");

        ContentOrdering.renumber(ordered, GrammarPoint::getOrderIndex, GrammarPoint::setOrderIndex,
                grammarPointRepository::saveAllAndFlush);
        return ordered.stream().map(this::toGrammarPointWithCount).toList();
    }

    private GrammarPointResponse toGrammarPointWithCount(GrammarPoint point) {
        return dictionaryMapper.toGrammarPointResponse(point,
                contentItemRepository.countByGrammarPointId(point.getId()));
    }

    // ── Câu ví dụ của mẫu ngữ pháp (content_item loại GRAMMAR) ───────────────

    @Override
    @Transactional(readOnly = true)
    public List<ExampleSentenceResponse> listExamples(Long grammarPointId) {
        requireGrammarPoint(grammarPointId);
        return contentItemRepository.findByGrammarPointId(grammarPointId).stream()
                // Màn hình biên soạn không cần audio; danh sách audio để rỗng thay vì null.
                .map(item -> dictionaryMapper.toExampleSentenceResponse(item, List.of()))
                .toList();
    }

    @Override
    public ExampleSentenceResponse createExample(Long grammarPointId, ExampleSentenceUpsertRequest request) {
        GrammarPoint point = requireGrammarPoint(grammarPointId);
        Lesson lesson = point.getLesson();
        requireNotArchived(lesson.getUnit().getTextbook());

        LessonSection section = resolveExampleSection(lesson, request.getSectionId());
        ContentItem item = new ContentItem();
        item.setSection(section);
        item.setContentType(CONTENT_TYPE_GRAMMAR);
        item.setGrammarPointId(point.getId());
        applyExample(item, request);
        item.setOrderIndex(nextOrderIndex(contentItemRepository.findMaxOrderIndexBySectionId(section.getId())));
        return dictionaryMapper.toExampleSentenceResponse(contentItemRepository.save(item), List.of());
    }

    @Override
    public ExampleSentenceResponse updateExample(Long contentItemId, ExampleSentenceUpsertRequest request) {
        ContentItem item = requireContentItem(contentItemId);
        requireNotArchived(item.getSection().getLesson().getUnit().getTextbook());
        applyExample(item, request);
        // Không đổi section cha và không đổi mẫu ngữ pháp ở đây; chuyển câu sang mẫu khác là việc riêng.
        return dictionaryMapper.toExampleSentenceResponse(contentItemRepository.save(item), List.of());
    }

    @Override
    public void deleteExample(Long contentItemId) {
        ContentItem item = requireContentItem(contentItemId);
        requireNotArchived(item.getSection().getLesson().getUnit().getTextbook());
        // Không cần chốt chặn: content_item là mục lá của nhánh ngữ pháp, chỉ có content_audio trỏ tới
        // và nó cascade theo, không mất dữ liệu người học.
        contentItemRepository.delete(item);
    }

    private void applyExample(ContentItem item, ExampleSentenceUpsertRequest request) {
        item.setTitle(request.getTitle().trim());
        item.setContent(request.getTextJa().trim());
        item.setTranslatedText(blankToNull(request.getTextVi()));
    }

    /**
     * Tìm chỗ chứa cho câu ví dụ. Chỉ định rõ thì dùng đúng phần đó (và phải thuộc chính bài này, nếu
     * không là 400 — gắn nhầm sang bài khác chính là kiểu dữ liệu mồ côi ticket muốn chặn). Để trống thì
     * dùng phần EXAMPLE đầu tiên của bài, chưa có thì tạo một phần mới tên "Câu ví dụ".
     */
    private LessonSection resolveExampleSection(Lesson lesson, Long requestedSectionId) {
        if (requestedSectionId != null) {
            LessonSection section = requireSection(requestedSectionId);
            if (!section.getLesson().getId().equals(lesson.getId())) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "Phần nội dung được chọn không thuộc bài học này.");
            }
            return section;
        }
        // Lọc theo CẢ title, không chỉ section_type: hội thoại cũng dùng type EXAMPLE (title dạng
        // "Hội thoại: <tên cảnh>"), nên lấy "section EXAMPLE đầu tiên" sẽ ném câu ví dụ ngữ pháp vào
        // giữa một đoạn hội thoại ở những bài có hội thoại mà chưa có phần "Câu ví dụ".
        List<LessonSection> sections = lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lesson.getId());
        return sections.stream()
                .filter(s -> SECTION_TYPE_EXAMPLE.equals(s.getSectionType())
                        && EXAMPLE_SECTION_TITLE.equals(s.getTitle()))
                .findFirst()
                .orElseGet(() -> {
                    LessonSection created = new LessonSection();
                    created.setLesson(lesson);
                    created.setTitle(EXAMPLE_SECTION_TITLE);
                    created.setSectionType(SECTION_TYPE_EXAMPLE);
                    created.setOrderIndex(nextOrderIndex(
                            lessonSectionRepository.findMaxOrderIndexByLessonId(lesson.getId())));
                    return lessonSectionRepository.save(created);
                });
    }

    // ── Từ vựng của bài (content_vocabulary) ─────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<VocabularySearchItem> searchVocabulary(String keyword, Long lessonId, int page, int size) {
        Set<Long> attached = lessonId == null
                ? Set.of()
                : Set.copyOf(contentVocabularyRepository.findVocabularyIdsByLessonId(lessonId));
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SEARCH_PAGE_SIZE));
        Page<VocabularyEntry> found = vocabularyEntryRepository
                .searchForEditor(keyword == null ? "" : keyword.trim(), pageable);
        return PagedResponse.of(found, v -> dictionaryMapper.toVocabularySearchItem(v, attached.contains(v.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VocabularySearchItem> listLessonVocabulary(Long lessonId) {
        requireLesson(lessonId);
        return contentVocabularyRepository.findVocabularyByLessonId(lessonId).stream()
                .map(v -> dictionaryMapper.toVocabularySearchItem(v, true))
                .toList();
    }

    @Override
    public VocabularySearchItem attachVocabulary(Long lessonId, AttachVocabularyRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        VocabularyEntry entry = vocabularyEntryRepository.findById(request.getVocabularyId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Không tìm thấy từ vựng có id " + request.getVocabularyId()));

        if (!contentVocabularyRepository.findByLessonIdAndVocabularyId(lessonId, entry.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Từ \"" + entry.getExpression() + "\" đã có trong bài học này.");
        }

        linkVocabulary(lesson, entry, request.getSectionId());
        return dictionaryMapper.toVocabularySearchItem(entry, true);
    }

    @Override
    public VocabularySearchItem createVocabulary(Long lessonId, VocabularyCreateRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        VocabularyEntry entry = findOrCreateVocabulary(request);
        if (!contentVocabularyRepository.findByLessonIdAndVocabularyId(lessonId, entry.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Từ \"" + entry.getExpression() + "\" đã có trong bài học này.");
        }
        linkVocabulary(lesson, entry, null);
        return dictionaryMapper.toVocabularySearchItem(entry, true);
    }

    /** Nối một từ vào chỗ chứa từ vựng của bài. Người gọi đã kiểm bài chưa lưu trữ và từ chưa có trong bài. */
    private void linkVocabulary(Lesson lesson, VocabularyEntry entry, Long requestedSectionId) {
        ContentItem container = resolveVocabularyContainer(lesson, requestedSectionId);
        ContentVocabulary link = new ContentVocabulary();
        link.setId(new ContentVocabulary.ContentVocabularyId(container.getId(), entry.getId()));
        link.setContentItem(container);
        link.setVocabularyEntry(entry);
        contentVocabularyRepository.save(link);
    }

    /**
     * Từ có cùng cặp (cách viết, cách đọc) sau chuẩn hoá thì dùng lại nguyên trạng — không sửa nghĩa hay cấp
     * của từ dùng chung; chưa có thì tạo bản DRAFT phạm vi TEXTBOOK kèm vocabulary_kanji.
     */
    private VocabularyEntry findOrCreateVocabulary(VocabularyCreateRequest request) {
        String expression = DictionaryUtil.normalizeText(request.getExpression());
        String reading = DictionaryUtil.normalizeText(request.getReading());
        String normalizedReading = DictionaryUtil.normalizeReading(reading);
        Optional<VocabularyEntry> existing = vocabularyEntryRepository.findByNormalizedPair(expression, normalizedReading);
        if (existing.isPresent()) return existing.get();

        VocabularyEntry entry = vocabularyEntryRepository.save(VocabularyEntry.builder()
                .expression(expression)
                .normalizedExpression(expression)
                .reading(reading)
                .normalizedReading(normalizedReading)
                .readingRomaji(blankToNull(request.getReadingRomaji()))
                .meaning(request.getMeaning().trim())
                .wordType(blankToNull(request.getWordType()))
                .jlptLevel(blankToNull(request.getJlptLevel()))
                .exampleJa(blankToNull(request.getExampleJa()))
                .exampleVi(blankToNull(request.getExampleVi()))
                .usageScope(USAGE_SCOPE_TEXTBOOK)
                .dictionaryStatus(STATUS_DRAFT)
                .build());
        linkKanjiComposition(entry);
        return entry;
    }

    /**
     * Điền vocabulary_kanji theo từng chữ Kanji trong cách viết (vị trí tính theo code point, bắt đầu từ 1),
     * như script import đã làm với dữ liệu có sẵn. Chữ chưa có trong bảng kanji thì bỏ qua.
     */
    private void linkKanjiComposition(VocabularyEntry entry) {
        int[] codePoints = entry.getExpression().codePoints().toArray();
        List<String> kanjiCharacters = new ArrayList<>();
        for (int codePoint : codePoints) {
            if (DictionaryUtil.isKanjiCodepoint(codePoint)) kanjiCharacters.add(Character.toString(codePoint));
        }
        if (kanjiCharacters.isEmpty()) return;

        Map<String, Kanji> byCharacter = new LinkedHashMap<>();
        kanjiRepository.findByCharacterIn(kanjiCharacters.stream().distinct().toList())
                .forEach(k -> byCharacter.put(k.getCharacter(), k));
        for (int i = 0; i < codePoints.length; i++) {
            Kanji kanji = byCharacter.get(Character.toString(codePoints[i]));
            if (kanji == null) continue;
            vocabularyKanjiRepository.save(VocabularyKanji.builder()
                    .id(new VocabularyKanji.VocabularyKanjiId(entry.getId(), kanji.getId(), (short) (i + 1)))
                    .vocabularyEntry(entry)
                    .kanji(kanji)
                    .build());
        }
    }

    @Override
    public void detachVocabulary(Long lessonId, Long vocabularyId) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        List<ContentVocabulary> links = contentVocabularyRepository
                .findByLessonIdAndVocabularyId(lessonId, vocabularyId);
        if (links.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Từ vựng này không nằm trong bài học.");
        }
        // Chỉ xoá dòng nối. KHÔNG xoá vocabulary_entry: từ dùng chung cho mọi bài và cho tra cứu từ điển,
        // và fk_content_vocab_vocabulary là ON DELETE CASCADE nên xoá từ sẽ gỡ nó khỏi mọi bài khác.
        contentVocabularyRepository.deleteAll(links);
    }

    // ── Kanji gắn tay vào bài ────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<KanjiResponse> listLessonKanji(Long lessonId) {
        requireLesson(lessonId);
        return toLessonKanjiResponses(lessonKanjiRepository.findByLessonIdWithKanji(lessonId));
    }

    @Override
    public List<KanjiResponse> attachLessonKanji(Long lessonId, LessonKanjiRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());

        // Theo code point chứ không theo char: Kanji mở rộng (CJK Ext B trở đi) nằm ngoài BMP.
        List<String> characters = request.getCharacters().codePoints()
                .filter(DictionaryUtil::isKanjiCodepoint)
                .distinct()
                .mapToObj(Character::toString)
                .toList();
        if (characters.isEmpty()) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chuỗi nhập vào không có chữ Kanji nào.");
        }

        Map<String, Kanji> byCharacter = new LinkedHashMap<>();
        kanjiRepository.findByCharacterIn(characters).forEach(k -> byCharacter.put(k.getCharacter(), k));
        List<String> unknown = characters.stream().filter(c -> !byCharacter.containsKey(c)).toList();
        if (!unknown.isEmpty()) {
            // Không gắn chữ nào cả: gắn một nửa rồi báo lỗi dễ khiến người biên soạn tưởng đã xong.
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Chưa có trong dữ liệu Kanji: " + String.join(" ", unknown));
        }

        Set<Long> attached = lessonKanjiRepository.findByLessonIdWithKanji(lessonId).stream()
                .map(link -> link.getKanji().getId())
                .collect(Collectors.toSet());
        int orderIndex = nextOrderIndex(lessonKanjiRepository.findMaxOrderIndexByLessonId(lessonId));
        for (String character : characters) {
            Kanji kanji = byCharacter.get(character);
            if (!attached.add(kanji.getId())) continue;
            lessonKanjiRepository.save(LessonKanji.builder()
                    .lesson(lesson)
                    .kanji(kanji)
                    .orderIndex(orderIndex++)
                    .build());
        }
        return toLessonKanjiResponses(lessonKanjiRepository.findByLessonIdWithKanji(lessonId));
    }

    @Override
    public void detachLessonKanji(Long lessonId, Long kanjiId) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        LessonKanji link = lessonKanjiRepository.findByLessonIdAndKanjiId(lessonId, kanjiId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Kanji này không được gắn tay vào bài học."));
        // Chỉ xoá dòng nối; bảng kanji là dữ liệu từ điển dùng chung.
        lessonKanjiRepository.delete(link);
    }

    private List<KanjiResponse> toLessonKanjiResponses(List<LessonKanji> links) {
        if (links.isEmpty()) return Collections.emptyList();
        Set<Long> kanjiIds = links.stream().map(link -> link.getKanji().getId()).collect(Collectors.toSet());
        Map<Long, List<KanjiReadingResponse>> readingsByKanji = new LinkedHashMap<>();
        for (KanjiReading reading : kanjiReadingRepository.findByKanjiIdInOrderByKanjiIdAscReadingTypeAscIdAsc(kanjiIds)) {
            readingsByKanji.computeIfAbsent(reading.getKanji().getId(), k -> new ArrayList<>())
                    .add(dictionaryMapper.toKanjiReadingResponse(reading));
        }
        return links.stream()
                .map(link -> dictionaryMapper.toKanjiResponse(link.getKanji(),
                        readingsByKanji.getOrDefault(link.getKanji().getId(), Collections.emptyList()),
                        Collections.emptyList()))
                .toList();
    }

    /**
     * Chỗ chứa từ vựng của bài. Theo đúng quy ước của dữ liệu hiện có: phần EXPLANATION tên "Từ vựng",
     * bên trong là content_item loại READING tên "Danh sách từ vựng". Tái dùng nếu bài đã có, chưa có
     * thì tạo — người biên soạn không phải biết tới hai tầng này khi chỉ muốn thêm một từ.
     */
    private ContentItem resolveVocabularyContainer(Lesson lesson, Long requestedSectionId) {
        LessonSection section;
        if (requestedSectionId != null) {
            section = requireSection(requestedSectionId);
            if (!section.getLesson().getId().equals(lesson.getId())) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Phần nội dung được chọn không thuộc bài học này.");
            }
        } else {
            // Lọc theo cả title vì một bài có nhiều section EXPLANATION ("Từ vựng", "Ngữ pháp"…);
            // lấy cái đầu tiên theo type có thể trúng nhầm phần giải thích ngữ pháp.
            section = lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lesson.getId()).stream()
                    .filter(s -> SECTION_TYPE_EXPLANATION.equals(s.getSectionType())
                            && VOCABULARY_SECTION_TITLE.equals(s.getTitle()))
                    .findFirst()
                    .orElseGet(() -> {
                        LessonSection created = new LessonSection();
                        created.setLesson(lesson);
                        created.setTitle(VOCABULARY_SECTION_TITLE);
                        created.setSectionType(SECTION_TYPE_EXPLANATION);
                        created.setOrderIndex(nextOrderIndex(
                                lessonSectionRepository.findMaxOrderIndexByLessonId(lesson.getId())));
                        return lessonSectionRepository.save(created);
                    });
        }
        LessonSection target = section;
        return contentItemRepository.findBySectionIdAndContentType(target.getId(), CONTENT_TYPE_READING).stream()
                .findFirst()
                .orElseGet(() -> {
                    ContentItem created = new ContentItem();
                    created.setSection(target);
                    created.setContentType(CONTENT_TYPE_READING);
                    created.setTitle(VOCABULARY_ITEM_TITLE);
                    created.setOrderIndex(nextOrderIndex(
                            contentItemRepository.findMaxOrderIndexBySectionId(target.getId())));
                    return contentItemRepository.save(created);
                });
    }

    // ── Hội thoại: vai ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DialogueRoleResponse> listDialogueRoles(Long lessonId) {
        requireLesson(lessonId);
        return dialogueRoleRepository.findByLessonIdOrderByCodeAsc(lessonId).stream()
                .map(dictionaryMapper::toDialogueRoleResponse)
                .toList();
    }

    @Override
    public DialogueRoleResponse createDialogueRole(Long lessonId, DialogueRoleUpsertRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        String code = request.getCode().trim();
        if (dialogueRoleRepository.existsByLessonIdAndCode(lessonId, code)) {
            throw new ApiException(ErrorCode.CONFLICT, "Mã vai \"" + code + "\" đã có trong bài học này.");
        }
        DialogueRole role = new DialogueRole();
        role.setLesson(lesson);
        role.setCode(code);
        applyDialogueRole(role, request);
        return dictionaryMapper.toDialogueRoleResponse(dialogueRoleRepository.save(role));
    }

    @Override
    public DialogueRoleResponse updateDialogueRole(Long roleId, DialogueRoleUpsertRequest request) {
        DialogueRole role = requireDialogueRole(roleId);
        requireNotArchived(role.getLesson().getUnit().getTextbook());
        String code = request.getCode().trim();
        if (!code.equals(role.getCode())
                && dialogueRoleRepository.existsByLessonIdAndCode(role.getLesson().getId(), code)) {
            throw new ApiException(ErrorCode.CONFLICT, "Mã vai \"" + code + "\" đã có trong bài học này.");
        }
        role.setCode(code);
        applyDialogueRole(role, request);
        return dictionaryMapper.toDialogueRoleResponse(dialogueRoleRepository.save(role));
    }

    private void applyDialogueRole(DialogueRole role, DialogueRoleUpsertRequest request) {
        role.setNameVi(request.getNameVi().trim());
        role.setNameJa(blankToNull(request.getNameJa()));
        role.setDescription(blankToNull(request.getDescription()));
        String voiceCode = blankToNull(request.getVoiceCode());
        if (voiceCode == null) {
            role.setVoice(null);
        } else {
            role.setVoice(contentVoiceRepository.findById(voiceCode)
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                            "Không tìm thấy giọng đọc có mã " + request.getVoiceCode())));
        }
    }

    @Override
    public void deleteDialogueRole(Long roleId) {
        DialogueRole role = requireDialogueRole(roleId);
        requireNotArchived(role.getLesson().getUnit().getTextbook());
        long lines = dialogueRoleRepository.countLinesByRoleId(roleId);
        if (lines > 0) {
            // fk_dialogue_detail_role là ON DELETE SET NULL: xoá thẳng thì các lời này mất tên người nói
            // mà không có lỗi nào hiện ra, bong bóng ở màn hình học hiện trống.
            throw new ApiException(ErrorCode.CONFLICT,
                    "Vai \"" + role.getNameVi() + "\" còn " + lines + " lời thoại nên không xoá được. "
                            + "Hãy đổi các lời đó sang vai khác, hoặc xoá chúng trước.");
        }
        dialogueRoleRepository.delete(role);
    }

    // ── Hội thoại: đoạn và lời ───────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<DialogueSceneResponse> listDialogueScenes(Long lessonId) {
        requireLesson(lessonId);
        return lessonSectionRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lessonId).stream()
                .filter(ContentAdminServiceImpl::isDialogueScene)
                .map(this::toSceneResponse)
                .toList();
    }

    @Override
    public DialogueSceneResponse createDialogueScene(Long lessonId, DialogueSceneUpsertRequest request) {
        Lesson lesson = requireLesson(lessonId);
        requireNotArchived(lesson.getUnit().getTextbook());
        LessonSection section = new LessonSection();
        section.setLesson(lesson);
        // Tiền tố do service thêm, theo đúng quy ước của dữ liệu hiện có ("Hội thoại: <tên cảnh>"),
        // nhờ đó phân biệt được đoạn hội thoại với phần "Câu ví dụ" — cả hai đều là section EXAMPLE.
        section.setTitle(DIALOGUE_SECTION_PREFIX + request.getTitle().trim());
        section.setSectionType(SECTION_TYPE_EXAMPLE);
        section.setOrderIndex(nextOrderIndex(lessonSectionRepository.findMaxOrderIndexByLessonId(lessonId)));
        return toSceneResponse(lessonSectionRepository.save(section));
    }

    @Override
    public DialogueLineResponse createDialogueLine(Long sectionId, DialogueLineUpsertRequest request) {
        LessonSection section = requireSection(sectionId);
        requireNotArchived(section.getLesson().getUnit().getTextbook());

        ContentItem item = new ContentItem();
        item.setSection(section);
        item.setContentType(CONTENT_TYPE_LISTENING);
        item.setOrderIndex(nextOrderIndex(contentItemRepository.findMaxOrderIndexBySectionId(sectionId)));
        applyDialogueLine(item, section, request);
        return toDialogueLineResponse(item);
    }

    @Override
    public DialogueLineResponse updateDialogueLine(Long contentItemId, DialogueLineUpsertRequest request) {
        ContentItem item = requireContentItem(contentItemId);
        LessonSection section = item.getSection();
        requireNotArchived(section.getLesson().getUnit().getTextbook());
        applyDialogueLine(item, section, request);
        return toDialogueLineResponse(item);
    }

    /**
     * Ghi một lời thoại: content_item + dialogue_line_detail + toàn bộ cụm.
     * Cụm luôn ghi đè cả bộ, vì bất biến "ghép ra đúng câu" chỉ kiểm được trên toàn bộ danh sách.
     */
    private void applyDialogueLine(ContentItem item, LessonSection section, DialogueLineUpsertRequest request) {
        String textJa = request.getTextJa().trim();
        List<DialogueChunkInput> chunks = request.getChunks() == null ? List.of() : request.getChunks();
        requireChunksRebuildTheLine(textJa, chunks);

        item.setTitle(request.getTitle().trim());
        item.setContent(textJa);
        item.setTranslatedText(blankToNull(request.getTextVi()));
        // saveAndFlush chứ không save: DialogueLineDetail dùng @MapsId nên phải có id của content_item
        // trước khi tạo detail, nếu không Hibernate ném "null identifier".
        ContentItem saved = contentItemRepository.saveAndFlush(item);

        DialogueRole role = null;
        if (request.getRoleId() != null) {
            role = requireDialogueRole(request.getRoleId());
            if (!role.getLesson().getId().equals(section.getLesson().getId())) {
                throw new ApiException(ErrorCode.BAD_REQUEST, "Vai được chọn không thuộc bài học này.");
            }
        }
        DialogueLineDetail detail = dialogueLineDetailRepository.findById(saved.getId())
                .orElseGet(DialogueLineDetail::new);
        // KHÔNG gán contentItemId thủ công: @MapsId lấy id từ chính quan hệ contentItem.
        detail.setContentItem(saved);
        detail.setRole(role);
        detail.setBubbleSide(blankToNull(request.getBubbleSide()));
        detail.setSceneCode(blankToNull(request.getSceneCode()));
        dialogueLineDetailRepository.save(detail);

        dialogueLineChunkRepository.deleteByContentItemId(saved.getId());
        dialogueLineChunkRepository.flush();
        int order = 1;
        for (DialogueChunkInput input : chunks) {
            DialogueLineChunk chunk = new DialogueLineChunk();
            chunk.setContentItem(saved);
            chunk.setOrderIndex(order++);
            chunk.setSurface(input.getSurface());
            chunk.setMeaningVi(blankToNull(input.getMeaningVi()));
            dialogueLineChunkRepository.save(chunk);
        }
    }

    /**
     * LỚP CHẶN Ở SERVICE cho bất biến quan trọng nhất của phần hội thoại.
     * <p>
     * textbook-lesson.js dựng lại lời thoại bằng {@code chunks.map(chunkSpan).join('')} và chỉ dùng
     * content_item.content khi KHÔNG có cụm nào. Nghĩa là khi đã có cụm, thứ học viên đọc hoàn toàn do
     * các cụm quyết định. Nếu ghép cụm không ra đúng nguyên câu thì câu hiển thị khác câu đã lưu và khác
     * cả audio — sai trong im lặng, không lỗi, không cảnh báo. Nên chặn ngay từ cửa, và thông báo đưa
     * CẢ HAI chuỗi để người biên soạn thấy lệch ở đâu.
     */
    private static void requireChunksRebuildTheLine(String textJa, List<DialogueChunkInput> chunks) {
        if (chunks.isEmpty()) return;
        String joined = chunks.stream().map(DialogueChunkInput::getSurface).collect(Collectors.joining());
        if (!joined.equals(textJa)) {
            throw new ApiException(ErrorCode.BAD_REQUEST,
                    "Ghép các cụm lại không ra đúng lời thoại.\nLời thoại: " + textJa + "\nGhép từ cụm: " + joined);
        }
    }

    @Override
    public void deleteDialogueLine(Long contentItemId) {
        ContentItem item = requireContentItem(contentItemId);
        requireNotArchived(item.getSection().getLesson().getUnit().getTextbook());
        // detail và chunk đều cascade theo content_item nên xoá một lệnh là sạch, không sót dữ liệu rác.
        contentItemRepository.delete(item);
    }

    @Override
    public List<DialogueLineResponse> reorderDialogueLines(Long sectionId, ReorderRequest request) {
        LessonSection section = requireSection(sectionId);
        requireNotArchived(section.getLesson().getUnit().getTextbook());
        List<ContentItem> lines = contentItemRepository
                .findBySectionIdAndContentTypeOrderByOrderIndexAscIdAsc(sectionId, CONTENT_TYPE_LISTENING);
        List<ContentItem> ordered = ContentOrdering.sortByRequestedIds(
                lines, ContentItem::getId, request.getOrderedIds(), "lời thoại");

        ContentOrdering.renumber(ordered, ContentItem::getOrderIndex, ContentItem::setOrderIndex,
                contentItemRepository::saveAllAndFlush);
        return ordered.stream().map(this::toDialogueLineResponse).toList();
    }

    /** Đoạn hội thoại nhận ra bằng title: cùng section_type EXAMPLE với phần "Câu ví dụ". */
    private static boolean isDialogueScene(LessonSection section) {
        return SECTION_TYPE_EXAMPLE.equals(section.getSectionType())
                && section.getTitle() != null && section.getTitle().startsWith(DIALOGUE_SECTION_PREFIX);
    }

    private DialogueSceneResponse toSceneResponse(LessonSection section) {
        List<DialogueLineResponse> lines = contentItemRepository
                .findBySectionIdAndContentTypeOrderByOrderIndexAscIdAsc(section.getId(), CONTENT_TYPE_LISTENING)
                .stream().map(this::toDialogueLineResponse).toList();
        return DialogueSceneResponse.builder()
                .sectionId(section.getId())
                .title(section.getTitle().startsWith(DIALOGUE_SECTION_PREFIX)
                        ? section.getTitle().substring(DIALOGUE_SECTION_PREFIX.length())
                        : section.getTitle())
                .sectionTitle(section.getTitle())
                .orderIndex(section.getOrderIndex())
                .lines(lines)
                .build();
    }

    private DialogueLineResponse toDialogueLineResponse(ContentItem item) {
        DialogueLineDetail detail = dialogueLineDetailRepository.findById(item.getId()).orElse(null);
        List<DialogueChunkResponse> chunks = dialogueLineChunkRepository
                .findByContentItemIdOrderByOrderIndexAsc(item.getId()).stream()
                .map(dictionaryMapper::toDialogueChunkResponse)
                .toList();
        return dictionaryMapper.toEditableDialogueLineResponse(item, detail, chunks);
    }

    // ── Tag ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<TagResponse> listTagsByType(String tagType) {
        return tagRepository.findByTagTypeOrderByNameAsc(tagType).stream()
                .map(dictionaryMapper::toTagResponse)
                .toList();
    }

    @Override
    public TagResponse createTag(TagUpsertRequest request) {
        String code = request.getCode().trim();
        // uk_tag_code là UNIQUE: kiểm trước để trả 409 có thông báo, thay vì để DB ném lỗi ràng buộc.
        if (tagRepository.existsByCode(code)) {
            throw new ApiException(ErrorCode.CONFLICT, "Mã tag \"" + code + "\" đã tồn tại.");
        }
        Tag tag = new Tag();
        tag.setCode(code);
        tag.setName(request.getName().trim());
        tag.setTagType(request.getTagType());
        return dictionaryMapper.toTagResponse(tagRepository.save(tag));
    }

    // ── Helper dùng chung ────────────────────────────────────────────────────

    private Textbook requireTextbook(Long textbookId) {
        return textbookRepository.findById(textbookId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Textbook not found: " + textbookId));
    }

    private Unit requireUnit(Long unitId) {
        return unitRepository.findById(unitId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Unit not found: " + unitId));
    }

    /** Dùng findWithUnitById để có sẵn unit.textbook cho requireNotArchived, không phát sinh query phụ. */
    private Lesson requireLesson(Long lessonId) {
        return lessonRepository.findWithUnitById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));
    }

    private LessonSection requireSection(Long sectionId) {
        return lessonSectionRepository.findById(sectionId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Section not found: " + sectionId));
    }

    private DialogueRole requireDialogueRole(Long roleId) {
        return dialogueRoleRepository.findById(roleId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Dialogue role not found: " + roleId));
    }

    private GrammarPoint requireGrammarPoint(Long grammarPointId) {
        return grammarPointRepository.findById(grammarPointId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Grammar point not found: " + grammarPointId));
    }

    private ContentItem requireContentItem(Long contentItemId) {
        return contentItemRepository.findById(contentItemId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Content item not found: " + contentItemId));
    }

    /**
     * Không cho thêm/sửa nội dung bên trong giáo trình đã lưu trữ — nếu cho thì sẽ có bài mới nằm trong
     * giáo trình mà không ai thấy, đúng kiểu dữ liệu mồ côi mà ticket muốn chặn. Muốn sửa thì khôi phục
     * giáo trình về DRAFT trước.
     */
    private void requireNotArchived(Textbook textbook) {
        if (STATUS_ARCHIVED.equals(textbook.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Giáo trình \"" + textbook.getTitle() + "\" đã được lưu trữ. "
                            + "Hãy khôi phục giáo trình trước khi thêm hoặc sửa nội dung bên trong.");
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Thêm vào cuối. Cha đang rỗng thì bắt đầu từ 1, cùng chuẩn 1-based với dữ liệu seed và với renumber(). */
    private static int nextOrderIndex(Integer currentMax) {
        return currentMax == null ? 1 : currentMax + 1;
    }
}
