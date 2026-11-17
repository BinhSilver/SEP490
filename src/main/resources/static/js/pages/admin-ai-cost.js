/* Chi phí AI theo tháng (GET /api/admin/system/ai-cost?month=…): trung bình, P90 và giá CP gợi ý của từng chức năng,
   để rà giá hằng tháng theo kế hoạch Pro/CP (doc-huy/ke-hoach-kinh-doanh-pro-cp-huy.md, mục 2). */
(function () {
  'use strict';

  var A = window.Admin;
  if (!A) return;

  function money(value) {
    return Math.round(Number(value) || 0).toLocaleString('vi-VN') + 'đ';
  }

  function priceCell(line) {
    if (line.currentCredit == null) return '<td class="adm-muted">Không bán theo lần</td>';
    if (line.suggestedCredit == null) return '<td>' + line.currentCredit + ' CP <span class="adm-muted">· chưa đủ số liệu</span></td>';
    var low = line.suggestedCredit > line.currentCredit;
    return '<td class="adm-nowrap">' + line.currentCredit + ' CP → <strong>' + line.suggestedCredit + ' CP</strong> ' +
      (low ? '<span class="adm-badge adm-badge--danger">Giá đang thấp</span>'
        : '<span class="adm-badge adm-badge--ok">Đủ lãi</span>') + '</td>';
  }

  function row(line, strong) {
    var unknown = line.calls - line.pricedCalls;
    return '<tr><td>' + (strong ? '<strong>' + A.esc(line.label) + '</strong>' : A.esc(line.label)) +
      (unknown > 0 ? '<br><span class="adm-muted">' + unknown + ' lần chưa có giá</span>' : '') + '</td>' +
      '<td class="adm-nowrap">' + Number(line.calls).toLocaleString('vi-VN') + '</td>' +
      '<td class="adm-nowrap">' + money(line.totalVnd) + '</td>' +
      '<td class="adm-nowrap">' + money(line.averageVnd) + '</td>' +
      '<td class="adm-nowrap">' + money(line.p90Vnd) + '</td>' +
      priceCell(line) + '</tr>';
  }

  function table(caption, lines, strong) {
    return '<section class="adm-panel"><h2 class="adm-panel__title">' + A.esc(caption) + '</h2>' +
      (lines.length
        ? '<div class="adm-table-wrap"><table class="adm-table"><thead><tr><th scope="col">Chức năng</th>' +
          '<th scope="col">Số lần</th><th scope="col">Tổng</th><th scope="col">Trung bình</th><th scope="col">P90</th>' +
          '<th scope="col">Giá CP hiện tại → gợi ý</th></tr></thead><tbody>' +
          lines.map(function (line) { return row(line, strong); }).join('') + '</tbody></table></div>'
        : A.empty('fa-inbox', 'Chưa có lần gọi AI nào trong tháng này')) +
      '</section>';
  }

  function thisMonth() {
    var now = new Date();
    return now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
  }

  A.section('system-ai-cost', {
    subtitle: 'Chi phí thật của mỗi lần gọi AI, quy ra tiền. Giá CP gợi ý = làm tròn lên bội số của 5 ' +
      '(chi phí P90 × hệ số ÷ giá 1 CP). Rà mỗi tháng và chỉnh giá hoặc hạn mức khi giá đang thấp.',
    render: async function (root) {
      root.innerHTML =
        '<p class="adm-toolbar"><label>Tháng <input type="month" class="input" data-ai-cost-month value="' + thisMonth() +
          '"></label></p><div data-ai-cost-body>' + A.loading() + '</div>';
      var input = root.querySelector('[data-ai-cost-month]');
      var body = root.querySelector('[data-ai-cost-body]');

      async function load() {
        body.innerHTML = A.loading();
        try {
          var r = await A.api.get('/api/admin/system/ai-cost?month=' + encodeURIComponent(input.value || thisMonth()));
          body.innerHTML =
            table('Theo đơn vị bán', [r.aiCallTurn, r.shadowingMinute], true) +
            table('Theo từng lần gọi AI', r.features, false) +
            '<p class="adm-muted">Một lượt AI Call = câu trả lời + phần gợi ý, dịch và giọng đọc chia đều cho các lượt. ' +
              'Một phút video Shadowing = chi phí AI Shadowing của tháng (phụ đề, câu hỏi, bài tập) chia cho tổng số phút ' +
              'video người dùng tạo trong tháng. ' +
              'Quy đổi: 1 USD = ' + Number(r.usdToVnd).toLocaleString('vi-VN') + 'đ, hệ số ' + r.markup + ', 1 CP = ' +
              Number(r.vndPerCredit).toLocaleString('vi-VN') + 'đ (cấu hình <code>ai-pricing</code>).</p>';
        } catch (e) {
          body.innerHTML = A.empty('fa-circle-exclamation', 'Không tải được chi phí AI', e.message);
        }
      }

      input.addEventListener('change', load);
      await load();
    }
  });
})();
