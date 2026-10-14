-- [JLAS2-51] Gop vai tro ve 4 vai tro chuan: LEARNER, CONTENT_CREATOR, MODERATOR, SUPER_ADMIN.
--   ADMIN          -> SUPER_ADMIN      (System Admin lam ca kiem duyet va che tai nguoi dung)
--   CONTENT_ADMIN  -> CONTENT_CREATOR  (Content Admin va Content Creator la mot)
-- Premium khong phai vai tro: la LEARNER co goi Premium con han.
-- Chuyen tai khoan dang giu vai tro cu sang vai tro moi roi xoa vai tro cu.
-- Chay lai duoc (lan sau khong con gi de chuyen). Chay SAU cac file seed neu DB dung tu seed cu.

SET NAMES utf8mb4;

INSERT IGNORE INTO roles (name, description) VALUES
    ('SUPER_ADMIN',     'System Admin: quan tri he thong, kiem duyet va che tai nguoi dung'),
    ('CONTENT_CREATOR', 'Content Creator: soan, quan ly va duyet noi dung hoc');

UPDATE users u
  JOIN roles old_role ON old_role.id = u.role_id AND old_role.name = 'ADMIN'
  JOIN roles new_role ON new_role.name = 'SUPER_ADMIN'
   SET u.role_id = new_role.id;

UPDATE users u
  JOIN roles old_role ON old_role.id = u.role_id AND old_role.name = 'CONTENT_ADMIN'
  JOIN roles new_role ON new_role.name = 'CONTENT_CREATOR'
   SET u.role_id = new_role.id;

DELETE FROM roles WHERE name IN ('ADMIN', 'CONTENT_ADMIN');

SELECT r.name AS role, COUNT(u.id) AS users
  FROM roles r LEFT JOIN users u ON u.role_id = r.id
 GROUP BY r.name ORDER BY r.name;
