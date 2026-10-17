-- [JLAS-W0108] Tach nguon data cho Learning Path V2 khoi module Textbook (Huong B).
-- Composer V2 (JpaLearningScopeSource) truoc day keo TAT CA textbook PUBLISHED cung cap do,
-- khong phan biet "sach de doc/tra cuu" voi "sach dung de sinh lo trinh" -> textbook cua team
-- noi dung khac se tu dong bi lo trinh keo vao chi vi cung cap do + PUBLISHED.
--
-- Them co rieng: is_learning_path_source = 1 danh dau ro textbook nao duoc composer dung.
-- Mac dinh 0 (an toan, khong anh huong textbook da co). Van dung chung vocabulary_entry/kanji/
-- grammar_point voi Dictionary/Assessment (khong nhan doi du lieu) -- chi tach o cap textbook/
-- unit/lesson (dung Huong B, khong tach kien truc theo Huong C).

ALTER TABLE textbook
    ADD COLUMN is_learning_path_source TINYINT(1) NOT NULL DEFAULT 0,
    ADD KEY idx_textbook_learning_path_source (is_learning_path_source, jlpt_level, status);

-- Backfill: 6 textbook JLPT/nen tang bang chu cai dang la nguon lo trinh THAT hien tai (da qua
-- gate KANJI_NOT_READY/TOO_MANY_GRAMMAR_POINTS, da verify sinh lo trinh chay duoc) tiep tuc duoc
-- dung. Textbook chuyen nganh IT/du lich/doi song (7/8/9) CHUA co logic composer cho goal ngoai
-- JLPT nen KHONG danh co (giu 0) -- tranh composer vo tinh keo vao khi feature do duoc lam sau.
UPDATE textbook SET is_learning_path_source = 1 WHERE id IN (1, 2, 3, 4, 5, 6);
