/* cpath-mock.js — Dữ liệu giả theo đúng hợp đồng API của Lộ trình Giao tiếp (Đợt 2).
 * Chỉ kích hoạt khi URL có ?mock=1 (hoặc root.CPATH_FORCE_MOCK === true khi chạy test).
 * Khi không có ?mock=1 thì giữ nguyên window.JpathApi thật của hệ thống.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathMock = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var STORAGE_KEY = 'jlas_cpath_mock_state_v1';

  function isMockActive() {
    if (root && root.CPATH_FORCE_MOCK === true) return true;
    var search = (root && root.location && root.location.search) ? String(root.location.search) : '';
    return /[?&]mock=1(?:&|$)/.test(search);
  }

  /* Điều hướng linh hoạt: hỗ trợ cả mở file .html trực tiếp lẫn route Spring Boot /learning/comm/... */
  function pageUrl(page, queryObj) {
    var loc = (root && root.location) || {};
    var pathname = String(loc.pathname || '');
    var isDirectHtml = pathname.indexOf('.html') >= 0 || String(loc.protocol || '') === 'file:';
    var base = isDirectHtml ? (page + '.html') : ('/learning/comm/' + (page === 'path' ? 'path' : page));
    var params = [];
    if (queryObj && typeof queryObj === 'object') {
      Object.keys(queryObj).forEach(function (k) {
        if (queryObj[k] !== undefined && queryObj[k] !== null && queryObj[k] !== '') {
          params.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(queryObj[k])));
        }
      });
    }
    if (isMockActive()) params.push('mock=1');
    return params.length ? (base + '?' + params.join('&')) : base;
  }

  /* Đường dẫn ảnh Kitsu tương thích cả khi mở file:// trực tiếp lẫn qua server */
  function kitsuSrc(fileName) {
    var loc = (root && root.location) || {};
    if (String(loc.protocol || '') === 'file:') {
      return '../../static/images/mascots/kitsu/' + fileName;
    }
    return '/images/mascots/kitsu/' + fileName;
  }

  /* 1. Danh mục mục tiêu & ngành (GET /api/learning/comm/goals) */
  var GOALS_DATA = [
    {
      goal: 'TRAVEL',
      nameVi: 'Du lịch',
      topicCount: 6,
      fields: [
        { code: 'TRAVEL_CORE', labelVi: 'Du lịch tự túc & khám phá', topicCodes: ['AIR', 'TRAIN', 'HOTEL', 'FOOD', 'SHOP', 'TROUBLE'] }
      ]
    },
    {
      goal: 'DAILY_LIFE',
      nameVi: 'Đời sống',
      topicCount: 7,
      fields: [
        { code: 'DAILY_CORE', labelVi: 'Sinh hoạt tại Nhật', topicCodes: ['HOME', 'MARKET', 'PUBLIC', 'HEALTH', 'SCHOOL', 'SOCIAL', 'TIME'] }
      ]
    },
    {
      goal: 'WORK',
      nameVi: 'Công việc',
      topicCount: 22,
      fields: [
        { code: 'OFFICE', labelVi: 'Văn phòng chung', topicCodes: ['OFFICE-GREETING', 'OFFICE-REPORT', 'OFFICE-LEAVE', 'OFFICE-PHONE', 'OFFICE-MEETING'] },
        { code: 'IT_BRSE', labelVi: 'IT / BrSE', topicCodes: ['REQ', 'DEV', 'TEST', 'BUG', 'OPS', 'PM', 'COM'] },
        { code: 'FACTORY', labelVi: 'Nhà máy / Sản xuất', topicCodes: ['FACTORY-SAFETY', 'FACTORY-SHIFT', 'FACTORY-INSTRUCTION', 'FACTORY-ERROR', 'FACTORY-5S'] },
        { code: 'SERVICE', labelVi: 'Dịch vụ / Khách sạn', topicCodes: ['SERVICE-RECEPTION', 'SERVICE-PRODUCT', 'SERVICE-PAYMENT', 'SERVICE-COMPLAINT', 'SERVICE-BOOKING'] }
      ]
    }
  ];

  /* Nhãn tiếng Việt cho mã chủ đề (dùng chung cho khảo sát & bản đồ) */
  var TOPIC_LABELS = {
    'AIR': 'Sân bay & xuất nhập cảnh',
    'TRAIN': 'Tàu xe & di chuyển',
    'HOTEL': 'Lưu trú & khách sạn',
    'FOOD': 'Ăn uống & nhà hàng',
    'SHOP': 'Mua sắm & hoàn thuế',
    'TROUBLE': 'Hỏi đường & sự cố',
    'HOME': 'Nhà cửa & tiện ích',
    'MARKET': 'Đi chợ & bữa ăn',
    'PUBLIC': 'Hành chính & ngân hàng',
    'HEALTH': 'Sức khoẻ & bệnh viện',
    'SCHOOL': 'Trường học & học tập',
    'SOCIAL': 'Quan hệ xã hội',
    'TIME': 'Thời gian & thời tiết',
    'OFFICE-GREETING': 'Chào hỏi đồng nghiệp',
    'OFFICE-REPORT': 'Báo cáo & Hou-Ren-Sou',
    'OFFICE-LEAVE': 'Xin phép & nghỉ phép',
    'OFFICE-PHONE': 'Điện thoại công sở',
    'OFFICE-MEETING': 'Họp & thảo luận',
    'REQ': 'Yêu cầu & thiết kế',
    'DEV': 'Phát triển & lập trình',
    'TEST': 'Kiểm thử phần mềm',
    'BUG': 'Lỗi & chất lượng',
    'OPS': 'Môi trường & vận hành',
    'PM': 'Quản lý dự án',
    'COM': 'Giao tiếp dự án',
    'FACTORY-SAFETY': 'An toàn lao động',
    'FACTORY-SHIFT': 'Bàn giao ca',
    'FACTORY-INSTRUCTION': 'Nhận chỉ thị sản xuất',
    'FACTORY-ERROR': 'Báo cáo sự cố máy',
    'FACTORY-5S': 'Quy tắc 5S',
    'SERVICE-RECEPTION': 'Đón tiếp khách hàng',
    'SERVICE-PRODUCT': 'Tư vấn sản phẩm',
    'SERVICE-PAYMENT': 'Thanh toán tại quầy',
    'SERVICE-COMPLAINT': 'Xử lý phản hồi khách',
    'SERVICE-BOOKING': 'Đặt bàn & đặt phòng'
  };

  /* 2. Kho chi tiết các tình huống mẫu (GET /api/learning/comm/situations/{id}) */
  var SITUATIONS_DB = {
    'TRV-01': {
      id: 'TRV-01',
      titleJa: '空港のチェックイン',
      titleVi: 'Làm thủ tục ở sân bay',
      situationVi: 'Làm thủ tục lên máy bay tại quầy sân bay, chọn ghế ngồi cạnh cửa sổ và gửi hành lý.',
      level: 'N4',
      topic: { code: 'AIR', nameVi: 'Sân bay & xuất nhập cảnh' },
      words: [
        { id: 'TRV-001', word: '空港', reading: 'くうこう', meaningVi: 'Sân bay', audioUrl: '' },
        { id: 'TRV-002', word: '予約番号', reading: 'よやくばんごう', meaningVi: 'Mã đặt chỗ', audioUrl: '' },
        { id: 'TRV-018', word: '窓側', reading: 'まどがわ', meaningVi: 'Phía cửa sổ', audioUrl: '' },
        { id: 'TRV-009', word: '預ける', reading: 'あずける', meaningVi: 'Ký gửi (hành lý)', audioUrl: '' },
        { id: 'TRV-003', word: '搭乗口', reading: 'とうじょうぐち', meaningVi: 'Cửa lên máy bay', audioUrl: '' },
        { id: 'TRV-020', word: '追加料金', reading: 'ついかりょうきん', meaningVi: 'Phí phụ thu', audioUrl: '' }
      ],
      phrases: [
        { id: 'TRV-01-P01', ja: '〜をお願いします', reading: '〜をおねがいします', vi: 'Cho tôi xin / nhờ ~ ạ', note: 'Mẫu câu đề nghị lịch sự dùng xuyên suốt tại sân bay, nhà ga và cửa hàng.', audioUrl: '' },
        { id: 'TRV-01-P02', ja: '窓側の席は空いていますか', reading: 'まどがわのせきはあいていますか', vi: 'Còn ghế cạnh cửa sổ không ạ?', note: 'Dùng khi muốn hỏi xin chỗ ngồi mong muốn trên máy bay hoặc tàu.', audioUrl: '' },
        { id: 'TRV-01-P03', ja: '搭乗口はどこですか', reading: 'とうじょうぐちはどこですか', vi: 'Cửa lên máy bay ở đâu ạ?', note: 'Hỏi vị trí cổng ra tàu bay sau khi nhận thẻ lên máy bay.', audioUrl: '' }
      ],
      lines: [
        { order: 1, speaker: '係員', ja: 'おはようございます。パスポートと予約番号をお願いします。', furigana: 'おはようございます。パスポートと{予約|よやく}{番号|ばんごう}をお{願|ねが}いします。', vi: 'Chào bạn. Cho tôi xin hộ chiếu và mã đặt chỗ ạ.', audioUrl: '', learner: false },
        { order: 2, speaker: 'リン', ja: 'はい、これです。窓側の席は空いていますか。', furigana: 'はい、これです。{窓側|まどがわ}の{席|せき}は{空|あ}いていますか。', vi: 'Vâng, đây ạ. Còn ghế cạnh cửa sổ không ạ?', audioUrl: '', learner: true },
        { order: 3, speaker: '係員', ja: '前方でしたら一席ご用意できます。', furigana: '{前方|ぜんぽう}でしたら{一席|いっせき}ご{用意|ようい}できます。', vi: 'Nếu ở phía trước thì tôi có thể sắp xếp một ghế ạ.', audioUrl: '', learner: false },
        { order: 4, speaker: 'リン', ja: 'お願いします。預ける荷物は一つです。', furigana: 'お{願|ねが}いします。{預|あず}ける{荷物|にもつ}は{一|ひと}つです。', vi: 'Cho tôi ghế đó ạ. Tôi gửi một kiện hành lý.', audioUrl: '', learner: true },
        { order: 5, speaker: '係員', ja: '重さを量りますので、こちらに載せてください。', furigana: '{重|おも}さを{量|はか}りますので、こちらに{載|の}せてください。', vi: 'Tôi cân hành lý nên bạn đặt lên đây giúp tôi nhé.', audioUrl: '', learner: false },
        { order: 6, speaker: 'リン', ja: '分かりました。搭乗口はどこですか。', furigana: '{分|わ}かりました。{搭乗|とうじょう}{口|ぐち}はどこですか。', vi: 'Tôi hiểu rồi. Cửa lên máy bay ở đâu ạ?', audioUrl: '', learner: true },
        { order: 7, speaker: '係員', ja: '十五番です。三十分前までにお越しください。', furigana: '{十|じゅう}{五|ご}{番|ばん}です。{三十分|さんじゅっぷん}{前|まえ}までにお{越|こ}しください。', vi: 'Cửa số 15 ạ. Xin bạn đến trước 30 phút nhé.', audioUrl: '', learner: false }
      ],
      challenge: {
        turns: [
          {
            line: 2,
            options: [
              { ja: 'はい、これです。窓側の席は空いていますか。', vi: 'Vâng, đây ạ. Còn ghế cạnh cửa sổ không ạ?' },
              { ja: 'はい、パスポートをお願いします。', vi: 'Vâng, cho tôi xin hộ chiếu ạ.' },
              { ja: '荷物はもう受け取りました。', vi: 'Tôi đã nhận hành lý rồi ạ.' }
            ],
            answerIndex: 0,
            whyVi: 'Nhân viên xin hộ chiếu nên リン đưa ra (はい、これです) rồi hỏi xin ghế cạnh cửa sổ; xin lại hộ chiếu là ngược vai.'
          },
          {
            line: 4,
            options: [
              { ja: 'じゃ、窓側の席はないんですね。', vi: 'Vậy là không còn ghế cạnh cửa sổ nhỉ.' },
              { ja: 'お願いします。預ける荷物は一つです。', vi: 'Cho tôi ghế đó ạ. Tôi gửi một kiện hành lý.' },
              { ja: '後ろの席に変えてください。', vi: 'Hãy đổi cho tôi ghế phía sau.' }
            ],
            answerIndex: 1,
            whyVi: 'Nhân viên báo còn một ghế phía trước nên リン đồng ý nhận ghế (お願いします) và báo số kiện hành lý ký gửi.'
          },
          {
            line: 6,
            options: [
              { ja: '分かりました。搭乗口はどこですか。', vi: 'Tôi hiểu rồi. Cửa lên máy bay ở đâu ạ?' },
              { ja: '到着は何時ですか。', vi: 'Mấy giờ thì hạ cánh ạ?' },
              { ja: '毛布をもう一枚もらえますか。', vi: 'Cho tôi xin thêm một chiếc chăn được không ạ?' }
            ],
            answerIndex: 0,
            whyVi: 'Sau khi đặt hành lý lên cân để hoàn tất thủ tục, câu hỏi hợp lý nhất tại quầy là hỏi cửa lên máy bay (搭乗口はどこですか).'
          }
        ]
      },
      steps: { words: true, listen: true, roleplay: true, speak: true },
      aiCallUrl: '/aicall?scenario=TRV-01'
    },
    'TRV-05': {
      id: 'TRV-05',
      titleJa: '乗り換えとホームの確認',
      titleVi: 'Hỏi sân ga và đổi tàu',
      situationVi: 'Ở nhà ga đông đúc tại Tokyo, リン hỏi nhân viên đường tàu đi Shinjuku và điểm đổi tàu.',
      level: 'N4',
      topic: { code: 'TRAIN', nameVi: 'Tàu xe & di chuyển' },
      words: [
        { id: 'TRV-101', word: '乗り換え', reading: 'のりかえ', meaningVi: 'Đổi tàu / chuyển tuyến', audioUrl: '' },
        { id: 'TRV-102', word: '番線', reading: 'ばんせん', meaningVi: 'Đường ray / sân ga số...', audioUrl: '' },
        { id: 'TRV-103', word: '急行', reading: 'きゅうこう', meaningVi: 'Tàu tốc hành', audioUrl: '' },
        { id: 'TRV-104', word: '各駅停車', reading: 'かくえきていしゃ', meaningVi: 'Tàu dừng mọi ga', audioUrl: '' },
        { id: 'TRV-105', word: '改札', reading: 'かいさつ', meaningVi: 'Cửa soát vé', audioUrl: '' },
        { id: 'TRV-106', word: '方面', reading: 'ほうめん', meaningVi: 'Hướng đi về phía...', audioUrl: '' }
      ],
      phrases: [
        { id: 'TRV-05-P01', ja: '〜へ行きたいんですが、何番線ですか', reading: '〜へいきたいんですが、なんばんせんですか', vi: 'Tôi muốn đi đến ~, ở sân ga số mấy ạ?', note: 'Mẫu câu mở đầu tự nhiên với んですが để nhờ nhân viên nhà ga chỉ dẫn.', audioUrl: '' },
        { id: 'TRV-05-P02', ja: 'どこで乗り換えますか', reading: 'どこでのりかえますか', vi: 'Đổi tàu ở đâu ạ?', note: 'Hỏi ga trung chuyển khi không có tàu đi thẳng.', audioUrl: '' },
        { id: 'TRV-05-P03', ja: 'この電車は〜に止まりますか', reading: 'このでんしゃは〜にとまりますか', vi: 'Tàu này có dừng ở ga ~ không ạ?', note: 'Xác nhận nhanh trước khi bước lên tàu tốc hành.', audioUrl: '' }
      ],
      lines: [
        { order: 1, speaker: 'リン', ja: 'すみません、新宿へ行きたいんですが、何番線ですか。', furigana: 'すみません、{新宿|しんじゅく}へ{行|い}きたいんですが、{何番線|なんばんせん}ですか。', vi: 'Xin lỗi, tôi muốn đi Shinjuku thì vào sân ga số mấy ạ?', audioUrl: '', learner: true },
        { order: 2, speaker: '駅員', ja: '三番線ですよ。階段を上がって右側です。', furigana: '{三番線|さんばんせん}ですよ。{階段|かいだん}を{上|あ}がって{右側|みぎがわ}です。', vi: 'Sân ga số 3 nhé. Đi lên cầu thang rồi rẽ bên phải ạ.', audioUrl: '', learner: false },
        { order: 3, speaker: 'リン', ja: '途中で乗り換えはありますか。', furigana: '{途中|とちゅう}で{乗|の}り{換|か}えはありますか。', vi: 'Giữa đường có phải đổi tàu không ạ?', audioUrl: '', learner: true },
        { order: 4, speaker: '駅員', ja: 'いいえ、山手線なら乗り換えなしで行けます。', furigana: 'いいえ、{山手線|やまのてせん}なら{乗|の}り{換|か}えなしで{行|い}けます。', vi: 'Không ạ, đi tuyến Yamanote thì đi thẳng không cần đổi tàu.', audioUrl: '', learner: false },
        { order: 5, speaker: 'リン', ja: '次の電車は何分後に来ますか。', furigana: '{次|つぎ}の{電車|でんしゃ}は{何分後|なんぷんご}に{来|き}ますか。', vi: 'Mấy phút nữa thì có chuyến tàu tiếp theo ạ?', audioUrl: '', learner: true },
        { order: 6, speaker: '駅員', ja: 'あと三分ほどで参ります。', furigana: 'あと{三分|さんぷん}ほどで{参|まい}ります。', vi: 'Khoảng 3 phút nữa tàu sẽ tới ạ.', audioUrl: '', learner: false }
      ],
      challenge: {
        turns: [
          {
            line: 1,
            options: [
              { ja: 'すみません、新宿へ行きたいんですが、何番線ですか。', vi: 'Xin lỗi, tôi muốn đi Shinjuku thì vào sân ga số mấy ạ?' },
              { ja: '新宿駅はもう着きましたか。', vi: 'Đã tới ga Shinjuku chưa ạ?' },
              { ja: '切符を払い戻したいです。', vi: 'Tôi muốn hoàn tiền vé tàu ạ.' }
            ],
            answerIndex: 0,
            whyVi: 'Khi đang ở nhà ga và cần tìm đường lên tàu đi Shinjuku, hỏi 何番線ですか (sân ga số mấy) là tự nhiên và đúng mục đích nhất.'
          },
          {
            line: 3,
            options: [
              { ja: '階段はどこにありますか。', vi: 'Cầu thang ở đâu vậy ạ?' },
              { ja: '途中で乗り換えはありますか。', vi: 'Giữa đường có phải đổi tàu không ạ?' },
              { ja: '指定席はいくらですか。', vi: 'Ghế đặt chỗ giá bao nhiêu tiền ạ?' }
            ],
            answerIndex: 1,
            whyVi: 'Sau khi biết sân ga số 3, リン hỏi thêm 途中で乗り換えはありますか để biết có cần chuyển tuyến giữa chừng không.'
          },
          {
            line: 5,
            options: [
              { ja: '次の電車は何分後に来ますか。', vi: 'Mấy phút nữa thì có chuyến tàu tiếp theo ạ?' },
              { ja: '終電は何時でしたか。', vi: 'Chuyến tàu cuối là mấy giờ vậy ạ?' },
              { ja: '荷物を預けてもいいですか。', vi: 'Tôi gửi hành lý được không ạ?' }
            ],
            answerIndex: 0,
            whyVi: 'Biết đi thẳng bằng tuyến Yamanote rồi thì hỏi 次の電車は何分後に来ますか (mấy phút nữa tàu tới) để kịp lên sân ga.'
          }
        ]
      },
      steps: { words: false, listen: false, roleplay: false, speak: false },
      aiCallUrl: '/aicall?scenario=TRV-05'
    }
  };

  function getTodayIso() {
    var d = new Date(Date.now() - new Date().getTimezoneOffset() * 60000);
    return d.toISOString().slice(0, 10);
  }

  function shiftIso(isoStr, deltaDays) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(isoStr || ''));
    var d = m ? new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])) : new Date();
    d.setUTCDate(d.getUTCDate() + deltaDays);
    return d.toISOString().slice(0, 10);
  }

  function defaultState() {
    var todayIso = getTodayIso();
    return {
      pathId: 'cpath-mock-01',
      goal: 'TRAVEL',
      nameVi: 'Du lịch Nhật Bản',
      selfLevel: 'ELEMENTARY',
      dailySituations: 2,
      restDays: ['SUN'],
      finishDate: shiftIso(todayIso, 28),
      extraAddedToday: false,
      display: {
        furigana: true,
        romaji: false,
        translation: true
      },
      progress: {
        doneSituations: 4,
        totalSituations: 18,
        passedTopics: 1,
        totalTopics: 6
      },
      secondary: {
        goal: 'DAILY_LIFE',
        nameVi: 'Đời sống hằng ngày'
      },
      situationSteps: {
        'TRV-01': { words: true, listen: true, roleplay: true, speak: true, done: true, score: 92 },
        'TRV-02': { words: true, listen: true, roleplay: true, speak: true, done: true, score: 88 },
        'TRV-03': { words: true, listen: true, roleplay: true, speak: true, done: true, score: 85 },
        'TRV-04': { words: true, listen: true, roleplay: true, speak: true, done: true, score: 90 },
        'TRV-05': { words: false, listen: false, roleplay: false, speak: false, done: false, score: null },
        'TRV-06': { words: false, listen: false, roleplay: false, speak: false, done: false, score: null }
      }
    };
  }

  function loadState() {
    var base = defaultState();
    try {
      if (root && root.sessionStorage) {
        var raw = root.sessionStorage.getItem(STORAGE_KEY);
        if (raw) {
          var parsed = JSON.parse(raw);
          return Object.assign(base, parsed);
        }
      }
    } catch (e) { /* ignore storage errors */ }
    return base;
  }

  function saveState(st) {
    try {
      if (root && root.sessionStorage) {
        root.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(st));
      }
    } catch (e) { /* ignore storage errors */ }
    return st;
  }

  function buildPathResponse(st) {
    var todayIso = getTodayIso();
    var monIso = shiftIso(todayIso, -((new Date().getDay() + 6) % 7));
    var week = [];
    for (var i = 0; i < 7; i++) {
      var dIso = shiftIso(monIso, i);
      var isSun = i === 6;
      week.push({
        day: dIso,
        kind: isSun ? 'REST' : 'STUDY',
        done: dIso < todayIso,
        today: dIso === todayIso
      });
    }
    var sit5 = st.situationSteps && st.situationSteps['TRV-05'];
    var sit6 = st.situationSteps && st.situationSteps['TRV-06'];
    var todaySituations = [
      {
        id: 'TRV-04',
        titleVi: 'Mua vé tàu điện tại máy tự động',
        topicCode: 'TRAIN',
        status: 'DONE'
      },
      {
        id: 'TRV-05',
        titleVi: 'Hỏi sân ga và đổi tàu',
        topicCode: 'TRAIN',
        status: (sit5 && sit5.done) ? 'TODAY_DONE' : 'TODAY'
      }
    ];
    if (st.extraAddedToday) {
      todaySituations.push({
        id: 'TRV-06',
        titleVi: 'Đi xe buýt và hỏi điểm xuống',
        topicCode: 'TRAIN',
        status: (sit6 && sit6.done) ? 'TODAY_DONE' : 'TODAY'
      });
    }
    return {
      pathId: st.pathId,
      goal: st.goal,
      nameVi: st.nameVi,
      selfLevel: st.selfLevel,
      dailySituations: st.dailySituations,
      display: st.display,
      progress: st.progress,
      week: week,
      today: {
        situations: todaySituations,
        weeklyTestDay: shiftIso(monIso, 5)
      },
      secondary: st.secondary
    };
  }

  function buildMapResponse(st) {
    var todayIso = getTodayIso();
    var sit5Done = st.situationSteps && st.situationSteps['TRV-05'] && st.situationSteps['TRV-05'].done;
    return {
      currentWeek: 2,
      weeks: [
        {
          index: 1,
          from: shiftIso(todayIso, -7),
          to: shiftIso(todayIso, -1),
          items: [
            { type: 'SITUATION', id: 'TRV-01', titleVi: 'Làm thủ tục ở sân bay', titleJa: '空港のチェックイン', topicCode: 'AIR', topicNameVi: 'Sân bay & xuất nhập cảnh', status: 'DONE', score: 92 },
            { type: 'SITUATION', id: 'TRV-02', titleVi: 'Khai báo hải quan & nhập cảnh', titleJa: '入国審査と税関', topicCode: 'AIR', topicNameVi: 'Sân bay & xuất nhập cảnh', status: 'DONE', score: 88 },
            { type: 'SITUATION', id: 'TRV-03', titleVi: 'Nhờ giúp đỡ trên máy bay', titleJa: '機内でお願い', topicCode: 'AIR', topicNameVi: 'Sân bay & xuất nhập cảnh', status: 'DONE', score: 85 },
            { type: 'TOPIC_TEST', id: 'TOPIC-AIR', titleVi: 'Bài nhập vai: Sân bay & xuất nhập cảnh', titleJa: '空港ロールプレイ', topicCode: 'AIR', topicNameVi: 'Sân bay & xuất nhập cảnh', status: 'DONE', score: 86 }
          ]
        },
        {
          index: 2,
          from: todayIso,
          to: shiftIso(todayIso, 6),
          items: [
            { type: 'SITUATION', id: 'TRV-04', titleVi: 'Mua vé tàu điện tại máy tự động', titleJa: '券売機で切符を買う', topicCode: 'TRAIN', topicNameVi: 'Tàu xe & di chuyển', status: 'DONE', score: 90 },
            { type: 'SITUATION', id: 'TRV-05', titleVi: 'Hỏi sân ga và đổi tàu', titleJa: '乗り換えとホームの確認', topicCode: 'TRAIN', topicNameVi: 'Tàu xe & di chuyển', status: sit5Done ? 'TODAY_DONE' : 'TODAY', score: sit5Done ? 89 : null },
            { type: 'SITUATION', id: 'TRV-06', titleVi: 'Đi xe buýt và hỏi điểm xuống', titleJa: 'バスでの移動', topicCode: 'TRAIN', topicNameVi: 'Tàu xe & di chuyển', status: st.extraAddedToday ? 'TODAY' : 'NEXT', score: null },
            { type: 'TOPIC_TEST', id: 'TOPIC-TRAIN', titleVi: 'Bài nhập vai: Tàu xe & di chuyển', titleJa: '移動ロールプレイ', topicCode: 'TRAIN', topicNameVi: 'Tàu xe & di chuyển', status: 'NEXT', score: null },
            { type: 'SITUATION', id: 'TRV-07', titleVi: 'Nhận phòng khách sạn (Check-in)', titleJa: 'ホテルのチェックイン', topicCode: 'HOTEL', topicNameVi: 'Lưu trú & khách sạn', status: 'NEXT', score: null }
          ]
        },
        {
          index: 3,
          from: shiftIso(todayIso, 7),
          to: shiftIso(todayIso, 13),
          items: [
            { type: 'SITUATION', id: 'TRV-08', titleVi: 'Gọi món tại nhà hàng địa phương', titleJa: 'レストランで注文する', topicCode: 'FOOD', topicNameVi: 'Ăn uống & nhà hàng', status: 'LOCKED', score: null },
            { type: 'SITUATION', id: 'TRV-09', titleVi: 'Mua sắm & làm thủ tục hoàn thuế', titleJa: '免税手続き', topicCode: 'SHOP', topicNameVi: 'Mua sắm & hoàn thuế', status: 'LOCKED', score: null },
            { type: 'TOPIC_TEST', id: 'TOPIC-HOTEL-FOOD', titleVi: 'Bài nhập vai: Lưu trú & Ẩm thực', titleJa: '滞在ロールプレイ', topicCode: 'FOOD', topicNameVi: 'Ăn uống & nhà hàng', status: 'LOCKED', score: null },
            { type: 'GOAL_TEST', id: 'GOAL-TRAVEL', titleVi: 'Bài test với AI: Du lịch Nhật Bản', titleJa: '旅行総合テスト', topicCode: 'ALL', topicNameVi: 'Tổng hợp mục tiêu Du lịch', status: 'LOCKED', score: null }
          ]
        }
      ]
    };
  }

  function getSituationDetail(id, st) {
    var base = SITUATIONS_DB[id] || SITUATIONS_DB['TRV-05'];
    var clone = JSON.parse(JSON.stringify(base));
    clone.id = id || clone.id;
    var savedSteps = st.situationSteps && st.situationSteps[clone.id];
    if (savedSteps) {
      clone.steps = {
        words: !!savedSteps.words,
        listen: !!savedSteps.listen,
        roleplay: !!savedSteps.roleplay,
        speak: !!savedSteps.speak
      };
    }
    return clone;
  }

  function handleGet(url) {
    var st = loadState();
    var u = String(url || '');
    if (u.indexOf('/api/learning/comm/goals') >= 0) {
      return Promise.resolve(JSON.parse(JSON.stringify(GOALS_DATA)));
    }
    if (u.indexOf('/api/learning/comm/map') >= 0) {
      return Promise.resolve(buildMapResponse(st));
    }
    if (u.indexOf('/api/learning/comm/situations/') >= 0) {
      var mSit = /\/api\/learning\/comm\/situations\/([^/?#]+)/.exec(u);
      var sitId = mSit ? decodeURIComponent(mSit[1]) : 'TRV-05';
      return Promise.resolve(getSituationDetail(sitId, st));
    }
    if (u.indexOf('/api/learning/comm/day') >= 0) {
      var mDay = /[?&]d=([^&#]+)/.exec(u);
      var dayIso = mDay ? decodeURIComponent(mDay[1]) : getTodayIso();
      return Promise.resolve({
        day: dayIso,
        cp: 35,
        situations: [
          {
            id: 'TRV-04',
            titleVi: 'Mua vé tàu điện tại máy tự động',
            titleJa: '券売機で切符を買う',
            topicCode: 'TRAIN',
            topicNameVi: 'Tàu xe & di chuyển',
            status: 'DONE',
            steps: { words: true, listen: true, roleplay: true, speak: true },
            score: 90
          },
          {
            id: 'TRV-05',
            titleVi: 'Hỏi sân ga và đổi tàu',
            titleJa: '乗り換えとホームの確認',
            topicCode: 'TRAIN',
            topicNameVi: 'Tàu xe & di chuyển',
            status: (st.situationSteps && st.situationSteps['TRV-05'] && st.situationSteps['TRV-05'].done) ? 'DONE' : 'TODAY',
            steps: (st.situationSteps && st.situationSteps['TRV-05']) || { words: true, listen: true, roleplay: false, speak: false },
            score: 86
          }
        ],
        scores: [
          { labelVi: 'Từ & mẫu câu', score: 100, noteVi: '9/9 thẻ nhớ tốt' },
          { labelVi: 'Nhập vai chọn câu', score: 90, noteVi: '3/3 lượt trả lời đúng' },
          { labelVi: 'Luyện nói phát âm', score: 86, noteVi: 'Độ trôi chảy 84 · Độ chính xác 88' }
        ]
      });
    }
    if (u.indexOf('/api/learning/comm/path') >= 0) {
      return Promise.resolve(buildPathResponse(st));
    }
    return Promise.resolve(null);
  }

  function handleSend(method, url, body) {
    var st = loadState();
    var u = String(url || '');
    if (method === 'GET') return handleGet(u);

    if (u.indexOf('/api/learning/comm/path') >= 0 && method === 'POST') {
      var payload = body || {};
      var goalMeta = GOALS_DATA.filter(function (g) { return g.goal === payload.goal; })[0] || GOALS_DATA[0];
      st.goal = goalMeta.goal;
      st.nameVi = goalMeta.nameVi;
      st.selfLevel = payload.selfLevel || 'ELEMENTARY';
      st.dailySituations = Number(payload.dailySituations) || 2;
      st.restDays = Array.isArray(payload.restDays) ? payload.restDays : ['SUN'];
      st.finishDate = payload.finishDate || st.finishDate;
      saveState(st);
      return Promise.resolve({ pathId: st.pathId });
    }

    if (u.indexOf('/api/learning/comm/today/extra') >= 0 && method === 'POST') {
      if (!st.extraAddedToday) {
        st.extraAddedToday = true;
        saveState(st);
        return Promise.resolve({ id: 'TRV-06' });
      }
      return Promise.resolve({});
    }

    var mStep = /\/api\/learning\/comm\/situations\/([^/]+)\/steps\/(WORDS|LISTEN|ROLEPLAY|SPEAK)/i.exec(u);
    if (mStep && method === 'POST') {
      var sitId = decodeURIComponent(mStep[1]);
      var stepKey = mStep[2].toLowerCase();
      st.situationSteps = st.situationSteps || {};
      var cur = st.situationSteps[sitId] || { words: false, listen: false, roleplay: false, speak: false, done: false };
      cur[stepKey] = true;
      if (stepKey === 'speak' && body && typeof body === 'object') {
        if (typeof body.score === 'number') {
          cur.score = body.score;
          cur.skippedMic = false;
        } else {
          cur.score = null;
          cur.skippedMic = true;
        }
      }
      var allDone = !!(cur.words && cur.listen && cur.roleplay && cur.speak);
      if (allDone && !cur.done) {
        cur.done = true;
        st.progress.doneSituations = Math.min(st.progress.totalSituations, (st.progress.doneSituations || 4) + 1);
      }
      st.situationSteps[sitId] = cur;
      saveState(st);
      return Promise.resolve({
        steps: { words: cur.words, listen: cur.listen, roleplay: cur.roleplay, speak: cur.speak },
        situationDone: allDone,
        topicPassed: allDone && typeof cur.score === 'number' && cur.score >= 70,
        cp: allDone ? 25 : 5
      });
    }

    if (u.indexOf('/api/learning/comm/speak') >= 0) {
      return Promise.resolve(mockSpeakResult(body));
    }

    return Promise.resolve({ ok: true });
  }

  function mockSpeakResult(formOrBody) {
    var st = loadState();
    var text = '';
    if (formOrBody && typeof formOrBody.get === 'function') {
      text = String(formOrBody.get('text') || '');
    } else if (formOrBody && formOrBody.text) {
      text = String(formOrBody.text);
    }
    var lowMode = !!(
      (formOrBody && formOrBody._simulateLowScore === true)
      || (root && root.CPATH_MOCK_LOW_SCORE === true)
    );
    var clean = text.replace(/[。、！？\s]/g, '');
    var chunks = [];
    for (var i = 0; i < clean.length; i += 3) {
      chunks.push(clean.slice(i, i + 3));
    }
    if (!chunks.length) chunks = ['すみません', '新宿へ', '行きたい'];
    var words = chunks.map(function (w, idx) {
      return {
        word: w,
        score: lowMode ? (idx === 1 ? 58 : 66) : (82 + ((idx * 5) % 15))
      };
    });
    var score = lowMode ? 64 : 88;
    var curAttempts = typeof st.attemptsLeft === 'number' ? st.attemptsLeft : 30;
    var nextAttempts = Math.max(0, curAttempts - 1);
    st.attemptsLeft = nextAttempts;
    saveState(st);
    return {
      score: score,
      accuracy: lowMode ? 65 : 90,
      fluency: lowMode ? 62 : 86,
      completeness: lowMode ? 66 : 89,
      words: words,
      recognizedText: text || 'すみません、新宿へ行きたいんですが、何番線ですか。',
      feedbackVi: lowMode
        ? 'Một vài âm còn chưa rõ (dưới 70 điểm). Bạn hãy nghe mẫu và bấm "Nói lại" nhé!'
        : 'Phát âm rõ ràng, nhịp điệu tự nhiên và đầy đủ các từ trong câu.',
      attemptsLeft: nextAttempts
    };
  }

  function handleUpload(url, form) {
    if (String(url || '').indexOf('/api/learning/comm/speak') >= 0) {
      return Promise.resolve(mockSpeakResult(form));
    }
    return handleSend('POST', url, form);
  }

  function addExtraSituationToday() {
    var st = loadState();
    st.extraAddedToday = true;
    saveState(st);
    return buildPathResponse(st);
  }

  /* Chỉ ghi đè window.JpathApi khi URL có ?mock=1 */
  function installIfMock() {
    if (!isMockActive()) return false;
    if (root) {
      root.JpathApi = {
        get: handleGet,
        send: handleSend,
        upload: handleUpload
      };
    }
    return true;
  }

  installIfMock();

  return {
    isMockActive: isMockActive,
    installIfMock: installIfMock,
    pageUrl: pageUrl,
    kitsuSrc: kitsuSrc,
    TOPIC_LABELS: TOPIC_LABELS,
    GOALS_DATA: GOALS_DATA,
    SITUATIONS_DB: SITUATIONS_DB,
    addExtraSituationToday: addExtraSituationToday,
    mockSpeakResult: mockSpeakResult
  };
});
