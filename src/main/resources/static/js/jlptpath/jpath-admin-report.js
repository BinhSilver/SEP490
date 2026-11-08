/* [JLAS-W0108] "Báo cáo lộ trình JLPT": usage figures of the JLPT path for the system admin. Runs inside the admin
 * shell (Admin.*, AdminShell.ready). */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.JpathAdminReport = api;
    if (root.document) {
      if (root.document.readyState === 'loading') {
        root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
      } else {
        api.mount();
      }
    }
  }
})(typeof window !== 'undefined' ? window : globalThis, function (global) {
  'use strict';

  var REPORT_URL = '/api/admin/system/learning-path-report';

  /** 66.67 → '66,67 %'; null → '—' (nothing to measure yet). */
  function formatPercent(value) {
    return value == null ? '—' : String(value).replace('.', ',') + ' %';
  }

  /** 2.5 → '2,5'; null → '0'. */
  function formatNumber(value) {
    return value == null ? '0' : String(value).replace('.', ',');
  }

  /** The stat cards of the report, grouped by section: [{ section, label, value }]. */
  function cards(report) {
    var out = [];
    Object.keys(report.learnersByLevel || {}).forEach(function (level) {
      out.push({ section: 'levels', label: level, value: formatNumber(report.learnersByLevel[level]) });
    });
    out.push({ section: 'usage', label: 'Người học hoạt động 7 ngày', value: formatNumber(report.activeLearners7d) });
    out.push({ section: 'usage', label: 'Ngày học hoàn thành (30 ngày)', value: formatPercent(report.dayCompletionPercent30d) });
    out.push({ section: 'usage', label: 'Chuỗi ngày trung bình', value: formatNumber(report.averageStreak) });
    out.push({ section: 'usage', label: 'Kiểm tra nhanh đạt (30 ngày)', value: formatPercent(report.microCheckPassPercent30d) });
    out.push({ section: 'usage', label: 'Lộ trình cũ còn đang học', value: formatNumber(report.legacyActivePaths) });
    out.push({ section: 'mock', label: 'Lượt thi thử', value: formatNumber(report.mockAttempts) });
    out.push({ section: 'mock', label: 'Thi thử đạt', value: formatNumber(report.mockPassed) });
    out.push({ section: 'mock', label: 'Báo lỗi đang mở', value: formatNumber(report.reportsOpen) });
    out.push({ section: 'mock', label: 'Tổng báo lỗi', value: formatNumber(report.reportsTotal) });
    return out;
  }

  var HOSTS = { levels: 'jprLevels', usage: 'jprUsage', mock: 'jprMock' };

  function render(report) {
    var doc = global.document;
    Object.keys(HOSTS).forEach(function (key) { doc.getElementById(HOSTS[key]).innerHTML = ''; });
    cards(report).forEach(function (card) {
      doc.getElementById(HOSTS[card.section]).insertAdjacentHTML('beforeend', '<div class="jpa-stat"><strong>'
        + global.Admin.esc(card.value) + '</strong><span>' + global.Admin.esc(card.label) + '</span></div>');
    });
    doc.getElementById('jprGenerated').textContent = 'Cập nhật lúc ' + global.Admin.date(report.generatedAt) + '.';
  }

  async function load() {
    var status = global.document.getElementById('jprStatus');
    status.textContent = 'Đang tải…';
    try {
      render(await global.Admin.api.get(REPORT_URL));
      status.textContent = '';
    } catch (error) {
      status.textContent = error.message;
    }
  }

  var mounted = false;

  async function mount() {
    if (mounted || !global.AdminShell || !global.document.getElementById('jprLevels')) {
      return;
    }
    mounted = true;
    if (!(await global.AdminShell.ready)) {
      return;
    }
    global.document.getElementById('jprReload').addEventListener('click', load);
    load();
  }

  return { formatPercent: formatPercent, formatNumber: formatNumber, cards: cards, mount: mount };
});
