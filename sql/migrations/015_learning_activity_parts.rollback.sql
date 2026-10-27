-- [JLAS-W0125] Preflight ONLY. Rows returned mean rollback is blocked: do not delete valid parts to proceed.
SELECT package_id, activity_type, COUNT(*) AS parts
FROM user_learning_path_item
WHERE package_id IS NOT NULL AND activity_type IS NOT NULL
GROUP BY package_id, activity_type HAVING COUNT(*) > 1;

-- Stop writers and verify a restorable backup first. Deliberately NOT executable by running this file:
-- only when the preflight returns zero rows, an operator may run the following single ALTER.
-- ADD UNIQUE also guards against races/incorrect preflight; MySQL 8 atomic ALTER must fail on duplicates.
-- ALTER TABLE user_learning_path_item
--   ADD UNIQUE KEY uk_learning_path_item_package_type (package_id, activity_type),
--   DROP INDEX uk_learning_path_item_package_part,
--   DROP COLUMN part_index;
