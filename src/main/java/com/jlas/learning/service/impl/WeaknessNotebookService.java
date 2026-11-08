package com.jlas.learning.service.impl;

import com.jlas.assessment.entity.AssessmentAttempt;
import com.jlas.assessment.repository.AssessmentAttemptRepository;
import com.jlas.assessment.repository.SmartTestSessionRepository;
import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.entity.GrammarPoint;
import com.jlas.dictionary.repository.GrammarPointRepository;
import com.jlas.dictionary.repository.VocabularyEntryRepository;
import com.jlas.dictionary.entity.VocabularyEntry;
import com.jlas.dictionary.util.DictionaryUtil;
import com.jlas.learning.dto.WeaknessNotebookResponse;
import com.jlas.learning.dto.WeaknessNotebookResponse.*;
import com.jlas.learning.entity.UserKnowledgeWeakness;
import com.jlas.learning.repository.UserKnowledgeWeaknessRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** [JLAS2-23] Personal notebook; ownership always comes from the authenticated principal. */
@Service
@RequiredArgsConstructor
public class WeaknessNotebookService {
    private final UserKnowledgeWeaknessRepository weaknesses;
    private final AssessmentAttemptRepository attempts;
    private final SmartTestSessionRepository sessions;
    private final UserRepository users;
    private final GrammarPointRepository grammar;
    private final VocabularyEntryRepository vocabulary;
    private final SmartTestLearningProfileSource profiles;
    private final WeakTagPolicy policy;
    private final WeaknessNotebook notebook;

    @Transactional(readOnly = true)
    public WeaknessNotebookResponse list(Long userId, String skill, String level, String status,
            String query, String sort, int page, int size) {
        requireUser(userId);
        if (page < 0 || size < 1 || size > 50 || (query != null && query.length() > 200))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bộ lọc không hợp lệ.");
        validateFilter(skill, Set.of("KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING"));
        validateFilter(level, Set.of("N1", "N2", "N3", "N4", "N5"));
        validateFilter(status, Set.of("OPEN", "REVIEWED", "MASTERED"));
        if (!Set.of("recent", "wrong", "accuracy").contains(sort))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Thứ tự không hợp lệ.");
        var rows = weaknesses.findByUserId(userId);
        var counts = new LinkedHashMap<String, Long>();
        for (String s : List.of("KANJI", "VOCABULARY", "GRAMMAR", "READING", "LISTENING"))
            counts.put(s, rows.stream().filter(r -> s.equals(r.getSkill())).count());
        var summary = new Summary(rows.size(), count(rows, "OPEN"), count(rows, "REVIEWED"), count(rows, "MASTERED"), counts);
        var gpIds = rows.stream().map(r -> grammarId(r.getCanonicalItem())).filter(Objects::nonNull).distinct().toList();
        Map<Long, GrammarPoint> points = grammar.findAllById(gpIds).stream().collect(Collectors.toMap(GrammarPoint::getId, Function.identity()));
        var words = rows.stream().map(UserKnowledgeWeakness::getCanonicalItem).filter(Objects::nonNull)
                .filter(k -> k.startsWith("KANJI:") || k.startsWith("VOCAB:"))
                .map(k -> DictionaryUtil.normalizeReading(k.substring(k.indexOf(':') + 1))).distinct().toList();
        var vocabularyMap = vocabulary.findByNormalizedExpressionInAndDictionaryStatus(words, "PUBLISHED").stream()
                .sorted(Comparator.comparing(VocabularyEntry::getId))
                .collect(Collectors.toMap(VocabularyEntry::getNormalizedExpression, Function.identity(), (first, second) -> first));
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        var filtered = rows.stream().filter(r -> matches(skill, r.getSkill()) && matches(level, r.getJlptLevel()) && matches(status, r.getStatus()))
                .map(r -> entry(r, points, vocabularyMap)).filter(r -> (r.title() + " " + r.reading() + " " + r.description() + " " + r.topic()).toLowerCase(Locale.ROOT).contains(needle))
                .sorted(comparator(sort)).toList();
        int start = (int) Math.min((long) page * size, filtered.size());
        var latest = latest(userId).map(a -> sessions.findById(a.getId()).map(s -> new Assessment(a.getId(),
                s.getEstimatedOverallLevel(), s.getTargetLevel(), s.getReadinessLevel(), a.getSubmittedAt())).orElse(null)).orElse(null);
        return new WeaknessNotebookResponse(filtered.subList(start, Math.min(start + size, filtered.size())),
                filtered.size(), page, size, summary, latest);
    }

