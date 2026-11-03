package com.jlas.jlptpath.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.jlptpath.dto.JpathAdminItemView;
import com.jlas.jlptpath.dto.JpathContentEditRequest;
import com.jlas.jlptpath.dto.JpathContentEditView;
import com.jlas.jlptpath.dto.JpathEditExport;
import com.jlas.jlptpath.dto.JpathEditExportRow;
import com.jlas.jlptpath.dto.JpathPage;
import com.jlas.jlptpath.dto.JpathReportGroupView;
import com.jlas.jlptpath.dto.JpathReportQueueFilter;
import com.jlas.jlptpath.dto.JpathReportResolveRequest;
import com.jlas.jlptpath.dto.JpathReportRowView;
import com.jlas.jlptpath.dto.JpathReportTargetRef;
import com.jlas.jlptpath.entity.JpathContentEdit;
import com.jlas.jlptpath.entity.JpathContentReport;
import com.jlas.jlptpath.entity.JpathExample;
import com.jlas.jlptpath.enums.JpathContentStatus;
import com.jlas.jlptpath.enums.JpathReportTarget;
import com.jlas.jlptpath.repository.JpathContentEditRepository;
import com.jlas.jlptpath.repository.JpathContentReportRepository;
import com.jlas.jlptpath.repository.JpathContentReportRepository.TargetGroup;
import com.jlas.jlptpath.repository.JpathExampleRepository;
import com.jlas.jlptpath.repository.JpathGrammarQuestionRepository;
import com.jlas.jlptpath.repository.JpathGrammarRepository;
import com.jlas.jlptpath.repository.JpathKanaRepository;
import com.jlas.jlptpath.repository.JpathKanjiRepository;
import com.jlas.jlptpath.repository.JpathVocabularyRepository;
import com.jlas.jlptpath.service.JpathClock;
import com.jlas.jlptpath.service.JpathContentAdminService;
import com.jlas.jlptpath.service.impl.JpathEditableFields.Field;
import com.jlas.jlptpath.util.JpathAudio;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [JLAS-W0108] {@link JpathContentAdminService}. Targets are found through {@link JpathReportTargets} whatever their
 * status (HOLD rows stay in the queue); editable columns and their checks live in {@link JpathEditableFields}.
 */
@Service
@RequiredArgsConstructor
public class JpathContentAdminServiceImpl implements JpathContentAdminService {

    static final String HELD_PREVIEW = "(mục đã ngừng hiển thị)";
    private static final String REPORT_HIDDEN = "report_hidden";
    private static final String STATUS = "status";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int NOTES_PER_GROUP = 5;
    private static final int REPORTS_PER_ITEM = 50;
    private static final int EDITS_PER_ITEM = 20;
    private static final Set<String> QUEUE_STATUSES = Set.of("OPEN", "RESOLVED", "REJECTED");
    private static final Set<String> CLOSING_STATUSES = Set.of("RESOLVED", "REJECTED");

    private final JpathReportTargets targets;
    private final JpathContentReportRepository reports;
    private final JpathContentEditRepository edits;
    private final JpathVocabularyRepository vocabularies;
    private final JpathKanjiRepository kanjis;
    private final JpathGrammarRepository grammars;
    private final JpathKanaRepository kanas;
    private final JpathExampleRepository examples;
    private final JpathGrammarQuestionRepository questions;
    private final JpathClock clock;

