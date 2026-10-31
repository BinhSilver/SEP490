(function (global) {
  'use strict';

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function toast(message) {
    if (global.JlasUI && typeof global.JlasUI.toast === 'function') global.JlasUI.toast(message);
  }

  function furiganaHtml(tokens, highlight) {
    return (tokens || []).map(function (token) {
      var text = escapeHtml(token.text);
      var base = highlight && token.text === highlight ? '<strong>' + text + '</strong>' : text;
      return token.reading && token.reading !== token.text
        ? '<ruby>' + base + '<rt>' + escapeHtml(token.reading) + '</rt></ruby>' : base;
    }).join('');
  }

  var currentAudio = null;
  function speak(value) {
    if (currentAudio) {
      currentAudio.pause();
      currentAudio.currentTime = 0;
    }
    var url = '/api/dictionary/tts?text=' + encodeURIComponent(String(value || ''));
    var audio = new Audio(url);
    var reported = false;
    currentAudio = audio;
    function playbackError() {
      if (currentAudio !== audio || reported) return;
      reported = true;
      fetch(url).then(function (response) {
        if (response.ok) return null;
        return response.json().then(function (body) { return body.message || body.detail || null; });
      }).then(function (message) { toast(message || 'Không phát được âm thanh'); })
        .catch(function () { toast('Không phát được âm thanh'); });
    }
    audio.addEventListener('error', playbackError);
    audio.play().catch(playbackError);
  }

  function speakerButtonHtml(value) {
    return '<button type="button" class="dict-speaker" data-speak="' + escapeHtml(value) +
      '" aria-label="Phát âm ' + escapeHtml(value) + '"><i class="fa-solid fa-volume-high"></i></button>';
  }

  function jlptBadgeHtml(level) {
    var key = String(level || '').toUpperCase();
    return /^N[1-5]$/.test(key)
      ? '<span class="dict-badge dict-badge--jlpt dict-badge--' + key.toLowerCase() + '">' + key + '</span>' : '';
  }

  function emptyStateHtml() {
    return '<div class="dict-empty-state"><div class="dict-empty-state__icon">' +
      '<i class="fa-solid fa-book-open"></i><i class="fa-solid fa-magnifying-glass"></i></div>' +
      '<p>Không có kết quả nào được hiển thị</p></div>';
  }

  function pitchAccentHtml(reading, accent) {
    var morae = [];
    Array.from(String(reading || '')).forEach(function (char) {
      if (/[ゃゅょぁぃぅぇぉゎャュョァィゥェォ]/.test(char) && morae.length) morae[morae.length - 1] += char;
      else morae.push(char);
    });
    var drop = Number(accent);
    if (!Number.isInteger(drop) || drop < 0 || drop > morae.length) return escapeHtml(reading);
    return '<span class="dict-pitch" aria-label="Cao độ ' + escapeHtml(reading) + '">' + morae.map(function (mora, index) {
      var high = drop === 0 ? index > 0 : (drop === 1 ? index === 0 : index > 0 && index < drop);
      var nextHigh = drop === 0 ? index + 1 > 0 : (drop === 1 ? false : index + 1 > 0 && index + 1 < drop);
      var change = index + 1 < morae.length && high !== nextHigh;
      return '<span class="dict-pitch__mora' + (high ? ' is-high' : ' is-low') + (change ? ' has-change' : '') +
        '">' + escapeHtml(mora) + '</span>';
    }).join('') + '</span>';
  }

  function contributionsHtml(page) {
    var items = page.content || [];
    var pages = Math.ceil(page.totalElements / (page.size || 5));
    var rows = items.map(function (item) {
      return '<div class="dict-contribution"><p>' + escapeHtml(item.content) + '</p><div class="dict-contribution__meta">' +
        '<span>' + escapeHtml(item.authorName) + '</span><span>' +
        '<button type="button" data-contribution-vote="1" data-contribution-id="' + escapeHtml(item.id) + '"' +
        (item.myVote === 1 ? ' class="is-active" aria-pressed="true"' : ' aria-pressed="false"') +
        '><i class="fa-regular fa-thumbs-up"></i> ' + escapeHtml(item.likes) + '</button> ' +
        '<button type="button" data-contribution-vote="-1" data-contribution-id="' + escapeHtml(item.id) + '"' +
        (item.myVote === -1 ? ' class="is-active" aria-pressed="true"' : ' aria-pressed="false"') +
        '><i class="fa-regular fa-thumbs-down"></i> ' + escapeHtml(item.dislikes) + '</button>' +
        (item.mine ? '' : ' <button type="button" data-contribution-report data-contribution-id="' + escapeHtml(item.id) +
          '" aria-label="Báo cáo ý kiến"><i class="fa-regular fa-flag"></i></button>') + '</span></div></div>';
    }).join('');
    var pagination = pages > 1 ? '<nav class="dict-pagination" aria-label="Trang ý kiến">' +
      Array.from({ length: pages }, function (_unused, index) {
        return '<button type="button" data-contribution-page="' + index + '"' +
          (index === page.page ? ' aria-current="page"' : '') + '>' + (index + 1) + '</button>';
      }).join('') + '</nav>' : '';
    var form = global.JlasAuth && global.JlasAuth.isLoggedIn && global.JlasAuth.isLoggedIn()
      ? '<div class="dict-contribution-form"><textarea maxlength="2000" aria-label="Thêm ý kiến đóng góp" placeholder="Thêm ý kiến đóng góp"></textarea>' +
        '<button type="button" data-contribution-send>Gửi</button></div>' : '';
    return '<section class="dict-word-section"><h2>Ý kiến đóng góp</h2><p>Có ' + page.totalElements + ' ý kiến đóng góp</p>' +
      (items.length ? rows + pagination : '<p>Chưa có ý kiến đóng góp</p>') + form + '</section>';
  }

  if (global.document) global.document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-speak]');
    if (button) { event.stopImmediatePropagation(); speak(button.getAttribute('data-speak')); }
  });

  global.DictUi = { escapeHtml: escapeHtml, toast: toast, furiganaHtml: furiganaHtml, speak: speak,
    speakerButtonHtml: speakerButtonHtml, jlptBadgeHtml: jlptBadgeHtml, emptyStateHtml: emptyStateHtml,
    pitchAccentHtml: pitchAccentHtml, contributionsHtml: contributionsHtml };
})(window);
