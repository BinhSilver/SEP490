(function (global) {
  'use strict';

  /** @typedef {{text: string, reading?: string}} Token */
  /** @typedef {{expression: string, reading: string, hanViet?: string, meaning?: string, jlptLevel?: string, senses: Array, kanjiComposition?: Array, verified?: boolean, related?: Array}} Word */
  /** @typedef {{character: string, strokeCount: number, hanViet: string, meaningVi: string, jlptLevel: string, readings: Array, components?: Array, examples?: Array}} Kanji */
  /** @typedef {{tokens: Token[], vi: string}} Example */
  /** @typedef {{id: string, pattern: string, shortMeaning: string, jlptLevel: string, functions: string, structures: string[], explanation: string, examples: Example[]}} Grammar */
  /** @typedef {{word: string, reading: string, definition: string, phrases: string[], related: Array}} JaJaEntry */

  var words = {
    '募集': {
      expression: '募集', reading: 'ぼしゅう', hanViet: 'MỘ TẬP', meaning: 'chiêu mộ; tuyển dụng',
      jlptLevel: 'N2', verified: true, usageScope: 'BOTH',
      senses: [
        { senseOrder: 1, partsOfSpeech: ['Danh từ, danh từ làm trợ từ cho động từ suru', 'Động từ nhóm III (する)'],
          domains: [], glosses: [{ text: 'sự chiêu mộ; sự tuyển mộ; tuyển dụng' }, { text: 'sự quyên góp' }] },
        { senseOrder: 2, partsOfSpeech: ['Danh từ, danh từ làm trợ từ cho động từ suru', 'Động từ nhóm III (する)'],
          domains: ['Kinh tế'], glosses: [{ text: 'sự huy động (vốn)' }] }
      ],
      related: [
        { expression: '募金', reading: 'ぼきん', meaning: 'quyên góp tiền' },
        { expression: '応募', reading: 'おうぼ', meaning: 'ứng tuyển' },
        { expression: '公募', reading: 'こうぼ', meaning: 'tuyển chọn công khai' },
        { expression: '急募', reading: 'きゅうぼ', meaning: 'tuyển gấp' },
        { expression: '募る', reading: 'つのる', meaning: 'chiêu mộ; kêu gọi' },
        { expression: '募集要項', reading: 'ぼしゅうようこう', meaning: 'thông tin tuyển chọn' }
      ],
      kanjiComposition: [{ position: 1, character: '募', jlptLevel: 'N2' },
        { position: 2, character: '集', jlptLevel: 'N3' }]
    }
  };

  var kanji = {
    '募': { character: '募', strokeCount: 12, jlptLevel: 'N2', frequency: '809/2500',
      hanViet: 'MỘ', meaningVi: 'chiêu mộ; kêu gọi', strokeSvgUri: null,
      components: [{ character: '力', hanViet: 'LỰC' }, { character: '莫', hanViet: 'MẠC' }],
      readings: [{ readingType: 'KUN', readingText: 'つの.る' }, { readingType: 'ON', readingText: 'ボ' }],
      examples: [{ reading: 'ボ', words: ['募集（ぼしゅう）', '募金（ぼきん）'] },
        { reading: 'つの.る', words: ['募る（つのる）'] }],
      sentences: [{ tokens: [{ text: '新しい', reading: 'あたらしい' }, { text: '仲間', reading: 'なかま' },
        { text: 'を募集しています。' }], vi: 'Chúng tôi đang tuyển thành viên mới.' }] },
    '集': { character: '集', strokeCount: 12, jlptLevel: 'N3', hanViet: 'TẬP',
      meaningVi: 'tập hợp; thu thập', strokeSvgUri: null,
      components: [{ character: '隹', hanViet: 'CHUY' }, { character: '木', hanViet: 'MỘC' }],
      readings: [{ readingType: 'KUN', readingText: 'あつ.まる' }, { readingType: 'KUN', readingText: 'あつ.める' },
        { readingType: 'KUN', readingText: 'つど.う' }, { readingType: 'ON', readingText: 'シュウ' }],
      examples: [{ reading: 'シュウ', words: ['集合（しゅうごう）', '集団（しゅうだん）'] },
        { reading: 'あつ.める', words: ['集める（あつめる）'] }],
      sentences: [{ tokens: [{ text: '駅', reading: 'えき' }, { text: 'に集まりましょう。' }],
        vi: 'Chúng ta hãy tập trung ở ga.' }] }
  };

  var examples = {
    '募集': [
      { tokens: [{ text: 'アルバイト' }, { text: 'を募集しています。' }], vi: 'Chúng tôi đang tuyển nhân viên làm thêm.' },
      { tokens: [{ text: '参加者', reading: 'さんかしゃ' }, { text: 'を募集します。' }], vi: 'Chúng tôi sẽ tuyển người tham gia.' },
      { tokens: [{ text: '新しい', reading: 'あたらしい' }, { text: '社員', reading: 'しゃいん' }, { text: 'を募集しています。' }], vi: 'Chúng tôi đang tuyển nhân viên mới.' },
      { tokens: [{ text: '寄付', reading: 'きふ' }, { text: 'を募集しています。' }], vi: 'Chúng tôi đang kêu gọi quyên góp.' },
      { tokens: [{ text: 'ボランティア' }, { text: 'を募集しています。' }], vi: 'Chúng tôi đang tuyển tình nguyện viên.' },
      { tokens: [{ text: '応募者', reading: 'おうぼしゃ' }, { text: 'の募集を締め切りました。' }], vi: 'Đã hết hạn tuyển người ứng tuyển.' }
    ]
  };

  var grammars = [
    { id: 'tame-ni', pattern: 'ために', shortMeaning: 'để; vì', jlptLevel: 'N4',
      functions: 'Chỉ mục đích hoặc nguyên nhân', structures: ['V (thể từ điển) + ために、', 'Nの + ために、'],
      explanation: 'Diễn tả mục đích của hành động hoặc lý do dẫn đến sự việc.',
      examples: [
        { tokens: [{ text: '日本語', reading: 'にほんご' }, { text: 'を勉強するために、' }, { text: '日本', reading: 'にほん' }, { text: 'へ来ました。' }], vi: 'Tôi đã đến Nhật để học tiếng Nhật.' },
        { tokens: [{ text: '家族', reading: 'かぞく' }, { text: 'のために、' }, { text: '毎日', reading: 'まいにち' }, { text: '働いています。' }], vi: 'Tôi làm việc mỗi ngày vì gia đình.' },
        { tokens: [{ text: '健康', reading: 'けんこう' }, { text: 'のために、' }, { text: '毎朝', reading: 'まいあさ' }, { text: '走っています。' }], vi: 'Tôi chạy bộ mỗi sáng vì sức khỏe.' }
      ] },
    { id: 'tame-ka', pattern: 'ためか', shortMeaning: 'có lẽ vì', jlptLevel: 'N3', functions: 'Suy đoán nguyên nhân', structures: ['V / A / N + ためか'], explanation: 'Nêu nguyên nhân chưa chắc chắn.', examples: [] },
    { id: 'tame-ni-naranai', pattern: 'ためにならない', shortMeaning: 'không có ích', jlptLevel: 'N3', functions: 'Đánh giá lợi ích', structures: ['Nの + ためにならない'], explanation: 'Nói điều gì đó không đem lại lợi ích.', examples: [] },
    { id: 'sono-tame', pattern: 'そのため', shortMeaning: 'vì vậy', jlptLevel: 'N3', functions: 'Nối kết quả', structures: ['Câu 1。そのため、Câu 2。'], explanation: 'Nối nguyên nhân với kết quả.', examples: [] }
  ];

  var jaja = {
    '募集': { word: '募集', reading: 'ぼしゅう', definition: '希望する人を、つのって集めること。',
      phrases: ['生徒を～する', '～に応ずる'], related: [
        { word: '募る', reading: 'つのる', definition: '広く呼びかけて集める。' },
        { word: '応募', reading: 'おうぼ', definition: '募集に応じて申し込むこと。' },
        { word: '公募', reading: 'こうぼ', definition: '広く一般から募集すること。' },
        { word: '急募', reading: 'きゅうぼ', definition: '急いで募集すること。' }
      ] }
  };

  global.DictMock = { words: words, kanji: kanji, examples: examples, grammars: grammars, jaja: jaja };
})(window);
