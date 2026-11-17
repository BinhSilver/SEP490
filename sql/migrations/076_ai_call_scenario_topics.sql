-- [JLAS-AICALL-TOPICS] Thu vien AI Call: chia lai chu de cho 110 kich ban cpath-* cua lo trinh Giao tiep.
--   Truoc day ca nhom WORK va BA nam trong "Lam quen" (70 kich ban), DAILY_LIFE va phan lon TRAVEL nam trong
--   "Doi song" (43), con "Di chuyen", "Mua sam", "Suc khoe" chi co 1-3. Moi kich ban duoc dua ve dung chu de
--   theo noi dung; nhom BrSE (khach hang Nhat, du an phan mem) co chu de rieng "it-brse".
--   Chi doi category_id cua cac slug cpath-* liet ke duoi day; lo trinh Giao tiep tim kich ban theo slug nen
--   khong bi anh huong. Nguon: data/learning-path/comm-aicall/*.json (category_code), seed sinh lai trong
--   sql/learning/learning_data_comm.sql. Chay lai an toan.
SET NAMES utf8mb4;
START TRANSACTION;

INSERT INTO category (code, title, title_ja, description, icon, display_order, is_active) VALUES ('it-brse', 'Công việc IT (BrSE)', 'IT・ブリッジSE', 'Trao đổi với khách hàng Nhật trong dự án phần mềm: đặc tả, tiến độ, kiểm thử và phát hành.', 'fa-solid fa-laptop-code', 9, 1) AS n ON DUPLICATE KEY UPDATE title = n.title, title_ja = n.title_ja, description = n.description, icon = n.icon, display_order = n.display_order, is_active = n.is_active;

-- it-brse: 23
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'it-brse') c)
WHERE slug IN ('cpath-brse-01', 'cpath-brse-02', 'cpath-brse-09', 'cpath-brse-03', 'cpath-brse-07', 'cpath-brse-15',
    'cpath-brse-04', 'cpath-brse-05', 'cpath-brse-08', 'cpath-brse-10', 'cpath-brse-11', 'cpath-brse-16',
    'cpath-brse-17', 'cpath-brse-12', 'cpath-brse-18', 'cpath-brse-19', 'cpath-brse-06', 'cpath-brse-20',
    'cpath-brse-21', 'cpath-brse-13', 'cpath-brse-14', 'cpath-brse-22', 'cpath-test-ba');

-- daily-life: 8
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'daily-life') c)
WHERE slug IN ('cpath-dly-01', 'cpath-dly-02', 'cpath-dly-09', 'cpath-dly-04', 'cpath-dly-06', 'cpath-dly-12',
    'cpath-dly-19', 'cpath-test-daily-life');

-- shopping: 18
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'shopping') c)
WHERE slug IN ('cpath-dly-03', 'cpath-dly-10', 'cpath-dly-11', 'cpath-trv-05', 'cpath-trv-17', 'cpath-trv-18',
    'cpath-wrk-31', 'cpath-wrk-32', 'cpath-wrk-33', 'cpath-wrk-34', 'cpath-wrk-35', 'cpath-wrk-36',
    'cpath-wrk-37', 'cpath-wrk-38', 'cpath-wrk-39', 'cpath-wrk-40', 'cpath-wrk-41', 'cpath-wrk-42');

-- work-study: 31
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'work-study') c)
WHERE slug IN ('cpath-dly-07', 'cpath-dly-15', 'cpath-dly-16', 'cpath-wrk-07', 'cpath-wrk-08', 'cpath-wrk-09',
    'cpath-wrk-10', 'cpath-wrk-11', 'cpath-wrk-12', 'cpath-wrk-16', 'cpath-wrk-17', 'cpath-wrk-18',
    'cpath-wrk-19', 'cpath-wrk-20', 'cpath-wrk-21', 'cpath-wrk-28', 'cpath-wrk-29', 'cpath-wrk-30',
    'cpath-wrk-04', 'cpath-wrk-05', 'cpath-wrk-06', 'cpath-wrk-13', 'cpath-wrk-14', 'cpath-wrk-15',
    'cpath-wrk-22', 'cpath-wrk-23', 'cpath-wrk-24', 'cpath-wrk-25', 'cpath-wrk-26', 'cpath-wrk-27',
    'cpath-test-work');

-- health-service: 4
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'health-service') c)
WHERE slug IN ('cpath-dly-05', 'cpath-dly-13', 'cpath-dly-14', 'cpath-trv-08');

-- introduction: 7
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'introduction') c)
WHERE slug IN ('cpath-dly-08', 'cpath-dly-17', 'cpath-dly-18', 'cpath-dly-20', 'cpath-wrk-01', 'cpath-wrk-02',
    'cpath-wrk-03');

-- transport: 9
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'transport') c)
WHERE slug IN ('cpath-dly-21', 'cpath-trv-01', 'cpath-trv-09', 'cpath-trv-10', 'cpath-trv-02', 'cpath-trv-11',
    'cpath-trv-12', 'cpath-trv-06', 'cpath-trv-07');

-- travel: 7
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'travel') c)
WHERE slug IN ('cpath-trv-03', 'cpath-trv-13', 'cpath-trv-14', 'cpath-test-travel', 'cpath-wrk-43', 'cpath-wrk-44',
    'cpath-wrk-45');

-- food-drink: 3
UPDATE scenario SET category_id = (SELECT id FROM (SELECT id FROM category WHERE code = 'food-drink') c)
WHERE slug IN ('cpath-trv-04', 'cpath-trv-15', 'cpath-trv-16');

COMMIT;
