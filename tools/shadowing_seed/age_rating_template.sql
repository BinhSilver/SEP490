-- J-LAS curated Shadowing age ratings (video.min_age), MySQL 8.0.21+, UTF-8.
-- GENERATED: edit "min_age" in data/shadowing/catalog.json and run tools/shadowing_seed/build_seed.py.
-- {{RATED_COUNT}} rated sources ({{RATED_SUMMARY}}); a source without min_age is for all ages.
-- A separate companion of seed_data_shadowing.sql because the column comes from migration 067
-- (sqlrun/43_age_rating.sql), which a new database only gets after that seed has run.
-- Run after migration 067 and the Shadowing seed. The rating follows the source content, so every
-- copy of these sources is rated. Only unrated videos are filled: a rating the owner or a moderator
-- set later is kept. Re-runnable; no other column is touched.

SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET @shadowing_age_ratings = {{AGE_RATINGS}};

UPDATE video v
JOIN JSON_TABLE(@shadowing_age_ratings, '$[*]' COLUMNS (
    source_key VARCHAR(11) PATH '$.source_key' ERROR ON EMPTY,
    source_url VARCHAR(255) PATH '$.source_url' ERROR ON EMPTY,
    min_age TINYINT UNSIGNED PATH '$.min_age' ERROR ON EMPTY
)) r ON v.source_url IN (r.source_url, CONCAT('https://youtu.be/', r.source_key),
    CONCAT('https://youtube.com/shorts/', r.source_key), CONCAT('https://www.youtube.com/shorts/', r.source_key))
SET v.min_age = r.min_age
WHERE v.min_age IS NULL;
