-- [JLAS-W0193] Rollback guard for migration 014.
-- Run this query first. It must return no rows; otherwise multiple source segments exist and the
-- former (path, source_lesson_id) uniqueness cannot be restored without removing valid learning data.
SELECT learning_path_id, source_lesson_id, COUNT(*) AS segment_count
FROM learning_path_package
WHERE source_lesson_id IS NOT NULL
GROUP BY learning_path_id, source_lesson_id
HAVING COUNT(*) > 1;

-- Only after the query above returns zero rows:
ALTER TABLE learning_path_package
    DROP INDEX uk_learning_package_path_source_segment,
    DROP COLUMN source_segment_index,
    ADD UNIQUE KEY uk_learning_package_path_source (learning_path_id, source_lesson_id);
