package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.VocabularyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jlas.dictionary.util.DictionaryUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository("shadowingVocabularyEntryRepository")
public interface VocabularyEntryRepository extends JpaRepository<VocabularyEntry, Long> {
    List<VocabularyEntry> findTop5ByExpressionContainingOrReadingContainingOrderByIdAsc(
            String expression, String reading);

    boolean existsByNormalizedExpressionAndNormalizedReading(String expression, String reading);

    /** Longest dictionary word looked up inside a sentence; longer entries are set phrases, not words in a line. */
    int CONTAINED_MAX_LENGTH = 32;
    /** Keys per IN list, well under MySQL's packet and placeholder limits. */
    int CONTAINED_BATCH = 800;
    /** Same cap as the old query: callers only need the longest matches. */
    int CONTAINED_LIMIT = 100;

    // Native SQL on purpose: the dictionary module maps the same table to another entity also named
    // VocabularyEntry, and JPQL picked that one ("No converter found" and a 500 on the sample page).
    @Query(value = "select * from vocabulary_entry where normalized_expression in (:keys)", nativeQuery = true)
    List<VocabularyEntry> findByNormalizedExpressionIn(@Param("keys") Collection<String> keys);

    /** Dictionary words that occur in the text, longest first (any status). */
    default List<VocabularyEntry> findContainedIn(String text) {
        return containedIn(text, false);
    }

    /** Published dictionary words that occur in the text, longest first. */
    default List<VocabularyEntry> findPublishedContainedIn(String text) {
        return containedIn(text, true);
    }

    /**
     * Looks every substring of the text up through the normalized_expression index instead of testing
     * "text LIKE %expression%" against the whole table, which scanned every row for every sentence.
     * Keys use the stored normalization (NFKC, katakana to hiragana), so a match is exact, not the loose
     * ha = ba = pa comparison the old LIKE got from the column collation.
     */
    private List<VocabularyEntry> containedIn(String text, boolean publishedOnly) {
        String normalized = DictionaryUtil.normalizeReading(text);
        if (normalized.isEmpty()) return List.of();
        int[] points = normalized.codePoints().toArray();
        Set<String> keys = new LinkedHashSet<>();
        for (int start = 0; start < points.length; start++) {
            if (Character.isWhitespace(points[start])) continue;
            for (int end = start + 1; end <= Math.min(points.length, start + CONTAINED_MAX_LENGTH); end++) {
                if (Character.isWhitespace(points[end - 1])) break;
                keys.add(new String(points, start, end - start));
            }
        }
        List<String> all = new ArrayList<>(keys);
        Map<Long, VocabularyEntry> found = new LinkedHashMap<>();
        for (int from = 0; from < all.size(); from += CONTAINED_BATCH) {
            for (VocabularyEntry entry : findByNormalizedExpressionIn(all.subList(from, Math.min(all.size(), from + CONTAINED_BATCH)))) {
                if (!publishedOnly || "PUBLISHED".equals(entry.getDictionaryStatus())) found.putIfAbsent(entry.getId(), entry);
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparingInt((VocabularyEntry entry) -> length(entry.getExpression())).reversed()
                        .thenComparing(VocabularyEntry::getId))
                .limit(CONTAINED_LIMIT)
                .toList();
    }

    private static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
