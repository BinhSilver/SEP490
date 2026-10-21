-- Đổi giọng vai "Nhân viên đón khách" (AI01) trong kịch bản gọi món sang giọng 上条当麻 (a003f932…).
-- Giọng cũ (welcome) đọc câu thứ hai nhanh và lên xuống như hát; giọng mới được chọn sau khi nghe so sánh.
-- Chỉ đổi dữ liệu scenario.flow_json; không đụng phiên/tin nhắn cũ (giữ giọng đã ghi lúc tạo).
-- Mỗi đường dẫn đều kiểm tra mã vai và giọng cũ trước khi cập nhật, nên chạy lại an toàn.
-- Nguồn: data/ai-call/catalog.json + voices.json (migration 015 đã sinh lại khớp).
SET NAMES utf8mb4;
START TRANSACTION;

-- restaurant-order-and-pay
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.stages[0].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[1].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[2].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[3].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[4].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[5].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[6].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[7].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[8].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[9].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[10].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[11].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[12].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[13].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[14].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.stages[15].speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59',
    '$.speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'restaurant-order-and-pay'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[0].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[1].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[2].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[3].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[4].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[5].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[6].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[6].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[7].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[7].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[8].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[8].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[9].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[9].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[10].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[10].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[11].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[11].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[12].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[12].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[13].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[13].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[14].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[14].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[15].speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.stages[15].speakers[1].code')) = 'AI01'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI01';

-- restaurant-order-and-pay--sold-out
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'restaurant-order-and-pay--sold-out'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI01';

-- restaurant-order-and-pay--wrong-order
UPDATE scenario SET flow_json = JSON_SET(flow_json,
    '$.speakers[1].voiceId', 'a003f93264534ec1aeafb1d07dcc5d59')
WHERE slug = 'restaurant-order-and-pay--wrong-order'
  AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].voiceId')) = '9ac93029cca64a4f938969a97235e045' AND JSON_UNQUOTE(JSON_EXTRACT(flow_json, '$.speakers[1].code')) = 'AI01';

COMMIT;
