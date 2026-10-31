-- [JLAS-GAME-SKIN-PACK] Cửa hàng Trò chơi: admin tải lên gói skin (.zip: manifest.json + ảnh PNG, cùng định dạng skin gốc)
-- thay cho kiểu "ảnh tĩnh". Gói lưu ở uploads/game-skins/packs/<uuid>/; trang phục dùng gói có render_code = 'PACK'.
--   pack_path:    đường dẫn thư mục gói, ví dụ /uploads/game-skins/packs/<uuid>/ (NULL nếu không dùng gói).
--   weapon_theme: máy bay dùng gói mượn bộ vũ khí của máy bay gốc nào (halo-fighter | aegis-frame | split-core |
--                 void-blade); NULL = vũ khí mặc định.
-- Chạy lại an toàn. GameShopSchemaInitializer thêm đúng hai cột này khi khởi động.
SET @skin_pack_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'game_shop_skin' AND column_name = 'pack_path');
SET @skin_pack_ddl = IF(@skin_pack_exists = 0,
    'ALTER TABLE game_shop_skin ADD COLUMN pack_path VARCHAR(255) NULL',
    'SELECT 1');
PREPARE skin_pack_stmt FROM @skin_pack_ddl;
EXECUTE skin_pack_stmt;
DEALLOCATE PREPARE skin_pack_stmt;

SET @skin_weapon_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'game_shop_skin' AND column_name = 'weapon_theme');
SET @skin_weapon_ddl = IF(@skin_weapon_exists = 0,
    'ALTER TABLE game_shop_skin ADD COLUMN weapon_theme VARCHAR(40) NULL',
    'SELECT 1');
PREPARE skin_weapon_stmt FROM @skin_weapon_ddl;
EXECUTE skin_weapon_stmt;
DEALLOCATE PREPARE skin_weapon_stmt;