    @Override
    @Transactional(readOnly = true)
    public JpathPage<JpathReportGroupView> queue(JpathReportQueueFilter filter) {
        String status = filter.status() == null || filter.status().isBlank()
                ? "OPEN" : filter.status().strip().toUpperCase(Locale.ROOT);
        if (!QUEUE_STATUSES.contains(status)) {
            throw JpathEditableFields.invalid("Trạng thái báo lỗi không hợp lệ: " + filter.status());
        }
        String level = filter.level() == null || filter.level().isBlank() ? null : level(filter.level());
        int size = filter.size() <= 0 ? DEFAULT_PAGE_SIZE : Math.min(filter.size(), MAX_PAGE_SIZE);
        int page = Math.max(0, filter.page());

        List<TargetGroup> groups = reports.groupByTarget(status, level);
        int from = (int) Math.min((long) page * size, groups.size());
        List<TargetGroup> slice = groups.subList(from, Math.min(from + size, groups.size()));
        Map<String, List<JpathContentReport>> rowsByTarget = slice.isEmpty() ? Map.of()
                : reports.findByStatusAndTargetCodeInOrderByCreatedAtDescIdDesc(status,
                        slice.stream().map(TargetGroup::getTargetCode).collect(Collectors.toSet())).stream()
                .collect(Collectors.groupingBy(r -> key(r.getTargetType(), r.getTargetCode()), LinkedHashMap::new,
                        Collectors.toList()));

        List<JpathReportGroupView> items = new ArrayList<>(slice.size());
        for (TargetGroup g : slice) {
            List<JpathContentReport> rows = rowsByTarget.getOrDefault(key(g.getTargetType(), g.getTargetCode()),
                    List.of());
            items.add(groupView(g, rows));
        }
        return new JpathPage<>(items, page, size, groups.size(), Map.of());
    }

    @Override
    @Transactional(readOnly = true)
    public JpathAdminItemView item(String targetType, String code) {
        return view(require(targetType, code));
    }

    @Override
    @Transactional
    public JpathAdminItemView edit(Long editorId, String targetType, String code, JpathContentEditRequest request) {
        JpathReportTargetRef ref = require(targetType, code);
        Map<String, Field> allowed = JpathEditableFields.of(ref.type());
        if (allowed.isEmpty()) {
            throw JpathEditableFields.invalid("Mục này không sửa được.");
        }
        Object entity = load(ref).orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
        if (request == null || request.fields() == null || request.fields().isEmpty()) {
            throw JpathEditableFields.invalid("Chưa có trường nào để sửa.");
        }

        // Check every field before changing any.
        Map<String, String> next = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : request.fields().entrySet()) {
            Field field = allowed.get(e.getKey());
            if (field == null) {
                throw JpathEditableFields.invalid("Trường không sửa được: " + e.getKey());
            }
            next.put(field.name(), JpathEditableFields.normalize(field, e.getValue()));
        }
        if (ref.type() == JpathReportTarget.EXAMPLE && (next.containsKey("ja") || next.containsKey("ja_furigana"))) {
            JpathExample example = (JpathExample) entity;
            String ja = next.containsKey("ja") ? next.get("ja") : example.getJa();
            String furigana = next.containsKey("ja_furigana") ? next.get("ja_furigana") : example.getJaFurigana();
            if (furigana != null && !JpathEditableFields.plain(furigana).equals(ja)) {
                throw JpathEditableFields.invalid(JpathEditableFields.FURIGANA_MISMATCH);
            }
        }

