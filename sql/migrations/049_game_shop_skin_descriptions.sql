-- Cửa hàng Trò chơi: mô tả ngắn, vui hơn cho các trang phục gốc (khớp com.jlas.game.util.GameShopCatalog).
-- GameShopSchemaInitializer chỉ INSERT IGNORE nên DB đã có dữ liệu giữ mô tả cũ; tệp này cập nhật chúng.
-- Chỉ đổi dòng còn đúng mô tả cũ, nên không ghi đè mô tả admin đã tự sửa. Chạy lại an toàn.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE game_shop_skin SET description = 'Mèo cam!'
    WHERE id = 1101 AND description = 'Chú mèo cam ôm giỏ, có sẵn cho mọi người.';
UPDATE game_shop_skin SET description = 'Có phải mèo đi hia không?'
    WHERE id = 1102 AND description = 'Đội mũ cao bồi, có cảnh ăn mừng riêng khi thắng.';
UPDATE game_shop_skin SET description = 'Úm ba la xì bùa!'
    WHERE id = 1103 AND description = 'Áo choàng tím và nồi phép thuật.';
UPDATE game_shop_skin SET description = 'ROAAAR!'
    WHERE id = 1104 AND description = 'Godzilla chibi ôm giỏ bắt cá, với bộ chuyển động riêng.';
UPDATE game_shop_skin SET description = 'Ronovaaaaaaaaa!'
    WHERE id = 1105 AND description = 'Mái tóc trắng và trang phục đỏ đen của Ronova.';
UPDATE game_shop_skin SET description = 'Hơi thở của Sấm Sét!'
    WHERE id = 1106 AND description = 'Áo haori vàng họa tiết tam giác và chiếc giỏ bắt cá.';
UPDATE game_shop_skin SET description = 'Hãy đợi đó, Muzan!'
    WHERE id = 1107 AND description = 'Áo haori caro xanh đen và bộ chuyển động riêng của Tanjiro.';
UPDATE game_shop_skin SET description = 'Máy bay mặc định.'
    WHERE id = 1201 AND description = 'Máy bay mặc định, có sẵn cho mọi người.';

COMMIT;
