(function () {
  'use strict';
  function example() {
    const titles = ['Phân biệt は và が', 'Ôn thể て và cách nối câu', 'Dùng に và で đúng ngữ cảnh',
      'Nói về dự định với つもり', 'Diễn tả kinh nghiệm với たことがある',
      'Đọc hiểu thông báo trong đời sống', 'Nghe hội thoại về kế hoạch', 'Ôn tập ngữ pháp và từ vựng N4'];
    return {
      title: 'Từng bước đến JLPT N4', goal: 'JLPT', currentLevel: 'N5', targetLevel: 'N4', minutesPerDay: 30,
      textbook: 'Giáo trình sơ cấp N4',
      phases: [
        { code: 'WARM_UP', title: 'Khởi động', description: 'Củng cố những kiến thức bạn còn chưa chắc.' },
        { code: 'GOAL_LEARNING', title: 'Chinh phục N4', description: 'Tiếp nối nền tảng, học từng bước theo mục tiêu.' }
      ],
      weaknesses: [{ label: 'Trợ từ は・が', wrong: 4, assessed: 5 }, { label: 'Thể て', wrong: 3, assessed: 5 }],
      coverageGaps: [],
      items: titles.map((title, i) => ({ id: i + 1, lessonId: 101 + i, title,
        phase: i < 3 ? 'WARM_UP' : 'GOAL_LEARNING', dayNumber: i + 1,
        estimatedMinutes: i === 0 ? 20 : i === 1 ? 25 : i === 2 ? 15 : 30,
        status: i === 0 ? 'COMPLETED' : i === 1 ? 'IN_PROGRESS' : 'NOT_STARTED',
        skill: i === 5 ? 'Đọc hiểu' : i === 6 ? 'Nghe hiểu' : 'Ngữ pháp',
        reason: i < 3 ? 'Ôn lại kiến thức liên quan đến các tag còn yếu trong kết quả Smart Test mẫu.'
          : 'Bổ sung kiến thức phù hợp mục tiêu JLPT N4 từ khảo sát mẫu.',
        description: i < 3 ? 'Nhận biết cách dùng, phân biệt các trường hợp dễ nhầm và luyện tập qua câu ví dụ.'
          : 'Làm quen kiến thức mới, xem cách sử dụng trong ngữ cảnh và củng cố bằng bài luyện tập.' }))
    };
  }
  function demo(scenario) {
    return { load: async function () {
      if (scenario === 'empty') return null;
      if (scenario === 'error') throw new Error('Kết nối đang gián đoạn. Bạn có thể thử tải lại lộ trình.');
      const path = example();
      if (scenario === 'complete') path.items.forEach(item => { item.status = 'COMPLETED'; });
      if (scenario === 'coverage') path.coverageGaps = [{ tagCode: 'N5-CONDITIONAL',
        message: 'Chưa có bài ôn phù hợp cho câu điều kiện. Phần kiến thức này chưa được bố trí trong lộ trình.' }];
      if (scenario === 'no-warm-up') {
        path.items = path.items.filter(item => item.phase === 'GOAL_LEARNING');
        path.items.forEach((item, i) => { item.dayNumber = i + 1; });
        path.weaknesses = [];
        path.phases[0].emptyReason = 'Kết quả mẫu không có tag yếu đủ điều kiện. Bạn có thể bắt đầu phần học theo mục tiêu.';
      }
      return path;
    } };
  }
  function api(url, request) {
    return { load: async function () {
      const response = await (request || fetch)(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (response.status === 404) return null;
      if (!response.ok) throw new Error(response.status === 401 ? 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.' : 'Không tải được lộ trình. Vui lòng thử lại.');
      const body = await response.json();
      const path = body.data;
      if (!path || !Array.isArray(path.items) || !Array.isArray(path.phases)
          || !path.items.every(item => Number.isInteger(item.dayNumber) && item.dayNumber > 0
            && Number.isFinite(item.estimatedMinutes) && item.estimatedMinutes > 0)) {
        throw new Error('Dữ liệu lộ trình chưa đầy đủ. Vui lòng thử lại sau.');
      }
      return path;
    } };
  }
  const exports = { example, demo, api };
  if (typeof module !== 'undefined') module.exports = exports;
  if (typeof window !== 'undefined') window.PersonalizedPathData = exports;
})();
