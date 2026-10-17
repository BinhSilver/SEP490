-- [JLAS-W0250] Merge account roles: SUPER_ADMIN -> ADMIN, CONTENT_ADMIN -> CONTENT_CREATOR.
-- Four roles remain: LEARNER, MODERATOR, ADMIN, CONTENT_CREATOR. ADMIN does not get content-editing
-- rights; CONTENT_CREATOR now also has the content-review access CONTENT_ADMIN had (Onyomi, AI Call).
-- Safe to re-run; moves users first, then deletes the retired role rows (users.role_id has a FK).
-- The app also folds the old names at login (com.jlas.auth.security.Roles), so order is not critical.
SET NAMES utf8mb4;

INSERT IGNORE INTO roles (name, description) VALUES
    ('ADMIN',           'Quan tri: toan bo quyen kiem duyet va che tai nguoi dung'),
    ('CONTENT_CREATOR', 'Người biên soạn nội dung');

UPDATE users u
JOIN roles old_role ON old_role.id = u.role_id AND old_role.name = 'SUPER_ADMIN'
JOIN roles new_role ON new_role.name = 'ADMIN'
SET u.role_id = new_role.id;

UPDATE users u
JOIN roles old_role ON old_role.id = u.role_id AND old_role.name = 'CONTENT_ADMIN'
JOIN roles new_role ON new_role.name = 'CONTENT_CREATOR'
SET u.role_id = new_role.id;

DELETE r FROM roles r
LEFT JOIN users u ON u.role_id = r.id
WHERE r.name IN ('SUPER_ADMIN', 'CONTENT_ADMIN') AND u.id IS NULL;
