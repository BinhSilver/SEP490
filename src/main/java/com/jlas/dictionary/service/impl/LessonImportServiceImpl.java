package com.jlas.dictionary.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.DialogueLineUpsertRequest;
import com.jlas.dictionary.dto.DialogueRoleResponse;
import com.jlas.dictionary.dto.DialogueRoleUpsertRequest;
import com.jlas.dictionary.dto.DialogueSceneResponse;
import com.jlas.dictionary.dto.DialogueSceneUpsertRequest;
import com.jlas.dictionary.dto.ExampleSentenceUpsertRequest;
import com.jlas.dictionary.dto.GrammarPointUpsertRequest;
import com.jlas.dictionary.dto.LessonImportResult;
import com.jlas.dictionary.dto.LessonImportResult.RowError;
import com.jlas.dictionary.dto.LessonKanjiRequest;
import com.jlas.dictionary.dto.VocabularyCreateRequest;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.entity.Kanji;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.repository.ContentVocabularyRepository;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.KanjiRepository;
import com.jlas.dictionary.repository.LessonKanjiRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.service.ContentAdminService;
import com.jlas.dictionary.service.LessonImportService;
import com.jlas.dictionary.util.DictionaryUtil;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Import nội dung bài học từ Excel. Ba pha: đọc file → kiểm tra toàn bộ (không ghi gì) → lưu trong một
 * transaction bằng chính các thao tác của {@link ContentAdminService}, nên luật nghiệp vụ (bài lưu trữ, cụm
 * hội thoại, chỗ chứa từ vựng...) giữ một chỗ duy nhất. Pha kiểm tra đã loại các trường hợp mà
 * ContentAdminService sẽ từ chối, để lỗi đến tay người biên soạn kèm sheet + số dòng thay vì một lỗi chung.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LessonImportServiceImpl implements LessonImportService {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_ROWS_PER_SHEET = 1000;
    /** LessonKanjiRequest.characters giới hạn 200 ký tự; một Kanji tối đa 2 char UTF-16. */
    private static final int KANJI_PER_REQUEST = 100;
    private static final String STATUS_ARCHIVED = "ARCHIVED";

    private static final String SHEET_VOCABULARY = "TuVung";
    private static final String SHEET_GRAMMAR = "NguPhap";
    private static final String SHEET_EXAMPLE = "ViDu";
    private static final String SHEET_DIALOGUE = "HoiThoai";
    private static final String SHEET_KANJI = "Kanji";
    private static final String SHEET_GUIDE = "HuongDan";

    private static final List<String> VOCABULARY_HEADER = List.of("Từ *", "Cách đọc *", "Romaji", "Nghĩa *",
            "Loại từ", "JLPT", "Ví dụ (tiếng Nhật)", "Ví dụ (tiếng Việt)");
    private static final List<String> GRAMMAR_HEADER = List.of("Mẫu ngữ pháp *", "Nghĩa *", "Ví dụ ngắn", "Ghi chú");
    private static final List<String> EXAMPLE_HEADER = List.of("Mẫu ngữ pháp *", "Câu tiếng Nhật *",
            "Dịch tiếng Việt", "Tiêu đề");
    private static final List<String> DIALOGUE_HEADER = List.of("Đoạn hội thoại *", "Mã vai *",
            "Tên vai (tiếng Việt) *", "Tên vai (tiếng Nhật)", "Lời thoại tiếng Nhật *", "Dịch tiếng Việt",
            "Bên (trai/phai)");
    private static final List<String> KANJI_HEADER = List.of("Chữ Kanji *");

    private final LessonRepository lessonRepository;
    private final VocabularyEntryRepository vocabularyEntryRepository;
    private final ContentVocabularyRepository contentVocabularyRepository;
    private final GrammarPointRepository grammarPointRepository;
    private final KanjiRepository kanjiRepository;
    private final LessonKanjiRepository lessonKanjiRepository;
    private final ContentAdminService contentAdminService;

    /** Một dòng dữ liệu đã đọc; {@code number} là số dòng như Excel hiển thị. */
    record SheetRow(int number, List<String> cells) {
        /** Ô đã strip; ô trống hoặc ngoài phạm vi trả null. */
        String get(int index) {
            if (index >= cells.size()) return null;
            String value = cells.get(index);
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    private record PlannedVocabulary(VocabularyCreateRequest request, boolean inDictionary) {}

    private record PlannedExample(String grammarKey, ExampleSentenceUpsertRequest request) {}

    private record PlannedLine(String sceneKey, String roleCode, DialogueLineUpsertRequest request) {}

    /** Mọi thứ sẽ ghi, đã kiểm tra xong. */
    private static class ImportPlan {
        final List<PlannedVocabulary> vocabularies = new ArrayList<>();
        int vocabularySkipped;
        final List<String> kanjiCharacters = new ArrayList<>();
        int kanjiSkipped;
        /** Khoá mẫu → id mẫu đã có trong bài (dùng cho câu ví dụ). */
        final Map<String, Long> existingGrammar = new HashMap<>();
        final Map<String, GrammarPointUpsertRequest> grammarToCreate = new LinkedHashMap<>();
        int grammarSkipped;
        final List<PlannedExample> examples = new ArrayList<>();
        final Map<String, Long> existingRoles = new HashMap<>();
        final Map<String, DialogueRoleUpsertRequest> rolesToCreate = new LinkedHashMap<>();
        final Map<String, Long> existingScenes = new HashMap<>();
        final Map<String, String> scenesToCreate = new LinkedHashMap<>();
        final List<PlannedLine> lines = new ArrayList<>();
    }

    // ── Import ───────────────────────────────────────────────────────────────

    @Override
    public LessonImportResult importLesson(Long lessonId, MultipartFile file) {
        return importLesson(lessonId, file, false);
    }

    @Override
    public LessonImportResult importLesson(Long lessonId, MultipartFile file, boolean dryRun) {
        Lesson lesson = lessonRepository.findWithUnitById(lessonId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Lesson not found: " + lessonId));
        if (STATUS_ARCHIVED.equals(lesson.getUnit().getTextbook().getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "Giáo trình đã lưu trữ, không nhập thêm nội dung được.");
        }

        WorkbookData data = readWorkbook(readBytes(file));
        Map<String, List<SheetRow>> sheets = data.rows();
        // Lỗi tiêu đề (thiếu cột bắt buộc / không thấy dòng tiêu đề): báo theo sheet, chưa đọc dữ liệu của sheet đó.
        if (!data.headerErrors().isEmpty()) {
            return LessonImportResult.builder().imported(false).dryRun(dryRun).errors(data.headerErrors()).build();
        }
        if (sheets.values().stream().allMatch(List::isEmpty)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, emptyFileMessage(data));
        }

        List<RowError> errors = new ArrayList<>();
        ImportPlan plan = new ImportPlan();
        planVocabulary(lessonId, sheets.get(SHEET_VOCABULARY), plan, errors);
        planKanji(lessonId, sheets.get(SHEET_KANJI), plan, errors);
        planGrammar(lessonId, sheets.get(SHEET_GRAMMAR), plan, errors);
        planExamples(sheets.get(SHEET_EXAMPLE), plan, errors);
        planDialogue(lessonId, sheets.get(SHEET_DIALOGUE), plan, errors);

        if (!errors.isEmpty()) {
            return LessonImportResult.builder().imported(false).dryRun(dryRun).errors(errors).build();
        }
        return dryRun ? preview(plan) : apply(lessonId, plan);
    }

    /** Chạy thử: cùng các bộ đếm như apply() nhưng tính từ kế hoạch, không gọi thao tác ghi nào. */
    private static LessonImportResult preview(ImportPlan plan) {
        int attached = (int) plan.vocabularies.stream().filter(PlannedVocabulary::inDictionary).count();
        return LessonImportResult.builder()
                .imported(false)
                .dryRun(true)
                .vocabularyCreated(plan.vocabularies.size() - attached)
                .vocabularyAttached(attached)
                .vocabularySkipped(plan.vocabularySkipped)
                .kanjiAttached(plan.kanjiCharacters.size())
                .kanjiSkipped(plan.kanjiSkipped)
                .grammarCreated(plan.grammarToCreate.size())
                .grammarSkipped(plan.grammarSkipped)
                .examplesCreated(plan.examples.size())
                .dialogueRolesCreated(plan.rolesToCreate.size())
                .dialogueScenesCreated(plan.scenesToCreate.size())
                .dialogueLinesCreated(plan.lines.size())
                .build();
    }

    private LessonImportResult apply(Long lessonId, ImportPlan plan) {
        LessonImportResult result = LessonImportResult.builder()
                .imported(true)
                .vocabularySkipped(plan.vocabularySkipped)
                .kanjiSkipped(plan.kanjiSkipped)
                .grammarSkipped(plan.grammarSkipped)
                .build();

        for (PlannedVocabulary vocabulary : plan.vocabularies) {
            contentAdminService.createVocabulary(lessonId, vocabulary.request());
            if (vocabulary.inDictionary()) result.setVocabularyAttached(result.getVocabularyAttached() + 1);
            else result.setVocabularyCreated(result.getVocabularyCreated() + 1);
        }

        for (int from = 0; from < plan.kanjiCharacters.size(); from += KANJI_PER_REQUEST) {
            LessonKanjiRequest request = new LessonKanjiRequest();
            request.setCharacters(String.join("", plan.kanjiCharacters.subList(from,
                    Math.min(from + KANJI_PER_REQUEST, plan.kanjiCharacters.size()))));
            contentAdminService.attachLessonKanji(lessonId, request);
        }
        result.setKanjiAttached(plan.kanjiCharacters.size());

        Map<String, Long> grammarIds = new HashMap<>(plan.existingGrammar);
        for (Map.Entry<String, GrammarPointUpsertRequest> entry : plan.grammarToCreate.entrySet()) {
            grammarIds.put(entry.getKey(), contentAdminService.createGrammarPoint(lessonId, entry.getValue()).getId());
        }
        result.setGrammarCreated(plan.grammarToCreate.size());
        for (PlannedExample example : plan.examples) {
            contentAdminService.createExample(grammarIds.get(example.grammarKey()), example.request());
        }
        result.setExamplesCreated(plan.examples.size());

        Map<String, Long> roleIds = new HashMap<>(plan.existingRoles);
        for (Map.Entry<String, DialogueRoleUpsertRequest> entry : plan.rolesToCreate.entrySet()) {
            roleIds.put(entry.getKey(), contentAdminService.createDialogueRole(lessonId, entry.getValue()).getId());
        }
        result.setDialogueRolesCreated(plan.rolesToCreate.size());
        Map<String, Long> sceneIds = new HashMap<>(plan.existingScenes);
        for (Map.Entry<String, String> entry : plan.scenesToCreate.entrySet()) {
            DialogueSceneUpsertRequest request = new DialogueSceneUpsertRequest();
            request.setTitle(entry.getValue());
            sceneIds.put(entry.getKey(), contentAdminService.createDialogueScene(lessonId, request).getSectionId());
        }
        result.setDialogueScenesCreated(plan.scenesToCreate.size());
        for (PlannedLine line : plan.lines) {
            line.request().setRoleId(roleIds.get(line.roleCode()));
            contentAdminService.createDialogueLine(sceneIds.get(line.sceneKey()), line.request());
        }
        result.setDialogueLinesCreated(plan.lines.size());
        return result;
    }

    // ── Kiểm tra từng sheet ──────────────────────────────────────────────────

    private void planVocabulary(Long lessonId, List<SheetRow> rows, ImportPlan plan, List<RowError> errors) {
        if (rows.isEmpty()) return;
        Set<Long> inLesson = new HashSet<>(contentVocabularyRepository.findVocabularyIdsByLessonId(lessonId));
        Set<String> seen = new HashSet<>();
        for (SheetRow row : rows) {
            int before = errors.size();
            String expression = required(row, 0, VOCABULARY_HEADER, 255, SHEET_VOCABULARY, errors);
            String reading = required(row, 1, VOCABULARY_HEADER, 255, SHEET_VOCABULARY, errors);
            String romaji = optional(row, 2, VOCABULARY_HEADER, 255, SHEET_VOCABULARY, errors);
            String meaning = required(row, 3, VOCABULARY_HEADER, Integer.MAX_VALUE, SHEET_VOCABULARY, errors);
            String wordType = optional(row, 4, VOCABULARY_HEADER, 50, SHEET_VOCABULARY, errors);
            String jlpt = row.get(5) == null ? null : row.get(5).toUpperCase(Locale.ROOT);
            if (jlpt != null && !jlpt.matches("N[1-5]")) {
                errors.add(error(SHEET_VOCABULARY, row, "Cột JLPT phải là N5, N4, N3, N2 hoặc N1"));
            }
            if (errors.size() > before) continue;

            String normalizedExpression = DictionaryUtil.normalizeText(expression);
            String normalizedReading = DictionaryUtil.normalizeReading(reading);
            if (!seen.add(normalizedExpression + '\u0000' + normalizedReading)) {
                plan.vocabularySkipped++;
                continue;
            }
            Optional<VocabularyEntry> existing = vocabularyEntryRepository
                    .findByNormalizedPair(normalizedExpression, normalizedReading);
            if (existing.isPresent() && inLesson.contains(existing.get().getId())) {
                plan.vocabularySkipped++;
                continue;
            }

            VocabularyCreateRequest request = new VocabularyCreateRequest();
            request.setExpression(expression);
            request.setReading(reading);
            request.setReadingRomaji(romaji);
            request.setMeaning(meaning);
            request.setWordType(wordType);
            request.setJlptLevel(jlpt);
            request.setExampleJa(row.get(6));
            request.setExampleVi(row.get(7));
            plan.vocabularies.add(new PlannedVocabulary(request, existing.isPresent()));
        }
    }

    private void planKanji(Long lessonId, List<SheetRow> rows, ImportPlan plan, List<RowError> errors) {
        if (rows.isEmpty()) return;
        // Ký tự → dòng đầu tiên chứa nó, để báo lỗi đúng chỗ.
        Map<String, Integer> firstRow = new LinkedHashMap<>();
        for (SheetRow row : rows) {
            String value = required(row, 0, KANJI_HEADER, Integer.MAX_VALUE, SHEET_KANJI, errors);
            if (value == null) continue;
            List<String> characters = value.codePoints()
                    .filter(DictionaryUtil::isKanjiCodepoint)
                    .mapToObj(Character::toString)
                    .toList();
            if (characters.isEmpty()) {
                errors.add(error(SHEET_KANJI, row, "Ô \"" + value + "\" không có chữ Kanji nào"));
                continue;
            }
            for (String character : characters) {
                if (firstRow.putIfAbsent(character, row.number()) != null) plan.kanjiSkipped++;
            }
        }
        if (firstRow.isEmpty()) return;

        Set<String> known = kanjiRepository.findByCharacterIn(new ArrayList<>(firstRow.keySet())).stream()
                .map(Kanji::getCharacter)
                .collect(Collectors.toSet());
        Set<String> attached = lessonKanjiRepository.findByLessonIdWithKanji(lessonId).stream()
                .map(link -> link.getKanji().getCharacter())
                .collect(Collectors.toSet());
        for (Map.Entry<String, Integer> entry : firstRow.entrySet()) {
            if (!known.contains(entry.getKey())) {
                errors.add(new RowError(SHEET_KANJI, entry.getValue(),
                        "Chữ " + entry.getKey() + " chưa có trong dữ liệu Kanji"));
            } else if (attached.contains(entry.getKey())) {
                plan.kanjiSkipped++;
            } else {
                plan.kanjiCharacters.add(entry.getKey());
            }
        }
    }

    private void planGrammar(Long lessonId, List<SheetRow> rows, ImportPlan plan, List<RowError> errors) {
        for (GrammarPoint point : grammarPointRepository.findByLessonIdOrderByOrderIndexAscIdAsc(lessonId)) {
            plan.existingGrammar.putIfAbsent(key(point.getPattern()), point.getId());
        }
        for (SheetRow row : rows) {
            int before = errors.size();
            String pattern = required(row, 0, GRAMMAR_HEADER, 500, SHEET_GRAMMAR, errors);
            String meaning = required(row, 1, GRAMMAR_HEADER, Integer.MAX_VALUE, SHEET_GRAMMAR, errors);
            if (errors.size() > before) continue;

            String key = key(pattern);
            if (plan.existingGrammar.containsKey(key) || plan.grammarToCreate.containsKey(key)) {
                plan.grammarSkipped++;
                continue;
            }
            GrammarPointUpsertRequest request = new GrammarPointUpsertRequest();
            request.setPattern(pattern);
            request.setMeaning(meaning);
            request.setExample(row.get(2));
            request.setNote(row.get(3));
            plan.grammarToCreate.put(key, request);
        }
    }

    private void planExamples(List<SheetRow> rows, ImportPlan plan, List<RowError> errors) {
        for (SheetRow row : rows) {
            int before = errors.size();
            String pattern = required(row, 0, EXAMPLE_HEADER, 500, SHEET_EXAMPLE, errors);
            String textJa = required(row, 1, EXAMPLE_HEADER, Integer.MAX_VALUE, SHEET_EXAMPLE, errors);
            String title = optional(row, 3, EXAMPLE_HEADER, 255, SHEET_EXAMPLE, errors);
            if (errors.size() > before) continue;

            String key = key(pattern);
            if (!plan.existingGrammar.containsKey(key) && !plan.grammarToCreate.containsKey(key)) {
                errors.add(error(SHEET_EXAMPLE, row, "Mẫu \"" + pattern
                        + "\" chưa có trong bài, cũng không có trong sheet " + SHEET_GRAMMAR));
                continue;
            }
            ExampleSentenceUpsertRequest request = new ExampleSentenceUpsertRequest();
            request.setTitle(title != null ? title : truncate(textJa, 255));
            request.setTextJa(textJa);
            request.setTextVi(row.get(2));
            plan.examples.add(new PlannedExample(key, request));
        }
    }

    private void planDialogue(Long lessonId, List<SheetRow> rows, ImportPlan plan, List<RowError> errors) {
        if (rows.isEmpty()) return;
        for (DialogueRoleResponse role : contentAdminService.listDialogueRoles(lessonId)) {
            plan.existingRoles.put(role.getCode(), role.getId());
        }
        for (DialogueSceneResponse scene : contentAdminService.listDialogueScenes(lessonId)) {
            plan.existingScenes.putIfAbsent(key(scene.getTitle()), scene.getSectionId());
        }
        for (SheetRow row : rows) {
            int before = errors.size();
            String sceneTitle = required(row, 0, DIALOGUE_HEADER, 200, SHEET_DIALOGUE, errors);
            String roleCode = required(row, 1, DIALOGUE_HEADER, 64, SHEET_DIALOGUE, errors);
            String roleNameVi = optional(row, 2, DIALOGUE_HEADER, 255, SHEET_DIALOGUE, errors);
            String roleNameJa = optional(row, 3, DIALOGUE_HEADER, 255, SHEET_DIALOGUE, errors);
            String textJa = required(row, 4, DIALOGUE_HEADER, Integer.MAX_VALUE, SHEET_DIALOGUE, errors);
            String side = bubbleSide(row.get(6));
            if (row.get(6) != null && side == null) {
                errors.add(error(SHEET_DIALOGUE, row, "Cột Bên chỉ nhận \"trai\" hoặc \"phai\""));
            }
            if (errors.size() > before) continue;

            // Vai mới cần tên; vai đã có trong bài (hoặc khai báo ở dòng trước) thì cột tên có thể bỏ trống.
            if (!plan.existingRoles.containsKey(roleCode) && !plan.rolesToCreate.containsKey(roleCode)) {
                if (roleNameVi == null) {
                    errors.add(error(SHEET_DIALOGUE, row, "Vai \"" + roleCode
                            + "\" chưa có trong bài: cần cột \"" + DIALOGUE_HEADER.get(2) + "\""));
                    continue;
                }
                DialogueRoleUpsertRequest role = new DialogueRoleUpsertRequest();
                role.setCode(roleCode);
                role.setNameVi(roleNameVi);
                role.setNameJa(roleNameJa);
                plan.rolesToCreate.put(roleCode, role);
            }

            String sceneKey = key(sceneTitle);
            if (!plan.existingScenes.containsKey(sceneKey)) plan.scenesToCreate.putIfAbsent(sceneKey, sceneTitle);

            DialogueLineUpsertRequest line = new DialogueLineUpsertRequest();
            line.setTitle(truncate(textJa, 255));
            line.setTextJa(textJa);
            line.setTextVi(row.get(5));
            line.setBubbleSide(side);
            plan.lines.add(new PlannedLine(sceneKey, roleCode, line));
        }
    }

    // ── Đọc file ─────────────────────────────────────────────────────────────

    private static byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.BAD_REQUEST, "Chưa chọn file.");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx")) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Chỉ nhận file Excel .xlsx (hãy dùng file mẫu).");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "File không được vượt quá 2MB.");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không đọc được file tải lên.");
        }
    }

    /** Số dòng đầu mỗi sheet được dò để tìm dòng tiêu đề (dòng tiêu đề có thể không nằm ở dòng 1). */
    private static final int HEADER_SCAN_ROWS = 10;
    private static final List<String> DATA_SHEETS = List.of(SHEET_VOCABULARY, SHEET_GRAMMAR, SHEET_EXAMPLE, SHEET_DIALOGUE, SHEET_KANJI);
    private static final Map<String, List<String>> HEADERS = Map.of(SHEET_VOCABULARY, VOCABULARY_HEADER,
            SHEET_GRAMMAR, GRAMMAR_HEADER, SHEET_EXAMPLE, EXAMPLE_HEADER, SHEET_DIALOGUE, DIALOGUE_HEADER, SHEET_KANJI, KANJI_HEADER);
    /** Tên sheet được nhận thêm (ngoài tên trong file mẫu); so khớp qua sheetKey nên không phân biệt hoa thường / dấu / khoảng trắng. */
    private static final Map<String, List<String>> SHEET_ALIASES = Map.of(
            SHEET_VOCABULARY, List.of("Từ vựng", "Tu vung", "Vocabulary"),
            SHEET_GRAMMAR, List.of("Ngữ pháp", "Ngu phap", "Grammar"),
            SHEET_EXAMPLE, List.of("Ví dụ", "Vi du", "Câu ví dụ", "Examples", "Example"),
            SHEET_DIALOGUE, List.of("Hội thoại", "Hoi thoai", "Dialogue", "Dialog"),
            SHEET_KANJI, List.of("Chữ Kanji", "Hán tự", "Han tu"));

    /**
     * Kết quả đọc file: dữ liệu đã đưa về đúng thứ tự cột của file mẫu (cột trong file được nhận theo TÊN tiêu đề, không theo vị trí),
     * kèm thông tin để báo lỗi rõ: sheet tìm thấy (tên sheet chuẩn → tên trong file), sheet không nhận ra, sheet chỉ có tiêu đề,
     * lỗi tiêu đề (thiếu cột bắt buộc, không thấy dòng tiêu đề).
     */
    record WorkbookData(Map<String, List<SheetRow>> rows, Map<String, String> sheetTitles, List<String> unknownSheets,
                        List<String> headerOnlySheets, List<RowError> headerErrors) {}

    /**
     * Đọc các sheet dữ liệu. Khoan dung: tên sheet theo sheetKey + bí danh ("Từ vựng" → TuVung); dòng tiêu đề tự dò trong
     * HEADER_SCAN_ROWS dòng đầu; cột nhận theo tên (bỏ dấu *, không phân biệt hoa thường / dấu); ô công thức lấy giá trị đã tính;
     * bỏ dòng trống. Sheet thiếu → danh sách rỗng.
     */
    static WorkbookData readWorkbook(byte[] content) {
        Map<String, List<SheetRow>> result = new HashMap<>();
        Map<String, String> sheetByKey = new HashMap<>();
        for (String name : DATA_SHEETS) {
            result.put(name, new ArrayList<>());
            sheetByKey.put(sheetKey(name), name);
            for (String alias : SHEET_ALIASES.getOrDefault(name, List.of())) sheetByKey.put(sheetKey(alias), name);
        }
        Map<String, String> titles = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        List<String> headerOnly = new ArrayList<>();
        List<RowError> headerErrors = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (Sheet sheet : workbook) {
                String title = sheet.getSheetName();
                String name = sheetByKey.get(sheetKey(title));
                if (name == null) {
                    if (!sheetKey(title).equals(sheetKey(SHEET_GUIDE))) unknown.add(title);
                    continue;
                }
                if (titles.containsKey(name)) {   // hai sheet cùng nghĩa: chỉ đọc sheet đầu, báo sheet sau
                    headerErrors.add(new RowError(title, 1, "Sheet '" + title + "' trùng với sheet '" + titles.get(name)
                            + "' (cùng là " + name + "). Gộp dữ liệu vào một sheet."));
                    continue;
                }
                titles.put(name, title);
                readSheet(sheet, name, title, formatter, evaluator, result.get(name), headerOnly, headerErrors);
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException apiException) throw apiException;
            throw new ApiException(ErrorCode.BAD_REQUEST, "Không đọc được file Excel (.xlsx). Hãy mở bằng Excel, lưu lại dạng .xlsx rồi thử lại.");
        }
        return new WorkbookData(result, titles, unknown, headerOnly, headerErrors);
    }

    private static void readSheet(Sheet sheet, String name, String title, DataFormatter formatter, FormulaEvaluator evaluator,
                                  List<SheetRow> rows, List<String> headerOnly, List<RowError> headerErrors) {
        List<String> template = HEADERS.get(name);
        int first = Math.max(sheet.getFirstRowNum(), 0);
        int last = sheet.getLastRowNum();
        // 1) Dò dòng tiêu đề: dòng (trong vài dòng đầu) khớp nhiều tên cột của file mẫu nhất.
        int headerRow = -1;
        int[] columnOf = null;
        int best = 0;
        for (int r = first; r <= Math.min(last, first + HEADER_SCAN_ROWS - 1); r++) {
            List<String> cells = rowValues(sheet.getRow(r), formatter, evaluator);
            int[] mapping = new int[template.size()];
            java.util.Arrays.fill(mapping, -1);
            int matched = 0;
            for (int c = 0; c < cells.size(); c++) {
                int index = headerIndex(template, cells.get(c));
                if (index >= 0 && mapping[index] < 0) { mapping[index] = c; matched++; }
            }
            if (matched > best) { best = matched; headerRow = r; columnOf = mapping; }
        }
        boolean hasContent = false;
        for (int r = first; r <= last && !hasContent; r++) {
            hasContent = rowValues(sheet.getRow(r), formatter, evaluator).stream().anyMatch(v -> !v.isBlank());
        }
        if (headerRow < 0) {
            if (hasContent) {
                headerErrors.add(new RowError(title, first + 1, "Sheet '" + title + "' không có dòng tiêu đề như file mẫu trong "
                        + HEADER_SCAN_ROWS + " dòng đầu. Cần các cột: " + String.join(", ", headerLabels(template)) + "."));
            }
            return;
        }
        // 2) Cột bắt buộc phải có (theo tên).
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < template.size(); i++) {
            if (columnOf[i] < 0 && template.get(i).endsWith("*")) missing.add(headerLabel(template.get(i)));
        }
        if (!missing.isEmpty()) {
            List<String> present = rowValues(sheet.getRow(headerRow), formatter, evaluator).stream()
                    .filter(v -> !v.isBlank()).map(String::strip).toList();
            headerErrors.add(new RowError(title, headerRow + 1, "Sheet '" + title + "' thiếu cột "
                    + missing.stream().map(m -> "'" + m + "'").collect(Collectors.joining(", "))
                    + " (dòng tiêu đề " + (headerRow + 1) + " có: " + String.join(", ", present)
                    + "). Đặt tên cột đúng như file mẫu."));
            return;
        }
        // 3) Dữ liệu: các dòng sau dòng tiêu đề, đưa về thứ tự cột của file mẫu; bỏ dòng trống.
        for (int r = headerRow + 1; r <= last; r++) {
            List<String> raw = rowValues(sheet.getRow(r), formatter, evaluator);
            if (raw.stream().allMatch(String::isBlank)) continue;
            List<String> cells = new ArrayList<>(template.size());
            boolean blank = true;
            for (int i = 0; i < template.size(); i++) {
                int c = columnOf[i];
                String value = c >= 0 && c < raw.size() ? raw.get(c) : "";
                cells.add(value);
                if (!value.isBlank()) blank = false;
            }
            if (blank) continue;   // dòng chỉ có chữ ở cột lạ (ghi chú) — bỏ qua
            if (rows.size() >= MAX_ROWS_PER_SHEET) {
                throw new ApiException(ErrorCode.BAD_REQUEST,
                        "Sheet " + title + " có quá " + MAX_ROWS_PER_SHEET + " dòng dữ liệu.");
            }
            rows.add(new SheetRow(r + 1, cells));
        }
        if (rows.isEmpty()) headerOnly.add(title);
    }

    /** Giá trị hiển thị của các ô trong dòng; ô công thức lấy giá trị đã tính (tính lại được thì tính, không thì dùng kết quả lưu sẵn). */
    private static List<String> rowValues(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<String> cells = new ArrayList<>();
        if (row == null) return cells;
        for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
            cells.add(cellValue(row.getCell(c), formatter, evaluator));
        }
        return cells;
    }

    private static String cellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        if (cell.getCellType() != CellType.FORMULA) return formatter.formatCellValue(cell);
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            // Hàm Excel POI không tính được: dùng kết quả Excel đã lưu trong file.
            return switch (cell.getCachedFormulaResultType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> formatter.formatRawCellContents(cell.getNumericCellValue(), cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        }
    }

    /** Vị trí cột trong file mẫu ứng với tiêu đề trong file (bỏ dấu *, không phân biệt hoa thường / dấu / khoảng trắng); -1 nếu không khớp. */
    private static int headerIndex(List<String> template, String cell) {
        if (cell == null || cell.isBlank()) return -1;
        String key = sheetKey(cell.replace("*", ""));
        if (key.isEmpty()) return -1;
        for (int i = 0; i < template.size(); i++) {
            if (sheetKey(template.get(i).replace("*", "")).equals(key)) return i;
        }
        return -1;
    }

    private static String headerLabel(String header) {
        return header.replace("*", "").strip();
    }

    private static List<String> headerLabels(List<String> template) {
        return template.stream().map(LessonImportServiceImpl::headerLabel).toList();
    }

    /** File không có dòng dữ liệu: nói rõ sheet tìm thấy / chỉ có tiêu đề / không nhận ra. */
    static String emptyFileMessage(WorkbookData data) {
        StringBuilder message = new StringBuilder("File không có dòng dữ liệu nào để nhập.");
        if (data.sheetTitles().isEmpty()) {
            message.append(" Không tìm thấy sheet nào trong ").append(String.join(", ", DATA_SHEETS)).append('.');
        } else if (!data.headerOnlySheets().isEmpty()) {
            message.append(" Các sheet chỉ có dòng tiêu đề, chưa có dữ liệu: ")
                    .append(String.join(", ", data.headerOnlySheets())).append('.');
        }
        if (!data.unknownSheets().isEmpty()) {
            message.append(" Sheet không nhận ra (bỏ qua): ").append(String.join(", ", data.unknownSheets()))
                    .append(" — tên sheet phải là ").append(String.join(", ", DATA_SHEETS)).append(" (hoặc tiếng Việt có dấu như \"Từ vựng\").");
        }
        message.append(" Điền dữ liệu từ dòng ngay dưới dòng tiêu đề rồi LƯU file trước khi tải lên.");
        return message.toString();
    }

    // ── File mẫu ─────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public byte[] buildTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            writeHeader(workbook, SHEET_VOCABULARY, VOCABULARY_HEADER, headerStyle);
            writeHeader(workbook, SHEET_GRAMMAR, GRAMMAR_HEADER, headerStyle);
            writeHeader(workbook, SHEET_EXAMPLE, EXAMPLE_HEADER, headerStyle);
            writeHeader(workbook, SHEET_DIALOGUE, DIALOGUE_HEADER, headerStyle);
            writeHeader(workbook, SHEET_KANJI, KANJI_HEADER, headerStyle);

            Sheet guide = workbook.createSheet(SHEET_GUIDE);
            List<String> lines = List.of(
                    "HƯỚNG DẪN NHẬP NỘI DUNG BÀI HỌC",
                    "• Cột có dấu * là bắt buộc. Dòng 1 của mỗi sheet là tiêu đề, dữ liệu bắt đầu từ dòng 2.",
                    "• Sheet nào không dùng thì để trống. Mỗi sheet tối đa " + MAX_ROWS_PER_SHEET + " dòng, file tối đa 2MB.",
                    "• File có bất kỳ dòng lỗi nào thì KHÔNG nhập gì cả; sửa theo danh sách lỗi rồi tải lên lại.",
                    "",
                    SHEET_VOCABULARY + ": mỗi dòng một từ, ví dụ: 学生 | がくせい | gakusei | học sinh | danh từ | N5.",
                    "   Từ đã có trong từ điển (cùng cách viết + cách đọc) được gắn vào bài; chưa có thì tạo mới ở trạng thái nháp.",
                    "   Từ đã có trong bài hoặc lặp lại trong file được bỏ qua.",
                    SHEET_GRAMMAR + ": mỗi dòng một mẫu, ví dụ: ～は～です | ～ là ～. Mẫu đã có trong bài (cùng cách viết) được bỏ qua.",
                    SHEET_EXAMPLE + ": câu ví dụ của mẫu; cột Mẫu ngữ pháp phải ghi đúng như ở sheet " + SHEET_GRAMMAR
                            + " hoặc như mẫu đã có trong bài. Tiêu đề để trống thì lấy chính câu tiếng Nhật.",
                    SHEET_DIALOGUE + ": mỗi dòng một lời thoại, theo thứ tự nói. Các dòng cùng Đoạn hội thoại thuộc cùng một đoạn.",
                    "   Mã vai viết liền không dấu (ví dụ: mira, sato). Vai mới cần Tên vai (tiếng Việt); vai đã khai báo thì có thể bỏ trống.",
                    "   Bên: trai hoặc phai (vị trí bong bóng thoại), có thể bỏ trống.",
                    SHEET_KANJI + ": mỗi ô có thể ghi một hoặc nhiều chữ (ví dụ: 学生日). Chỉ nhận chữ đã có trong dữ liệu Kanji.",
                    "   Kanji trong từ vựng của bài đã tự hiện, không cần nhập lại ở đây.");
            for (int i = 0; i < lines.size(); i++) {
                guide.createRow(i).createCell(0).setCellValue(lines.get(i));
            }
            guide.getRow(0).getCell(0).setCellStyle(headerStyle);
            guide.setColumnWidth(0, 150 * 256);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "Không tạo được file mẫu.");
        }
    }

    private static void writeHeader(Workbook workbook, String name, List<String> header, CellStyle style) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        for (int i = 0; i < header.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(header.get(i));
            cell.setCellStyle(style);
            sheet.setColumnWidth(i, 24 * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    // ── Tiện ích ─────────────────────────────────────────────────────────────

    private static String required(SheetRow row, int column, List<String> header, int maxLength,
                                   String sheet, List<RowError> errors) {
        String value = row.get(column);
        if (value == null) {
            errors.add(error(sheet, row, "Ô cột \"" + headerLabel(header.get(column)) + "\" đang trống (cột bắt buộc)"));
            return null;
        }
        return checkLength(value, row, column, header, maxLength, sheet, errors);
    }

    private static String optional(SheetRow row, int column, List<String> header, int maxLength,
                                   String sheet, List<RowError> errors) {
        String value = row.get(column);
        return value == null ? null : checkLength(value, row, column, header, maxLength, sheet, errors);
    }

    private static String checkLength(String value, SheetRow row, int column, List<String> header, int maxLength,
                                      String sheet, List<RowError> errors) {
        if (value.length() > maxLength) {
            errors.add(error(sheet, row, "Cột \"" + header.get(column) + "\" dài quá " + maxLength + " ký tự"));
            return null;
        }
        return value;
    }

    private static RowError error(String sheet, SheetRow row, String message) {
        return new RowError(sheet, row.number(), message);
    }

    /** Khoá so khớp mẫu ngữ pháp / tên đoạn hội thoại: chuẩn hoá NFKC + khoảng trắng như dữ liệu từ điển. */
    private static String key(String value) {
        return DictionaryUtil.normalizeText(value);
    }

    /** Khoá so khớp tên sheet: bỏ dấu, khoảng trắng, gạch dưới, không phân biệt hoa thường ("Từ vựng" = "TuVung"). */
    private static String sheetKey(String name) {
        String stripped = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** "trai"/"trái"/"left" → trai, "phai"/"phải"/"right" → phai, trống → null; giá trị khác cũng trả null. */
    private static String bubbleSide(String value) {
        if (value == null) return null;
        String side = sheetKey(value);
        if (side.equals("trai") || side.equals("left")) return "trai";
        if (side.equals("phai") || side.equals("right")) return "phai";
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        int end = maxLength;
        // Không cắt đôi một cặp surrogate (Kanji mở rộng).
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }
}