        List<JpathContentEdit> log = new ArrayList<>();
        for (Map.Entry<String, String> e : next.entrySet()) {
            Field field = allowed.get(e.getKey());
            String old = field.getter().apply(entity);
            if (JpathEditableFields.same(field, old, e.getValue())) {
                continue;
            }
            field.setter().accept(entity, e.getValue());
            log.add(editRow(editorId, ref, field.name(), old, e.getValue(), null));
        }
        edits.saveAll(log);
        return view(ref);
    }

    @Override
    @Transactional
    public JpathAdminItemView resolve(Long editorId, String targetType, String code,
                                      JpathReportResolveRequest request) {
        String status = request == null || request.status() == null ? ""
                : request.status().strip().toUpperCase(Locale.ROOT);
        if (!CLOSING_STATUSES.contains(status)) {
            throw JpathEditableFields.invalid("Trạng thái xử lý phải là RESOLVED hoặc REJECTED.");
        }
        JpathReportTargetRef ref = require(targetType, code);
        LocalDateTime now = clock.now();

        List<JpathContentReport> open = reports.findByTargetTypeAndTargetCodeAndStatus(ref.type(), ref.code(), "OPEN");
        for (JpathContentReport r : open) {
            r.setStatus(status);
            r.setResolvedBy(editorId);
            r.setResolvedAt(now);
        }
        reports.saveAll(open);
        Long reportId = open.stream().map(JpathContentReport::getId).max(Comparator.naturalOrder()).orElse(null);

        List<JpathContentEdit> log = new ArrayList<>();
        if (request.unhide() && ref.hidden()) {
            targets.setHidden(ref, false);
            log.add(editRow(editorId, ref, REPORT_HIDDEN, "1", "0", reportId));
        }
        Field statusField = JpathEditableFields.of(ref.type()).get(STATUS);
        if (request.markReviewed() && statusField != null) {
            Optional<Object> entity = load(ref);
            String old = entity.map(statusField.getter()).orElse(null);
            String reviewed = JpathContentStatus.REVIEWED.name();
            if (entity.isPresent() && !reviewed.equals(old)) {
                statusField.setter().accept(entity.get(), reviewed);
                log.add(editRow(editorId, ref, STATUS, old, reviewed, reportId));
            }
        }
        edits.saveAll(log);
        return view(require(targetType, code));
    }

    @Override
    @Transactional
    public JpathEditExport exportEdits(String level) {
        String lv = level(level);
        LocalDateTime now = clock.now();
        List<JpathContentEdit> pending = edits.findByLevelAndExportedAtIsNullOrderByIdAsc(lv);
        Map<String, JpathEditExportRow> last = new HashMap<>();
        for (JpathContentEdit e : pending) {
            if (!REPORT_HIDDEN.equals(e.getFieldName())) {
                last.put(key(e.getTargetType(), e.getTargetCode()) + "|" + e.getFieldName(), new JpathEditExportRow(
                        e.getTargetType().name(), e.getTargetCode(), e.getFieldName(), e.getNewValue()));
            }
            e.setExportedAt(now);
        }
        edits.saveAll(pending);
        List<JpathEditExportRow> rows = last.values().stream()
                .sorted(Comparator.comparing(JpathEditExportRow::targetType)
                        .thenComparing(JpathEditExportRow::targetCode)
                        .thenComparing(JpathEditExportRow::field))
                .toList();
        return new JpathEditExport(lv, now, rows.size(), rows);
    }

    // ------------------------------------------------------------------ helpers

    private JpathReportGroupView groupView(TargetGroup g, List<JpathContentReport> rows) {
        Map<String, Long> errorTypes = rows.stream().collect(Collectors.groupingBy(r -> r.getErrorType().name(),
                LinkedHashMap::new, Collectors.counting()));
        List<String> notes = rows.stream().map(JpathContentReport::getNote)
                .filter(n -> n != null && !n.isBlank()).limit(NOTES_PER_GROUP).toList();
        Optional<JpathReportTargetRef> ref = targets.resolve(g.getTargetType(), g.getTargetCode());
        String contentStatus = ref.flatMap(this::contentStatus).orElse(null);
        String preview = JpathContentStatus.HOLD.name().equals(contentStatus) ? HELD_PREVIEW
                : ref.map(targets::preview).orElse("");
        return new JpathReportGroupView(g.getTargetType().name(), g.getTargetCode(),
                g.getItemType() == null ? null : g.getItemType().name(), g.getItemCode(), g.getLevel(), preview,
                g.getReportCount(), g.getReporterCount(), errorTypes, notes, g.getFirstAt(), g.getLastAt(),
                ref.map(JpathReportTargetRef::hidden).orElse(false), contentStatus);
    }

    private JpathAdminItemView view(JpathReportTargetRef ref) {
        Optional<Object> entity = load(ref);
        Map<String, String> fields = entity.map(e -> JpathEditableFields.read(ref.type(), e)).orElse(Map.of());
        String context = ref.type() == JpathReportTarget.EXAMPLE || ref.type() == JpathReportTarget.QUESTION
                ? owner(ref).map(targets::preview).orElse("") : targets.preview(ref);
        List<JpathReportRowView> reportRows = reports.findByTargetTypeAndTargetCodeOrderByCreatedAtDescIdDesc(
                        ref.type(), ref.code(), PageRequest.of(0, REPORTS_PER_ITEM)).stream()
                .map(r -> new JpathReportRowView(r.getId(), r.getErrorType().name(), r.getNote(), r.getPageContext(),
                        r.getStatus(), r.getCreatedAt(), r.getResolvedAt()))
                .toList();
        List<JpathContentEditView> editRows = edits.findByTargetTypeAndTargetCodeOrderByIdDesc(ref.type(), ref.code(),
                        PageRequest.of(0, EDITS_PER_ITEM)).stream()
                .map(e -> new JpathContentEditView(e.getId(), e.getEditorUserId(), e.getFieldName(), e.getOldValue(),
                        e.getNewValue(), e.getCreatedAt(), e.getExportedAt()))
                .toList();
        return new JpathAdminItemView(ref.type().name(), ref.code(), ref.itemType().name(), ref.itemCode(),
                ref.level(), contentStatus(ref).orElse(null), ref.hidden(), ref.authored(), fields, context,
                audioUrl(ref), reportRows, editRows);
    }

    /** The owning item of an example or question (VOCAB/KANJI/GRAMMAR share their names with the target types). */
    private Optional<JpathReportTargetRef> owner(JpathReportTargetRef ref) {
        return targets.resolve(JpathReportTarget.valueOf(ref.itemType().name()), ref.itemCode());
    }

    private Optional<String> contentStatus(JpathReportTargetRef ref) {
        Field status = JpathEditableFields.of(ref.type()).get(STATUS);
        return status == null ? Optional.empty() : load(ref).map(status.getter());
    }

    private String audioUrl(JpathReportTargetRef ref) {
        return switch (ref.type()) {
            case VOCAB -> vocabularies.findById(ref.code()).map(v -> JpathAudio.url(v.getAudioPath(), null))
                    .orElse(null);
            case EXAMPLE -> examples.findById(ref.code()).map(e -> JpathAudio.url(e.getAudioPath(), null))
                    .orElse(null);
            case KANA -> kanas.findById(ref.code()).map(k -> JpathAudio.url(null, k.getKana())).orElse(null);
            default -> null;
        };
    }

    /** The content row behind an editable target; empty for kana and any type without editable fields. */
    private Optional<Object> load(JpathReportTargetRef ref) {
        return switch (ref.type()) {
            case VOCAB -> vocabularies.findById(ref.code()).map(Object.class::cast);
            case KANJI -> kanjis.findById(ref.code()).map(Object.class::cast);
            case GRAMMAR -> grammars.findById(ref.code()).map(Object.class::cast);
            case EXAMPLE -> examples.findById(ref.code()).map(Object.class::cast);
            case QUESTION -> questions.findById(ref.code()).map(Object.class::cast);
            default -> Optional.empty();
        };
    }

    private JpathReportTargetRef require(String targetType, String code) {
        JpathReportTarget type;
        try {
            type = JpathReportTarget.valueOf(targetType == null ? "" : targetType.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND);
        }
        return targets.resolve(type, code == null ? null : code.strip())
                .orElseThrow(() -> new ApiException(ErrorCode.JPATH_ITEM_NOT_FOUND));
    }

    private static String level(String raw) {
        String level = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        if (!level.matches("N[1-5]")) {
            throw JpathEditableFields.invalid("Cấp phải là N5, N4, N3, N2 hoặc N1.");
        }
        return level;
    }

    private static JpathContentEdit editRow(Long editorId, JpathReportTargetRef ref, String field, String oldValue,
                                            String newValue, Long reportId) {
        return JpathContentEdit.builder().editorUserId(editorId).targetType(ref.type()).targetCode(ref.code())
                .level(ref.level()).fieldName(field).oldValue(oldValue).newValue(newValue).reportId(reportId).build();
    }

    private static String key(JpathReportTarget type, String code) {
        return type.name() + "|" + code;
    }
}
