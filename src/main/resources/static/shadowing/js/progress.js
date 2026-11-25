(() => {
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const shell = document.getElementById('progress-shell');
  const chart = document.getElementById('progress-chart');
  const tooltip = document.getElementById('progress-chart-tooltip');
  // Luôn chạy hoạt ảnh, không theo prefers-reduced-motion của hệ điều hành (quyết định sản phẩm).
  const reduceMotion = false;

  function headers() {
    return window.authHeaders ? window.authHeaders(false) : {};
  }

  function esc(value) {
    const div = document.createElement('div');
    div.textContent = value == null ? '' : String(value);
    return div.innerHTML;
  }

  function parseDay(iso) {
    const [y, m, d] = String(iso).split('-').map(Number);
    return new Date(y, m - 1, d);
  }

  function shortDate(date) {
    return date.getDate() + '/' + (date.getMonth() + 1);
  }

  function weekRange(iso) {
    const start = parseDay(iso);
    const end = new Date(start);
    end.setDate(end.getDate() + 6);
    return shortDate(start) + ' – ' + shortDate(end);
  }

  function relativeTime(iso) {
    const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
    if (minutes < 1) return 'vừa xong';
    if (minutes < 60) return minutes + ' phút trước';
    const hours = Math.round(minutes / 60);
    if (hours < 24) return hours + ' giờ trước';
    const days = Math.round(hours / 24);
    return days < 30 ? days + ' ngày trước' : new Date(iso).toLocaleDateString('vi-VN');
  }

  function formatScore(value) {
    return value == null ? '–' : (Math.round(value * 10) / 10).toLocaleString('vi-VN');
  }

  /** Counts a number up from 0 so the headline figures land with a little motion. */
  function countUp(el, target, suffix = '', decimals = 0) {
    if (target == null) {
      el.textContent = '–';
      return;
    }
    const format = value => value.toLocaleString('vi-VN', { maximumFractionDigits: decimals }) + suffix;
    if (reduceMotion || target === 0) {
      el.textContent = format(target);
      return;
    }
    const duration = 900;
    const start = performance.now();
    const step = now => {
      const t = Math.min(1, (now - start) / duration);
      const eased = 1 - Math.pow(1 - t, 3);
      const factor = Math.pow(10, decimals);
      el.textContent = format(Math.round(target * eased * factor) / factor);
      if (t < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  /** Fills a stat ring to value/100; CSS transitions the stroke. */
  function setRing(id, value) {
    const ring = document.getElementById(id);
    if (!ring) return;
    const circle = ring.querySelector('.progress-ring-value');
    const circumference = 2 * Math.PI * 20;
    circle.style.strokeDasharray = String(circumference);
    circle.style.strokeDashoffset = String(circumference);
    ring.classList.toggle('is-empty', value == null);
    if (value == null) return;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      circle.style.strokeDashoffset = String(circumference * (1 - Math.max(0, Math.min(100, value)) / 100));
    }));
  }

  function svg(tag, attrs) {
    const el = document.createElementNS(SVG_NS, tag);
    Object.entries(attrs || {}).forEach(([key, value]) => el.setAttribute(key, value));
    return el;
  }

  /** Bar with a 4px rounded data end, square on the baseline. */
  function barPath(x, y, width, height) {
    const r = Math.min(4, width / 2, height);
    return 'M' + x + ',' + (y + height) + 'V' + (y + r) + 'Q' + x + ',' + y + ' ' + (x + r) + ',' + y
      + 'H' + (x + width - r) + 'Q' + (x + width) + ',' + y + ' ' + (x + width) + ',' + (y + r)
      + 'V' + (y + height) + 'Z';
  }

  function renderChart(weeks) {
    const hasData = weeks.some(week => week.averageScore != null);
    document.getElementById('progress-chart-empty').hidden = hasData;
    chart.hidden = !hasData;
    document.querySelector('#progress-chart-table tbody').innerHTML = weeks.map(week =>
      '<tr><th scope="row">' + weekRange(week.weekStart) + '</th><td>' + formatScore(week.averageScore)
      + '</td><td>' + week.attempts + '</td></tr>').join('');
    if (!hasData) return;

    const width = 640, height = 220;
    const margin = { top: 24, right: 8, bottom: 28, left: 32 };
    const plotW = width - margin.left - margin.right;
    const plotH = height - margin.top - margin.bottom;
    const band = plotW / weeks.length;
    const barW = Math.min(40, band * 0.56);
    const y = value => margin.top + plotH - (value / 100) * plotH;
    const root = svg('svg', { viewBox: '0 0 ' + width + ' ' + height, class: 'progress-chart-svg' });

    [50, 100].forEach(value => {
      root.appendChild(svg('line', { x1: margin.left, x2: width - margin.right, y1: y(value), y2: y(value), class: 'chart-grid' }));
    });
    [0, 50, 100].forEach(value => {
      const label = svg('text', { x: margin.left - 8, y: y(value) + 4, class: 'chart-axis-label', 'text-anchor': 'end' });
      label.textContent = value;
      root.appendChild(label);
    });

    let lastIndex = -1;
    weeks.forEach((week, i) => { if (week.averageScore != null) lastIndex = i; });

    weeks.forEach((week, i) => {
      const cx = margin.left + band * i + band / 2;
      const xLabel = svg('text', { x: cx, y: height - 8, class: 'chart-axis-label', 'text-anchor': 'middle' });
      xLabel.textContent = shortDate(parseDay(week.weekStart));
      root.appendChild(xLabel);

      let bar = null;
      if (week.averageScore != null) {
        const top = y(week.averageScore);
        bar = svg('path', { d: barPath(cx - barW / 2, top, barW, margin.top + plotH - top), class: 'chart-bar' });
        bar.style.animationDelay = (i * 70) + 'ms';
        root.appendChild(bar);
        if (i === lastIndex) {
          const valueLabel = svg('text', { x: cx, y: top - 6, class: 'chart-value-label', 'text-anchor': 'middle' });
          valueLabel.textContent = formatScore(week.averageScore);
          valueLabel.style.animationDelay = (i * 70 + 500) + 'ms';
          root.appendChild(valueLabel);
        }
      }

      // The hit target covers the whole column so thin or empty weeks are still easy to hover.
      const hit = svg('rect', { x: margin.left + band * i, y: margin.top, width: band, height: plotH, class: 'chart-hit', tabindex: '0' });
      hit.setAttribute('aria-label', 'Tuần ' + weekRange(week.weekStart) + ': '
        + (week.averageScore == null ? 'chưa luyện' : formatScore(week.averageScore) + ' điểm, ' + week.attempts + ' lượt chấm'));
      const show = () => showTooltip(week, cx, bar ? y(week.averageScore) : margin.top + plotH, width, height, bar);
      hit.addEventListener('mouseenter', show);
      hit.addEventListener('focus', show);
      hit.addEventListener('mouseleave', hideTooltip);
      hit.addEventListener('blur', hideTooltip);
      root.appendChild(hit);
    });

    chart.innerHTML = '';
    chart.appendChild(root);
  }

  function showTooltip(week, cx, top, width, height, activeBar) {
    tooltip.innerHTML = '<strong>Tuần ' + weekRange(week.weekStart) + '</strong><span>'
      + (week.averageScore == null ? 'Chưa luyện phát âm'
        : formatScore(week.averageScore) + ' điểm · ' + week.attempts + ' lượt chấm') + '</span>';
    tooltip.hidden = false;
    const box = chart.getBoundingClientRect();
    const left = Math.min(Math.max(cx * (box.width / width), 70), box.width - 70);
    tooltip.style.left = (chart.offsetLeft + left) + 'px';
    tooltip.style.top = (chart.offsetTop + top * (box.height / height) - 10) + 'px';
    chart.querySelectorAll('.chart-bar').forEach(bar => bar.classList.toggle('is-dimmed', Boolean(activeBar) && bar !== activeBar));
  }

  function hideTooltip() {
    tooltip.hidden = true;
    chart.querySelectorAll('.chart-bar').forEach(bar => bar.classList.remove('is-dimmed'));
  }

  function renderWeak(items) {
    const list = document.getElementById('progress-weak-list');
    document.getElementById('progress-weak-empty').hidden = items.length > 0;
    list.innerHTML = items.map((item, i) =>
      '<li style="--i:' + i + '"><a class="progress-item" href="/shadowing/player/' + encodeURIComponent(item.videoId) + '?mode=shadowing">'
      + '<span class="progress-item-main"><span class="progress-item-title" lang="ja">' + esc(item.text) + '</span>'
      + '<span class="progress-item-meta">' + esc(item.videoTitle) + ' · ' + item.attempts + ' lượt</span>'
      + '<span class="progress-meter" aria-hidden="true"><span style="--w:' + Math.max(4, Math.min(100, item.bestScore)) + '%"></span></span></span>'
      + '<span class="progress-item-score" title="Điểm cao nhất">' + formatScore(item.bestScore) + '</span></a></li>').join('');
  }

  function renderRecent(items) {
    const list = document.getElementById('progress-recent-list');
    document.getElementById('progress-recent-empty').hidden = items.length > 0;
    list.innerHTML = items.map((item, i) => {
      const thumb = item.hasThumbnail
        ? '<img src="/api/videos/' + encodeURIComponent(item.videoId) + '/thumbnail" alt="" loading="lazy">'
        : '<i class="fa-solid fa-play" aria-hidden="true"></i>';
      return '<li style="--i:' + i + '"><a class="progress-item" href="/shadowing/practice/' + encodeURIComponent(item.videoId) + '">'
        + '<span class="progress-thumb">' + thumb + '</span>'
        + '<span class="progress-item-main"><span class="progress-item-title">' + esc(item.title) + '</span>'
        + '<span class="progress-item-meta"><i class="fa-regular fa-clock" aria-hidden="true"></i> ' + relativeTime(item.lastActivityAt) + '</span></span>'
        + '</a></li>';
    }).join('');
    list.querySelectorAll('.progress-thumb img').forEach(img => img.addEventListener('error', () => {
      img.replaceWith(Object.assign(document.createElement('i'), { className: 'fa-solid fa-play' }));
    }));
  }

  function greeting(data, thisWeek) {
    if (data.pronunciationAttempts === 0 && data.studiedVideos === 0) {
      return 'Chưa có hoạt động nào. Chọn một video và nói theo câu đầu tiên nhé!';
    }
    if (data.pronunciationAttempts === 0) {
      return 'Bạn đã học ' + data.studiedVideos + ' video. Thử ghi âm và chấm phát âm một câu xem sao!';
    }
    const score = data.averageScore30d;
    if (score == null) return 'Lâu rồi chưa luyện nói. Quay lại với một câu ngắn thôi nhé!';
    if (score >= 85) return 'Phát âm đang rất tốt — giữ phong độ nhé! 🎉';
    if (score >= 70) return 'Bạn tiến bộ đều. Luyện lại vài câu yếu để chạm mốc 85 nào!';
    return thisWeek && thisWeek.attempts > 0
      ? 'Tuần này bạn đã luyện rồi, cứ đều tay là điểm sẽ lên!'
      : 'Mỗi ngày một ít thôi — bắt đầu từ các câu dưới 70 điểm nhé.';
  }

  function renderHero(data) {
    const weeks = data.weeks || [];
    const thisWeek = weeks[weeks.length - 1];
    document.getElementById('progress-greeting').textContent = greeting(data, thisWeek);
    countUp(document.getElementById('pill-week-attempts'), thisWeek ? thisWeek.attempts : 0);
    document.getElementById('pill-week-score').textContent = formatScore(thisWeek ? thisWeek.averageScore : null);
    const latest = (data.recentVideos || [])[0];
    if (latest) {
      const button = document.getElementById('progress-continue');
      button.href = '/shadowing/practice/' + encodeURIComponent(latest.videoId);
      button.title = latest.title;
      document.getElementById('progress-continue-label').textContent = 'Luyện tiếp bài gần nhất';
      button.hidden = false;
    }
  }

  async function load() {
    try {
      const res = await fetch('/api/shadowing-progress', { headers: headers() });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const data = await res.json();
      shell.classList.remove('is-loading');
      shell.classList.add('is-ready');
      document.getElementById('progress-stats').setAttribute('aria-busy', 'false');

      renderHero(data);
      countUp(document.getElementById('stat-videos'), data.studiedVideos);
      countUp(document.getElementById('stat-sentences'), data.practicedSentences);
      document.getElementById('stat-attempts').textContent = data.pronunciationAttempts + ' lượt chấm';
      countUp(document.getElementById('stat-score'), data.averageScore30d, '', 1);
      countUp(document.getElementById('stat-accuracy'), data.listeningAccuracy, '%', 1);
      document.getElementById('stat-sessions').textContent = data.listeningSessions + ' phiên hoàn thành';
      setRing('ring-score', data.averageScore30d);
      setRing('ring-accuracy', data.listeningAccuracy);
      renderChart(data.weeks || []);
      renderWeak(data.weakSentences || []);
      renderRecent(data.recentVideos || []);
    } catch (err) {
      shell.classList.remove('is-loading');
      shell.classList.add('is-ready');
      document.getElementById('progress-greeting').textContent = 'Kitsu-chan chưa lấy được tiến độ của bạn.';
      const error = document.getElementById('progress-error');
      error.textContent = 'Không tải được tiến độ. Hãy đăng nhập rồi tải lại trang.';
      error.hidden = false;
    }
  }

  load();
})();