    @Transactional
    public int syncLatest(Long userId) {
        requireUser(userId);
        var user = users.findForCreditUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var attempt = latest(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Bạn chưa có Smart Test đã hoàn thành."));
        var profile = profiles.loadForUser(userId, attempt.getId());
        var evidence = profile.weaknesses().stream().filter(policy::isWeak).toList();
        notebook.record(user, attempt.getId(), evidence);
        return evidence.size();
    }

    @Transactional
    public void review(Long userId, Long id, String status) {
        requireUser(userId);
        if (!Set.of("OPEN", "REVIEWED").contains(status == null ? "" : status))
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Chỉ có thể đánh dấu đã ôn hoặc cần ôn.");
        users.findForCreditUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var row = weaknesses.findByIdAndUserId(id, userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if ("MASTERED".equals(row.getStatus()))
            throw new ApiException(ErrorCode.CONFLICT, "Mục đã thành thạo được quản lý theo kết quả đánh giá.");
        if (!status.equals(row.getStatus())) {
            row.setStatus(status);
            if ("REVIEWED".equals(status)) row.setLastReviewedAt(LocalDateTime.now());
            weaknesses.save(row);
        }
    }

    private Optional<AssessmentAttempt> latest(Long userId) {
        return attempts.findFirstByUserIdAndAttemptTypeAndStatusOrderBySubmittedAtDescIdDesc(userId, "SMART_TEST", "SUBMITTED");
    }
    private static void requireUser(Long id) { if (id == null || id <= 0) throw new ApiException(ErrorCode.UNAUTHORIZED); }
    private static void validateFilter(String value, Set<String> choices) {
        if (value != null && !value.isBlank() && !choices.contains(value)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Bộ lọc không hợp lệ.");
    }
    private static boolean matches(String filter, String value) { return filter == null || filter.isBlank() || filter.equals(value); }
    private static long count(List<UserKnowledgeWeakness> rows, String status) { return rows.stream().filter(r -> status.equals(r.getStatus())).count(); }
    private static Long grammarId(String key) {
        try { return key != null && key.startsWith("GP:") ? Long.valueOf(key.substring(3)) : null; }
        catch (NumberFormatException e) { return null; }
    }
    private static Entry entry(UserKnowledgeWeakness r, Map<Long, GrammarPoint> points, Map<String, VocabularyEntry> vocabulary) {
        String title = r.getCanonicalItem(), description = r.getTag() == null ? "" : r.getTag().getName();
        String example = null, reading = null, translation = null, url = "/learning";
        if (title == null || title.isBlank()) title = description.isBlank() ? "Nội dung cần ôn" : description;
        var gp = points.get(grammarId(r.getCanonicalItem()));
        if (gp != null) { title = gp.getPattern(); description = gp.getMeaning(); example = gp.getExample(); }
        else if (title.startsWith("KANJI:") || title.startsWith("VOCAB:")) {
            title = title.substring(title.indexOf(':') + 1);
            var word = vocabulary.get(DictionaryUtil.normalizeReading(title));
            if (word != null) { description = word.getMeaning(); reading = word.getReading(); example = word.getExampleJa(); translation = word.getExampleVi(); }
            url = "/dictionary/index.html?q=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        } else if (r.getOutcome() != null) title = r.getOutcome().getNameVi();
        else if ("READING".equals(r.getSkill())) title = "Đọc hiểu";
        else if ("LISTENING".equals(r.getSkill())) title = "Nghe hiểu";
        return new Entry(r.getId(), title, description, example, reading, translation, r.getSkill(), r.getJlptLevel(), r.getStatus(),
                r.getWrongCount(), r.getScorePercent(), r.getTopicTag() == null ? "" : r.getTopicTag().getName(),
                r.getSource(), r.getDetectedAt(), r.getLastReviewedAt(), url);
    }
    private static Comparator<Entry> comparator(String sort) {
        Comparator<Entry> recent = Comparator.comparing(Entry::detectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Entry::id, Comparator.reverseOrder());
        return switch (sort) {
            case "wrong" -> Comparator.comparingInt(Entry::wrongCount).reversed().thenComparing(recent);
            case "accuracy" -> Comparator.comparing(Entry::accuracy, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(recent);
            default -> recent;
        };
    }
}
