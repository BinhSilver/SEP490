package com.jlas.dictionary.service;

import com.jlas.auth.repository.UserRepository;
import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.dictionary.dto.*;
import com.jlas.dictionary.entity.*;
import com.jlas.dictionary.repository.*;
import com.jlas.dictionary.util.DictionaryUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class DictionaryEngagementService {
    private final DictionaryService dictionary;
    private final DictionaryFavoriteRepository favorites;
    private final DictionarySearchHistoryRepository history;
    private final VocabularyEntryRepository vocabulary;
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    public record NotebookSummary(Long id, String name, String type, long wordCount, long kanjiCount) {}
    public record NotebookEntry(Long vocabularyId, String expression, String reading, String meaning,
                                String exampleJa, String exampleVi, Integer repetitions,
                                java.time.LocalDateTime createdAt) {}

    @Transactional
    public InlineLookupResponse inlineLookup(String query, Long userId) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Cụm từ tra cứu không hợp lệ.");
        }
        SearchResultResponse result = dictionary.search(trimmed, 0, 1, null);
        List<VocabularyResponse> content = result.getVocabularyResults() == null
                ? List.of() : result.getVocabularyResults().getContent();
        if (content == null || content.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng phù hợp.");
        }
        VocabularyResponse word = content.getFirst();
        if (userId != null) {
            history.save(DictionarySearchHistory.builder().user(users.getReferenceById(userId))
                    .queryText(trimmed).normalizedQuery(DictionaryUtil.normalizeText(trimmed))
                    .searchMode(result.getSearchMode() == null ? "AUTO" : result.getSearchMode())
                    .selectedVocabulary(vocabulary.getReferenceById(word.getId())).resultCount(result.getTotal()).build());
        }
        return new InlineLookupResponse(word.getId(), word.getExpression(), word.getReading(),
                word.getMeaning(), word.getWordType(), "/dictionary/index.html?q="
                + URLEncoder.encode(word.getExpression(), StandardCharsets.UTF_8));
    }

    @Transactional
    public DictionaryFavoriteResponse saveFavorite(Long userId, Long vocabularyId) {
        var id = new DictionaryFavorite.DictionaryFavoriteId(userId, vocabularyId);
        vocabulary.findById(vocabularyId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng."));
        boolean created = favorites.insertIgnore(userId, vocabularyId) == 1;
        DictionaryFavorite saved = favorites.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.CONFLICT,
                        "Không thể lưu từ yêu thích. Vui lòng thử lại."));
        return favorite(saved, created);
    }

    @Transactional
    public void deleteFavorite(Long userId, Long vocabularyId) {
        favorites.deleteById(new DictionaryFavorite.DictionaryFavoriteId(userId, vocabularyId));
    }

    @Transactional(readOnly = true)
    public Page<DictionaryFavoriteResponse> favorites(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
        return favorites.findByUserIdOrderByCreatedAtDesc(userId, pageable).map(row -> favorite(row, false));
    }

    @Transactional
    public List<NotebookSummary> notebooks(Long userId) {
        ensureDefaultNotebooks(userId);
        return jdbc.query("SELECT n.id,n.name,n.notebook_type,(SELECT COUNT(*) FROM user_notebook_entry e WHERE e.notebook_id=n.id),(SELECT COUNT(*) FROM user_notebook_kanji k WHERE k.notebook_id=n.id) FROM user_notebook n WHERE n.user_id=? ORDER BY n.id", (rs, row) -> new NotebookSummary(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)), userId);
    }

    @Transactional
    public NotebookSummary createNotebook(Long userId, String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 120) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tên sổ tay phải từ 1 đến 120 ký tự.");
        try { jdbc.update("INSERT INTO user_notebook(user_id,name,notebook_type) VALUES (?,?,'CUSTOM')", userId, clean); }
        catch (org.springframework.dao.DuplicateKeyException e) { throw new ApiException(ErrorCode.CONFLICT, "Tên sổ tay đã tồn tại."); }
        return notebooks(userId).stream().filter(n -> n.name().equals(clean)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.CONFLICT));
    }

    @Transactional
    public NotebookSummary renameNotebook(Long userId, Long notebookId, String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 120) throw new ApiException(ErrorCode.VALIDATION_FAILED, "Tên sổ tay phải từ 1 đến 120 ký tự.");
        requireNotebook(userId, notebookId);
        try { jdbc.update("UPDATE user_notebook SET name=? WHERE id=? AND user_id=?", clean, notebookId, userId); }
        catch (org.springframework.dao.DuplicateKeyException e) { throw new ApiException(ErrorCode.CONFLICT, "Tên sổ tay đã tồn tại."); }
        return notebooks(userId).stream().filter(n -> n.id().equals(notebookId)).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay."));
    }

    @Transactional
    public void deleteNotebook(Long userId, Long notebookId) {
        requireCustomNotebook(userId, notebookId);
        jdbc.update("DELETE FROM user_notebook WHERE id=? AND user_id=? AND notebook_type='CUSTOM'", notebookId, userId);
    }

    @Transactional
    public void clearNotebook(Long userId, Long notebookId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_notebook WHERE id=? AND user_id=? AND notebook_type IN ('PERSONAL','WEAKNESS')", Integer.class, notebookId, userId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay mặc định.");
        jdbc.update("DELETE FROM user_notebook_entry WHERE notebook_id=?", notebookId);
        jdbc.update("DELETE FROM user_notebook_kanji WHERE notebook_id=?", notebookId);
    }

    @Transactional(readOnly = true)
    public List<NotebookEntry> notebookEntries(Long userId, Long notebookId) {
        requireNotebook(userId, notebookId);
        return jdbc.query("SELECT v.id,v.expression,v.reading,v.meaning,v.example_ja,v.example_vi,r.repetitions,e.created_at FROM user_notebook_entry e JOIN user_notebook n ON n.id=e.notebook_id JOIN vocabulary_entry v ON v.id=e.vocabulary_id LEFT JOIN user_notebook_review r ON r.notebook_id=e.notebook_id AND r.vocabulary_id=e.vocabulary_id WHERE n.id=? AND n.user_id=? ORDER BY e.created_at DESC,e.vocabulary_id DESC", (rs, row) -> new NotebookEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), (Integer) rs.getObject(7), rs.getTimestamp(8).toLocalDateTime()), notebookId, userId);
    }

    @Transactional
    public void addToNotebook(Long userId, Long notebookId, Long vocabularyId) {
        requireNotebook(userId, notebookId);
        vocabulary.findById(vocabularyId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy từ vựng."));
        jdbc.update("INSERT IGNORE INTO user_notebook_entry(notebook_id,vocabulary_id) VALUES (?,?)", notebookId, vocabularyId);
    }

    @Transactional
    public void removeFromNotebook(Long userId, Long notebookId, Long vocabularyId) {
        requireNotebook(userId, notebookId);
        jdbc.update("DELETE FROM user_notebook_entry WHERE notebook_id=? AND vocabulary_id=?", notebookId, vocabularyId);
    }

    private void ensureDefaultNotebooks(Long userId) {
        jdbc.update("INSERT IGNORE INTO user_notebook(user_id,name,notebook_type) SELECT ?, 'Sổ tay của tôi', 'PERSONAL' WHERE NOT EXISTS (SELECT 1 FROM user_notebook WHERE user_id=? AND notebook_type='PERSONAL')", userId, userId);
        jdbc.update("INSERT IGNORE INTO user_notebook(user_id,name,notebook_type) SELECT ?, 'Sổ tay điểm yếu', 'WEAKNESS' WHERE NOT EXISTS (SELECT 1 FROM user_notebook WHERE user_id=? AND notebook_type='WEAKNESS')", userId, userId);
    }
    private void requireNotebook(Long userId, Long notebookId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_notebook WHERE id=? AND user_id=?", Integer.class, notebookId, userId) == 0) throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay.");
    }
    private void requireCustomNotebook(Long userId, Long notebookId) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM user_notebook WHERE id=? AND user_id=? AND notebook_type='CUSTOM'", Integer.class, notebookId, userId) == 0)
            throw new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy sổ tay có thể chỉnh sửa.");
    }

    @Transactional(readOnly = true)
    public Page<SearchHistoryResponse> history(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
        return history.findByUserIdOrderBySearchedAtDesc(userId, pageable).map(row ->
                SearchHistoryResponse.builder().id(row.getId()).queryText(row.getQueryText())
                        .searchMode(row.getSearchMode()).resultCount(row.getResultCount())
                        .searchedAt(row.getSearchedAt()).selectedVocabularyId(row.getSelectedVocabulary() == null
                                ? null : row.getSelectedVocabulary().getId()).build());
    }

    private static DictionaryFavoriteResponse favorite(DictionaryFavorite row, boolean created) {
        return new DictionaryFavoriteResponse(row.getVocabularyEntry().getId(),
                row.getVocabularyEntry().getExpression(), row.getVocabularyEntry().getReading(),
                row.getNote(), row.getCreatedAt(), created);
    }
}
