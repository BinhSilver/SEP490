-- [JLAS-DICT] Cấp JLPT của từ điển theo lộ trình JLPT (jpath_vocabulary.level).
-- Trước khi chạy, gần 2.000 mục PUBLISHED mang cấp khó hơn cấp mà lộ trình dạy từ đó (面白い, 何時も, 赤ちゃん = N1
-- trong khi lộ trình xếp N5), và hơn 2.000 mục có trong lộ trình nhưng chưa có cấp.
-- Quy tắc: cấp mới = cấp DỄ hơn giữa từ điển và lộ trình; mục chưa có cấp thì lấy cấp của lộ trình.
--   1. Ghép theo jpath_vocabulary.dictionary_entry_id.
--   2. Ghép theo đúng chữ + cách đọc.
--   3. Từ lộ trình ghi bằng kana (かるい) ghép với mục kanji (軽い) theo cách đọc, CHỈ khi cách đọc đó ứng với đúng một
--      mục tra cứu được và mục đó đã có cấp (chỉ hạ cấp, không điền mới: 誤認 "ごにん" không được nhận cấp N5 của 五人).
-- Chỉ đụng mục dictionary_status = 'PUBLISHED'; bỏ qua từ lộ trình ở trạng thái DRAFT. Giá trị cũ được chép vào
-- backup_jlpt_level_080 (id, jlpt_level) trước khi sửa. Chạy lại an toàn (lần sau không còn gì để đổi).
-- Hoàn tác: UPDATE vocabulary_entry e JOIN backup_jlpt_level_080 b ON b.id = e.id SET e.jlpt_level = b.jlpt_level;
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS backup_jlpt_level_080 (
    id         BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    jlpt_level VARCHAR(10)     NULL,
    saved_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS tmp_jlpt_level_080;
CREATE TABLE tmp_jlpt_level_080 (
    id  BIGINT UNSIGNED NOT NULL,
    lvl VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    KEY (id)
);

-- 1 + 2: N5 > N4 > ... > N1 theo thứ tự chữ, nên MAX là cấp dễ nhất.
INSERT INTO tmp_jlpt_level_080 (id, lvl)
SELECT j.dictionary_entry_id, j.level FROM jpath_vocabulary j
WHERE j.dictionary_entry_id IS NOT NULL AND j.status <> 'DRAFT';

INSERT INTO tmp_jlpt_level_080 (id, lvl)
SELECT e.id, j.level FROM jpath_vocabulary j
JOIN vocabulary_entry e ON e.expression = j.expression AND e.reading = j.reading
WHERE j.status <> 'DRAFT' AND e.dictionary_status = 'PUBLISHED';

-- 3: kana của lộ trình -> đúng một mục tra cứu được có cách đọc đó, đã có cấp.
INSERT INTO tmp_jlpt_level_080 (id, lvl)
SELECT u.id, k.lvl
FROM (SELECT reading, MAX(level) lvl FROM jpath_vocabulary WHERE status <> 'DRAFT' AND expression = reading GROUP BY reading) k
JOIN (SELECT e.reading, MIN(e.id) id FROM vocabulary_entry e
      WHERE e.dictionary_status = 'PUBLISHED' AND e.usage_scope <> 'TEXTBOOK'
      GROUP BY e.reading HAVING COUNT(*) = 1) u ON u.reading = k.reading
JOIN vocabulary_entry e ON e.id = u.id
WHERE e.jlpt_level IS NOT NULL AND e.expression <> e.reading;

DROP TABLE IF EXISTS tmp_jlpt_level_080_best;
-- Khai khóa chính ngay khi tạo: MySQL bật sql_generate_invisible_primary_key (Azure) tự thêm khóa ẩn cho bảng tạo bằng
-- CREATE ... AS SELECT, nên ALTER ... ADD PRIMARY KEY sau đó bị từ chối.
CREATE TABLE tmp_jlpt_level_080_best (
    id  BIGINT UNSIGNED NOT NULL PRIMARY KEY,
    lvl VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL
);
INSERT INTO tmp_jlpt_level_080_best (id, lvl) SELECT id, MAX(lvl) FROM tmp_jlpt_level_080 GROUP BY id;

START TRANSACTION;

INSERT IGNORE INTO backup_jlpt_level_080 (id, jlpt_level)
SELECT e.id, e.jlpt_level FROM vocabulary_entry e JOIN tmp_jlpt_level_080_best b ON b.id = e.id
WHERE e.dictionary_status = 'PUBLISHED' AND (e.jlpt_level IS NULL OR b.lvl > e.jlpt_level);

UPDATE vocabulary_entry e JOIN tmp_jlpt_level_080_best b ON b.id = e.id
SET e.jlpt_level = b.lvl
WHERE e.dictionary_status = 'PUBLISHED' AND (e.jlpt_level IS NULL OR b.lvl > e.jlpt_level);

COMMIT;

DROP TABLE tmp_jlpt_level_080;
DROP TABLE tmp_jlpt_level_080_best;
