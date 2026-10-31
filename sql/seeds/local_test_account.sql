-- Tai khoan test cho database tu dien khoi phuc tu seed (database seed khong co nguoi dung).
-- Chi dung cho may local. Dang nhap: tester@jlas.local / Test@12345
-- Chay: mysql --default-character-set=utf8mb4 -u root -p jlas_dictionary_test < sql/seeds/local_test_account.sql

INSERT IGNORE INTO roles (name, description) VALUES ('LEARNER', 'Learner');

INSERT INTO users (role_id, email, username, password_hash, login_type, full_name, is_verified)
SELECT r.id, 'tester@jlas.local', 'tester',
       '$2a$10$HitusBG3VYWoijHTeY2CXuoHMWWs4GIFdkuyTUO7vVOGoY2zg0ply',
       'LOCAL', 'Tai khoan test tu dien', 1
FROM roles r
WHERE r.name = 'LEARNER'
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash), is_verified = 1;
