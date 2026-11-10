(function () {
  'use strict';

  var ui = window.DictUi;
  var esc = ui.escapeHtml;
  var tabs = ['word', 'kanji', 'example', 'grammar', 'japanese'];
  var state = { tab: 'word', query: '', result: null, selectedId: null, word: null,
    page: 0, size: 20, token: 0, otherPageToken: 0, suggestionResult: null,
    related: [], relatedKanji: [], kanjiRelated: [], kanjiCharacter: '' };
  var els = {};
  var suggestionTimer;

  function byId(id) { return document.getElementById(id); }
  function firstGloss(word) {
    if (word.meaning) return word.meaning;
    var first = (word.senses || [])[0];
    return first ? (first.glosses || []).map(function (g) { return g.text; }).join('; ') : '';
  }
  function routePath(tab, query) {
    return '/search/' + tab + (query ? '/' + encodeURIComponent(query) : '');
  }
  function parseRoute() {
    var path = window.location.pathname;
    if (path === '/dictionary/index.html' || path === '/dictionary/' || path === '/dictionary') {
      var legacy = new URLSearchParams(window.location.search).get('q') || '';
      history.replaceState(null, '', routePath('word', legacy));
      return { tab: 'word', query: legacy };
    }
    var parts = path.split('/');
    var tab = tabs.indexOf(parts[2]) >= 0 ? parts[2] : 'word';
    var query = '';
    if (parts[2] === 'grammar_details') return { tab: 'grammar', query: '' };
    try { query = decodeURIComponent(parts.slice(3).join('/')); } catch (_err) { query = ''; }
    return { tab: tab, query: query };
  }
  function navigate(tab, query, id) {
    history.pushState(null, '', routePath(tab, query));
    state.selectedId = id == null ? null : String(id);
    renderRoute();
  }
  function setShell(home) {
    els.hero.hidden = !home;
    els.homePanels.hidden = !home;
    els.layout.hidden = home;
    els.other.hidden = true;
    els.clear.hidden = !els.input.value;
    Array.prototype.forEach.call(els.tabs.querySelectorAll('[data-tab]'), function (button) {
      var active = button.dataset.tab === state.tab;
      button.classList.toggle('is-active', active);
      button.setAttribute('aria-selected', String(active));
    });
  }
  function showEmpty() { els.detail.innerHTML = ui.emptyStateHtml(); }
  function showLoading(value) { els.status.hidden = !value; }

  function vocabRowHtml(word) {
    var id = word.id == null ? '' : String(word.id);
    return '<button type="button" class="dict-vocab-row" data-word="' + esc(word.expression) +
      '" data-vocab-id="' + esc(id) + '"><span class="dict-vocab-row__head"><strong class="dict-vocab-row__expression">' +
      esc(word.expression) + '</strong><span class="dict-vocab-row__reading">' + esc(word.reading || '') +
      '</span>' + ui.jlptBadgeHtml(word.jlptLevel) + '</span><span class="dict-vocab-row__gloss">' +
      esc(firstGloss(word)) + '</span></button>';
  }
  function renderOtherResults() {
    var list = state.result && state.result.vocabularyResults && state.result.vocabularyResults.content || [];
    var others = list.filter(function (word) {
      var current = state.word;
      return current && current.id != null ? String(word.id) !== String(current.id)
        : word.expression !== (current && current.expression);
    });
    var page = state.result && state.result.vocabularyResults;
    var hasMore = !!page && page.last === false;
    els.other.hidden = state.tab !== 'word' || (!others.length && !hasMore && state.page === 0);
    els.vocabList.innerHTML = others.map(vocabRowHtml).join('');
    els.otherPagination.innerHTML = page && (state.page > 0 || hasMore)
      ? '<button type="button" data-other-page="' + (state.page - 1) + '"' +
          (state.page === 0 ? ' disabled' : '') + '>Trước</button>' +
        '<span class="dict-pagination__label">Trang ' + (state.page + 1) + '</span>' +
        '<button type="button" data-other-page="' + (state.page + 1) + '"' +
          (hasMore ? '' : ' disabled') + '>Tiếp</button>' : '';
  }

  function sensesHtml(senses) {
    var groups = [];
    (senses || []).forEach(function (sense) {
      var pos = (sense.partsOfSpeech || []).join(', ');
      var last = groups[groups.length - 1];
      if (!last || last.pos !== pos) groups.push(last = { pos: pos, senses: [] });
      last.senses.push(sense);
    });
    return groups.map(function (group) {
      return '<section class="dict-word-section dict-meaning"><h2><i class="fa-solid fa-book-open"></i> ' +
        esc(group.pos || 'Ý nghĩa') + '</h2>' + group.senses.map(function (sense) {
          return '<div class="dict-meaning__sense">' + (sense.domains || []).map(function (domain) {
            return '<span class="dict-domain">' + esc(domain) + '</span>';
          }).join('') + (sense.glosses || []).map(function (gloss) {
            return '<p class="dict-meaning__gloss">' + esc(gloss.text) + '</p>';
          }).join('') + (sense.usageNote ? '<small>' + esc(sense.usageNote) + '</small>' : '') + '</div>';
        }).join('') + '</section>';
    }).join('');
  }
  function actionButton(label, icon, extraClass) {
    return '<button type="button" class="dict-action ' + (extraClass || '') + '" data-coming-soon>' +
      (icon ? '<i class="fa-solid fa-' + icon + '"></i> ' : '') + esc(label) + '</button>';
  }
  function relatedRowHtml(word) {
    return '<button type="button" class="dict-related-row" data-word="' + esc(word.expression) + '"' +
      (word.id == null ? '' : ' data-vocab-id="' + esc(String(word.id)) + '"') + '>' +
      '<span class="dict-related-row__head"><strong lang="ja">' + esc(word.expression) + '</strong>' +
      '<span class="dict-related-row__reading" lang="ja">' + esc(word.reading || '') + '</span>' +
      ui.jlptBadgeHtml(word.jlptLevel) + '</span>' +
      '<span class="dict-related-row__meaning">' + esc(word.meaning || firstGloss(word)) + '</span></button>';
  }
  function relatedWordsUrl(kanji, exclude) {
    var params = new URLSearchParams({ kanji: kanji.join('') });
    if (exclude) params.set('exclude', exclude);
    return '/dictionary/words.html?' + params.toString();
  }
  function renderRelated() {
    var host = byId('dict-related-words');
    if (!host) return;
    host.hidden = !state.relatedKanji.length;
    if (!state.relatedKanji.length) return;
    host.innerHTML = '<h2>Từ liên quan</h2>' +
      (state.related.length ? state.related.slice(0, 5).map(relatedRowHtml).join('')
        : '<p class="dict-detail__empty">Chưa có từ vựng liên quan.</p>') +
      (state.relatedKanji.length ? '<a class="dict-text-button" href="' +
        esc(relatedWordsUrl(state.relatedKanji, state.word.expression)) + '">Xem thêm</a>' : '');
  }
  function kanjiMiniHtml(kanji) {
    var readings = kanji.readings || [];
    var on = readings.filter(function (r) { return r.readingType === 'ON'; }).map(function (r) { return r.readingText; }).join('、');
    var kun = readings.filter(function (r) { return r.readingType === 'KUN'; }).map(function (r) { return r.readingText; }).join('、');
    return '<article class="dict-kanji-mini"><div class="dict-kanji-mini__glyph" data-stroke-for="' + esc(kanji.character) +
      '">' + esc(kanji.character) + '</div><div><h3>' + esc(kanji.character) + ' <small>' + esc(kanji.hanViet || '') +
      '</small></h3><p>On: ' + esc(on || '—') + '</p><p>Kun: ' + esc(kun || '—') + '</p>' +
      '<button type="button" class="dict-text-button" data-kanji="' + esc(kanji.character) + '">Xem chi tiết</button></div></article>';
  }
  async function fillKanjiAndRelated(word, token) {
    var composition = word.kanjiComposition || [];
    var details = await Promise.all(composition.map(function (part) {
      return window.DictApi.kanjiDetail(part.character).catch(function () {
        return word.id == null ? (window.DictMock.kanji[part.character] || null) : null;
      });
    }));
    if (token !== state.token) return;
    var host = byId('dict-word-kanji');
    var available = details.filter(Boolean);
    if (host) {
      host.hidden = !available.length;
      host.innerHTML = available.length ? '<h2>Hán tự trong từ</h2><div class="dict-kanji-mini-grid">' +
        available.map(kanjiMiniHtml).join('') + '</div>' : '';
      available.forEach(function (kanji) {
        if (!kanji.strokeSvgUri) return;
        var box = Array.prototype.find.call(host.querySelectorAll('[data-stroke-for]'), function (node) {
          return node.dataset.strokeFor === kanji.character;
        });
        if (!box) return;
        window.DictStrokeOrder.mount(box, kanji.strokeSvgUri).then(function (replay) {
          if (token !== state.token || !box.isConnected) return;
          if (!replay) box.innerHTML = '<img src="' + esc(kanji.strokeSvgUri) + '" alt="Thứ tự nét ' + esc(kanji.character) + '">';
        });
      });
    }
    var seen = {};
    var related = [];
    available.forEach(function (kanji) {
      (kanji.relatedVocabulary || []).forEach(function (entry) {
        if (entry.id !== word.id && entry.expression !== word.expression && !seen[entry.expression]) {
          seen[entry.expression] = true; related.push(entry);
        }
      });
    });
    state.related = related;
    state.relatedKanji = Array.from(new Set(composition.map(function (part) { return part.character; }).filter(Boolean)));
    renderRelated();
  }

  function renderWord(word, token) {
    var data = word;
    state.word = data;
    state.related = [];
    state.relatedKanji = [];
    var pos = [];
    (data.senses || []).forEach(function (sense) { pos.push.apply(pos, sense.partsOfSpeech || []); });
    var conjugations = window.DictConjugation.conjugate(data.expression, pos);
    var html = '<article class="dict-word-card"><header class="dict-word-head"><div><h1>' + esc(data.expression) +
      (data.verified || data.usageScope === 'TEXTBOOK' || data.usageScope === 'BOTH'
        ? ' <i class="fa-solid fa-circle-check dict-verified" title="Đã xác minh"></i>' : '') +
      '</h1><p>「' + esc(data.reading || '') + '」' +
      (data.hanViet ? '「' + esc(data.hanViet.toUpperCase()) + '」' : '') +
      '</p></div><div class="dict-word-head__actions">' + actionButton('Lưu', 'plus') +
      actionButton('Flashcard', 'layer-group') + ui.speakerButtonHtml(data.reading || data.expression) + '</div></header>' +
      '<div class="dict-pronunciation">' + ui.speakerButtonHtml(data.reading || data.expression) +
      (data.pitchAccent == null ? esc(data.reading || '') : ui.pitchAccentHtml(data.reading, data.pitchAccent)) + '</div>' +
      (data.pitchAccent == null ? '' : '<div class="dict-pitch-source"' +
        (data.pitchAccentSource === 'KANJIUM' ? ' title="The pitch accent notation was provided by Uros O. through his free database."' : '') + '>' +
        (data.pitchAccentSource === 'UNIDIC_CWJ' ? 'Trọng âm: UniDic (NINJAL)' :
          data.pitchAccentSource === 'KANJIUM' ? 'Trọng âm: Kanjium – Uros O. (CC BY-SA 4.0)' : '') + '</div>') +
      '<div class="dict-actions">' + actionButton('Giải nghĩa AI', 'sparkles', 'dict-action--ai') +
      actionButton('Kết hợp từ', 'link') +
      actionButton('Luyện phát âm', 'microphone') + '</div>' + ui.jlptBadgeHtml(data.jlptLevel) +
      sensesHtml(data.senses);
    if (data.exampleJa) html += '<div class="dict-word-section dict-example">' +
      '<p lang="ja">' + esc(data.exampleJa) + ui.speakerButtonHtml(data.exampleJa) + '</p>' +
      '<p>' + esc(data.exampleVi || '') + '</p></div>';
    html += '<section class="dict-word-section"><h2>Tra cứu chuyên sâu với AI</h2><div class="dict-chip-row">' +
      ['Cụm từ cố định', 'Lỗi phổ biến', 'Cách dùng', 'Từ trái nghĩa', 'Đoạn văn minh họa']
        .map(function (label) { return '<button type="button" class="dict-ai-chip" data-coming-soon>' + esc(label) + '</button>'; }).join('') +
      '</div></section>' + (data.id == null ? '' : '<div data-contributions></div>');
    if (conjugations) html += '<section class="dict-word-section"><h2>Chia động từ</h2><div class="dict-table-wrap"><table class="dict-conjugation">' +
      '<thead><tr><th>Tên thể (形)</th><th>Từ</th></tr></thead><tbody>' + conjugations.map(function (row) {
        return '<tr><td>' + esc(row.label) + '</td><td lang="ja">' + esc(row.form) + '</td></tr>';
      }).join('') + '</tbody></table></div></section>';
    if ((data.antonyms || []).length) html += '<section class="dict-word-section"><h2>Từ trái nghĩa</h2>' +
      data.antonyms.map(relatedRowHtml).join('') + '</section>';
    html += '<section id="dict-word-kanji" class="dict-word-section" hidden></section>' +
      '<section id="dict-related-words" class="dict-word-section" hidden></section></article>';
    els.detail.innerHTML = html;
    var contribHost = els.detail.querySelector('[data-contributions]');
    if (contribHost) {
      contribHost.dataset.vocabularyId = data.id;
      loadContributions(contribHost, 0, token);
    }
    renderOtherResults();
    fillKanjiAndRelated(data, token);
  }
  function renderKanjiRelated() {
    var host = byId('dict-kanji-related');
    if (!host) return;
    host.innerHTML = (state.kanjiRelated.length
      ? state.kanjiRelated.slice(0, 5).map(relatedRowHtml).join('')
      : '<p class="dict-detail__empty">Chưa có từ vựng liên quan.</p>') +
      (state.kanjiCharacter ? '<a class="dict-text-button" href="' +
        esc(relatedWordsUrl([state.kanjiCharacter])) + '">Xem thêm</a>' : '');
  }

  async function loadContributions(host, page, token) {
    try {
      var result = await window.DictApi.vocabularyContributions(host.dataset.vocabularyId, page, 5);
      if (token === state.token && host.isConnected) {
        host.dataset.page = result.page;
        host.innerHTML = ui.contributionsHtml(result);
      }
      return result;
    } catch (_err) {
      if (token === state.token && host.isConnected) ui.toast('Không thể tải ý kiến đóng góp');
    }
  }

  function isLoggedIn() {
    return !!(window.JlasAuth && window.JlasAuth.isLoggedIn && window.JlasAuth.isLoggedIn());
  }

  async function handleContributionClick(event) {
    var button = event.target.closest('[data-contribution-page], [data-contribution-vote], [data-contribution-report], [data-contribution-send]');
    if (!button) return false;
    var host = button.closest('[data-contributions]');
    if (!host) return false;
    if (button.hasAttribute('data-contribution-page')) {
      loadContributions(host, Number(button.dataset.contributionPage), state.token);
      return true;
    }
    if (button.hasAttribute('data-contribution-report')) {
      if (!isLoggedIn()) { ui.toast('Vui lòng đăng nhập để báo cáo'); return true; }
      if (!window.confirm('Báo cáo ý kiến này? Ý kiến sẽ bị ẩn cho đến khi được kiểm duyệt.')) return true;
      button.disabled = true;
      try {
        await window.DictApi.reportContribution(button.dataset.contributionId);
        ui.toast('Đã báo cáo. Ý kiến đã được ẩn để chờ kiểm duyệt.');
        var page = Number(host.dataset.page || 0);
        var result = await loadContributions(host, page, state.token);
        if (result && !result.content.length && page > 0 && host.isConnected) {
          await loadContributions(host, page - 1, state.token);
        }
      } catch (error) { ui.toast(error.message || 'Không thể báo cáo ý kiến'); }
      finally { button.disabled = false; }
      return true;
    }
    if (!isLoggedIn()) { ui.toast('Vui lòng đăng nhập để đóng góp ý kiến'); return true; }
    button.disabled = true;
    try {
      if (button.hasAttribute('data-contribution-vote')) {
        var vote = Number(button.dataset.contributionVote);
        await window.DictApi.voteContribution(button.dataset.contributionId,
          button.getAttribute('aria-pressed') === 'true' ? 0 : vote);
        await loadContributions(host, Number(host.dataset.page || 0), state.token);
      } else {
        var textarea = host.querySelector('textarea');
        var content = textarea && textarea.value.trim();
        if (!content) { ui.toast('Vui lòng nhập ý kiến đóng góp'); return true; }
        await window.DictApi.addVocabularyContribution(host.dataset.vocabularyId, content);
        await loadContributions(host, 0, state.token);
      }
    } catch (error) { ui.toast(error.message || 'Không thể gửi ý kiến đóng góp'); }
    finally { button.disabled = false; }
    return true;
  }

  var escapeHtml = esc;
  function jlptBadgeClass(level) {
    return 'dict-badge dict-badge--jlpt dict-badge--' + String(level || '').toLowerCase();
  }

  function kanjiReadingCardsHtml(readings) {
    var labels = {
      ON: { title: 'Onyomi', subtitle: 'Âm On' },
      KUN: { title: 'Kunyomi', subtitle: 'Âm Kun' },
      OTHER: { title: 'Cách đọc khác', subtitle: 'Khác' }
    };
    var groups = {};
    (readings || []).forEach(function (reading) {
      if (String(reading.readingType).toLowerCase() === 'nanori') return;
      var type = labels[reading.readingType] ? reading.readingType : 'OTHER';
      (groups[type] = groups[type] || []).push(reading);
    });
    var types = Object.keys(labels).filter(function (type) { return groups[type]; });
    if (!types.length) return '<p class="dict-detail__empty">Chưa có cách đọc được ghi nhận.</p>';
    return '<div class="dict-kanji-readings">' + types.map(function (type) {
      return '<article class="dict-kanji-reading-card">' +
        '<div class="dict-kanji-reading-card__head">' +
          '<div><h3>' + labels[type].title + '</h3><span>' + labels[type].subtitle + '</span></div>' +
        '</div>' +
        '<div class="dict-kanji-reading-card__values">' + groups[type].map(function (reading) {
          return '<span class="dict-kanji-reading-card__value">' + escapeHtml(reading.readingText) +
            (reading.common ? '<small>Phổ biến</small>' : '') + '</span>';
        }).join('') + '</div>' +
      '</article>';
    }).join('') + '</div>';
  }

  function detailActionsHtml() {
    return '' +
      '<div class="dict-detail__actions">' +
        '<span class="dict-icon-btn" title="Phát âm · Sắp ra mắt"><i class="fa-solid fa-volume-high"></i></span>' +
        '<span class="dict-icon-btn" title="Lưu từ · Sắp ra mắt"><i class="fa-regular fa-bookmark"></i></span>' +
      '</div>';
  }

  function renderKanjiDetail(kanji) {
    var relatedVocabulary = kanji.relatedVocabulary || [];
    state.kanjiRelated = relatedVocabulary;
    state.kanjiCharacter = kanji.character;
    var radical = kanji.radical;
    var radicalHtml = radical
      ? '<div class="dict-kanji-radical">' +
          '<span class="dict-kanji-radical__glyph">' + escapeHtml(radical.character) + '</span>' +
          '<div class="dict-kanji-radical__body">' +
            '<span class="dict-kanji-radical__label">Bộ thủ</span>' +
            '<strong>' + escapeHtml(radical.nameVi || radical.nameJa || ('Bộ #' + radical.radicalNumber)) + '</strong>' +
            (radical.nameJa && radical.nameVi ? '<span>' + escapeHtml(radical.nameJa) + '</span>' : '') +
            (radical.meaningVi ? '<p>' + escapeHtml(radical.meaningVi) + '</p>' : '') +
          '</div>' +
          '<div class="dict-kanji-radical__meta">' +
            (radical.radicalNumber ? '<span>#' + radical.radicalNumber + '</span>' : '') +
            (radical.strokeCount ? '<span>' + radical.strokeCount + ' nét</span>' : '') +
          '</div>' +
        '</div>'
      : '<p class="dict-detail__empty">Chưa có thông tin bộ thủ.</p>';

    els.detail.innerHTML = '' +
      '<article class="dict-kanji-detail">' +
        '<header class="dict-kanji-detail__hero">' +
          '<div class="dict-kanji-glyph-panel">' +
            '<div class="dict-kanji-glyph-panel__top">' +
              '<span>Hán tự</span>' +
              (kanji.jlptLevel ? '<span class="' + jlptBadgeClass(kanji.jlptLevel) + '">' + escapeHtml(kanji.jlptLevel) + '</span>' : '') +
            '</div>' +
            '<div class="dict-kanji-glyph" aria-label="Hán tự ' + escapeHtml(kanji.character) + '">' + escapeHtml(kanji.character) + '</div>' +
            '<dl class="dict-kanji-facts">' +
              (kanji.hanViet ? '<div><dt>Hán Việt</dt><dd>' + escapeHtml(kanji.hanViet) + '</dd></div>' : '') +
              (kanji.strokeCount ? '<div><dt>Số nét</dt><dd>' + kanji.strokeCount + '</dd></div>' : '') +
            '</dl>' +
          '</div>' +
          '<div class="dict-kanji-semantic">' +
            '<div class="dict-kanji-semantic__head">' +
              '<div><span class="dict-kanji-kicker">Ý nghĩa</span>' +
                '<h1>' + escapeHtml(kanji.meaningVi || 'Chưa có nghĩa được ghi nhận') + '</h1></div>' +
              detailActionsHtml() +
            '</div>' +
            '<section class="dict-kanji-semantic__section" aria-labelledby="dict-kanji-readings-title">' +
              '<div class="dict-kanji-section-heading"><h2 id="dict-kanji-readings-title">Cách đọc</h2>' +
                '<span>Onyomi và Kunyomi</span></div>' +
              kanjiReadingCardsHtml(kanji.readings) +
            '</section>' +
            '<section class="dict-kanji-semantic__section" aria-labelledby="dict-kanji-radical-title">' +
              '<div class="dict-kanji-section-heading"><h2 id="dict-kanji-radical-title">Bộ thủ</h2></div>' +
              radicalHtml +
            '</section>' +
          '</div>' +
        '</header>' +

        '<section class="dict-kanji-section dict-kanji-stroke" aria-labelledby="dict-kanji-stroke-title">' +
          '<div class="dict-kanji-section-heading dict-kanji-section-heading--action">' +
            '<div><h2 id="dict-kanji-stroke-title">Thứ tự nét</h2>' +
              '<p>' + (kanji.strokeCount ? kanji.strokeCount + ' nét · ' : '') + 'Hoạt ảnh từ dữ liệu KanjiVG</p></div>' +
            (kanji.strokeSvgUri ? '<button type="button" id="dict-stroke-replay" class="btn btn--ghost btn--sm">' +
              '<i class="fa-solid fa-rotate-right"></i> Phát lại</button>' : '') +
          '</div>' +
          '<div class="dict-kanji-stroke__stage">' +
            (kanji.strokeSvgUri
              ? '<div id="dict-stroke-order" class="dict-stroke-order dict-kanji-stroke__canvas"></div>'
              : '<div class="dict-kanji-stroke__empty"><i class="fa-solid fa-pen-nib"></i>' +
                  '<p>Chưa có dữ liệu thứ tự nét cho Hán tự này.</p></div>') +
          '</div>' +
        '</section>' +

        '<section class="dict-kanji-section" aria-labelledby="dict-kanji-vocab-title">' +
          '<div class="dict-kanji-section-heading">' +
            '<h2 id="dict-kanji-vocab-title">Từ vựng chứa Kanji</h2>' +
             '<span>' + Math.min(relatedVocabulary.length, 5) + ' từ hiển thị</span>' +
          '</div>' +
          '<div id="dict-kanji-related"></div>' +
        '</section>' +

        '<footer class="dict-kanji-cta">' +
          '<div><span class="dict-kanji-cta__icon"><i class="fa-solid fa-graduation-cap"></i></span>' +
            '<div><strong>Luyện ghi nhớ ' + escapeHtml(kanji.character) + '</strong>' +
              '<p>Bộ thẻ và bài luyện viết sẽ được bổ sung trong phiên bản tiếp theo.</p></div></div>' +
          '<div class="dict-kanji-cta__actions">' +
            '<button type="button" class="btn btn--secondary" disabled title="Sắp ra mắt">Thêm vào bộ thẻ</button>' +
            '<button type="button" class="btn btn--primary" disabled title="Sắp ra mắt">Luyện viết · Sắp ra mắt</button>' +
          '</div>' +
        '</footer>' +
      '</article>';

    renderKanjiRelated();
    if (kanji.strokeSvgUri) {
      var container = byId('dict-stroke-order');
      window.DictStrokeOrder.mount(container, kanji.strokeSvgUri).then(function (replay) {
        var replayBtn = byId('dict-stroke-replay');
        if (!replay) {
          // Animation thất bại (ví dụ mạng lỗi) -> fallback về ảnh tĩnh thay vì để trống.
          if (!container.isConnected) return;
          container.innerHTML = '<img class="dict-stroke-order__fallback" src="' + escapeHtml(kanji.strokeSvgUri) +
            '" alt="Thứ tự nét ' + escapeHtml(kanji.character) + '">';
          // .btn (buttons.css) forces display:inline-flex, which would ignore [hidden];
          // remove the element outright instead of trying to hide it.
          if (replayBtn) replayBtn.remove();
          return;
        }
        if (replayBtn) replayBtn.addEventListener('click', replay);
      });
    }
  }

  async function loadWord(result, token) {
    var list = result && result.vocabularyResults && result.vocabularyResults.content || [];
    var mock = window.DictMock.words[state.query];
    var chosen = state.selectedId ? list.find(function (v) { return String(v.id) === state.selectedId; }) : null;
    if (state.selectedId && !chosen) chosen = { id: state.selectedId };
    if (!chosen) chosen = list.find(function (v) { return v.expression === state.query; }) || list[0];
    if (!chosen) { if (mock) renderWord(mock, token); else showEmpty(); return; }
    try {
      var detail = await window.DictApi.vocabularyDetail(chosen.id);
      if (token !== state.token) return;
      renderWord(detail, token);
    } catch (_err) {
      if (token !== state.token) return;
      if (mock) renderWord(mock, token); else showEmpty();
    }
  }
  async function loadKanji(result, token) {
    var first = Array.from(state.query).find(function (ch) { return /[\u3400-\u9fff]/.test(ch); });
    var character = first || ((result && result.kanjiResults || [])[0] || {}).character;
    if (!character) { showEmpty(); return; }
    try {
      var kanji = await window.DictApi.kanjiDetail(character);
      if (token === state.token) renderKanjiDetail(kanji);
    } catch (_err) {
      if (token !== state.token) return;
      var mock = window.DictMock.kanji[character];
      if (mock) renderKanjiDetail(mock); else showEmpty();
    }
  }
  async function renderRoute() {
    var route = parseRoute();
    state.tab = route.tab;
    state.query = route.query;
    state.page = 0;
    state.otherPageToken++;
    var token = ++state.token;
    els.input.value = state.query;
    setShell(!state.query);
    hideSuggestions();
    if (!state.query) { showLoading(false); return; }
    els.detail.innerHTML = '';
    state.result = null;
    if (state.tab === 'example' || state.tab === 'grammar' || state.tab === 'japanese') {
      showEmpty(); return;
    }
    showLoading(true);
    var result = null;
    try { result = await window.DictApi.search(state.query, 0, state.size); } catch (_err) { /* Dữ liệu mẫu khi API không sẵn sàng. */ }
    if (token !== state.token) return;
    state.result = result;
    try {
      if (state.tab === 'word') await loadWord(result, token);
      else await loadKanji(result, token);
    } finally {
      if (token === state.token) showLoading(false);
    }
  }

  function hideSuggestions() { els.suggest.hidden = true; els.suggest.innerHTML = ''; }
  function renderSuggestions(result) {
    if (document.activeElement !== els.input) return;
    var words = result && result.vocabularyResults && result.vocabularyResults.content || [];
    var kanji = result && result.kanjiResults || [];
    var items = words.slice(0, 5).map(function (word) {
      return '<li><button type="button" class="dict-suggest__item" data-word="' + esc(word.expression) +
        '" data-vocab-id="' + esc(word.id) + '"><strong>' + esc(word.expression) + '</strong> ' +
        esc(word.reading || '') + '<small>' + esc(firstGloss(word)) + '</small></button></li>';
    });
    if (kanji.length) items.push('<li><button type="button" class="dict-suggest__item" data-kanji="' +
      esc(kanji[0].character) + '">' + esc(kanji[0].character) + ' · Hán tự</button></li>');
    els.suggest.innerHTML = items.join('');
    els.suggest.hidden = !items.length;
  }
  async function searchSuggestions() {
    var query = els.input.value.trim();
    if (!query) { hideSuggestions(); return; }
    try {
      var result = await window.DictApi.search(query, 0, 6);
      if (els.input.value.trim() !== query) return;
      state.suggestionResult = result;
      renderSuggestions(result);
    } catch (_err) { hideSuggestions(); }
  }
  async function loadOtherPage(page) {
    if (page < 0 || !state.result || !state.result.vocabularyResults) return;
    var token = state.token;
    var request = ++state.otherPageToken;
    Array.prototype.forEach.call(els.otherPagination.querySelectorAll('button'), function (button) { button.disabled = true; });
    try {
      var result = await window.DictApi.search(state.query, page, state.size);
      if (token !== state.token || request !== state.otherPageToken) return;
      state.page = page;
      state.result = result;
      renderOtherResults();
    } catch (_err) {
      if (token === state.token && request === state.otherPageToken) {
        renderOtherResults();
        ui.toast('Không thể tải kết quả');
      }
    }
  }
  function commitSearch() {
    var query = els.input.value.trim();
    if (/^[a-zA-Z][a-zA-Z'-]*$/.test(query) && window.DictRomaji) {
      query = window.DictRomaji.toHiragana(query) || query;
    }
    els.input.value = query;
    navigate(state.tab, query);
  }
  async function loadRecent() {
    if (!window.JlasAuth || !window.JlasAuth.isLoggedIn || !window.JlasAuth.isLoggedIn()) {
      els.recentEmpty.hidden = false; return;
    }
    try {
      var seen = new Set();
      var entries = (await window.DictApi.history() || []).filter(function (entry) {
        if (seen.has(entry.queryText)) return false;
        seen.add(entry.queryText);
        return true;
      }).slice(0, 6);
      els.recentEmpty.hidden = !!entries.length;
      if (!entries.length) els.recentEmpty.textContent = 'Chưa có lịch sử tra cứu nào.';
      var cards = await Promise.all(entries.map(async function (entry) {
        var detail = null;
        if (entry.selectedVocabularyId) {
          try { detail = await window.DictApi.vocabularyDetail(entry.selectedVocabularyId); }
          catch (_err) { /* Hiển thị truy vấn khi chi tiết không còn sẵn. */ }
        }
        if (detail) return '<button type="button" class="dict-recent-card" data-word="' +
          esc(detail.expression) + '" data-vocab-id="' + esc(detail.id || entry.selectedVocabularyId) + '">' +
          '<span class="dict-recent-card__head"><span class="dict-recent-card__expression">' +
          esc(detail.expression) + '</span>' + ui.jlptBadgeHtml(detail.jlptLevel) + '</span>' +
          '<span class="dict-recent-card__reading">' + esc(detail.reading || '') + '</span>' +
          '<span class="dict-recent-card__gloss">' + esc(firstGloss(detail)) + '</span></button>';
        return '<button type="button" class="dict-recent-card" data-word="' + esc(entry.queryText) +
          '"><span class="dict-recent-card__expression">' + esc(entry.queryText) + '</span>' +
          '<span class="dict-recent-card__gloss">' + esc(entry.resultCount || 0) +
          ' kết quả</span></button>';
      }));
      els.recent.innerHTML = cards.join('');
    } catch (_err) {
      els.recentEmpty.hidden = false;
      els.recentEmpty.textContent = 'Không thể tải lịch sử tra cứu lúc này.';
    }
  }
  function init() {
    els.hero = byId('dict-hero'); els.homePanels = byId('dict-home-panels');
    els.input = byId('dict-search-input'); els.clear = byId('dict-search-clear');
    els.suggest = byId('dict-suggest'); els.tabs = byId('dict-tabs');
    els.layout = byId('dict-results-layout'); els.detail = byId('dict-detail-pane');
    els.other = byId('dict-other-results'); els.vocabList = byId('dict-vocab-list');
    els.otherPagination = byId('dict-other-pagination'); els.status = byId('dict-status');
    els.recent = byId('dict-recent-list'); els.recentEmpty = byId('dict-recent-empty');
    els.input.addEventListener('input', function () {
      els.clear.hidden = !els.input.value;
      window.clearTimeout(suggestionTimer);
      suggestionTimer = window.setTimeout(searchSuggestions, 250);
    });
    els.input.addEventListener('focus', function () { if (state.suggestionResult) renderSuggestions(state.suggestionResult); });
    els.input.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') hideSuggestions();
      if (event.key === 'Enter') { event.preventDefault(); commitSearch(); }
    });
    els.clear.addEventListener('click', function () {
      els.input.value = ''; els.clear.hidden = true; hideSuggestions(); els.input.focus();
    });
    els.tabs.addEventListener('click', function (event) {
      var button = event.target.closest('[data-tab]');
      if (button) navigate(button.dataset.tab, state.query);
    });
    document.addEventListener('click', function (event) {
      if (event.target.closest('[data-contribution-page], [data-contribution-vote], [data-contribution-report], [data-contribution-send]')) {
        handleContributionClick(event);
        return;
      }
      if (!event.target.closest('.dict-search-bar')) hideSuggestions();
      var coming = event.target.closest('[data-coming-soon]');
      if (coming) ui.toast('Sắp ra mắt');
      var otherPage = event.target.closest('[data-other-page]');
      if (otherPage && !otherPage.disabled) { loadOtherPage(Number(otherPage.dataset.otherPage)); return; }
      var kanji = event.target.closest('[data-kanji]');
      if (kanji) { navigate('kanji', kanji.dataset.kanji); return; }
      var word = event.target.closest('[data-word]');
      if (word) navigate('word', word.dataset.word, word.dataset.vocabId || null);
    });
    byId('dict-open-handwriting').addEventListener('click', function () {
      if (window.DictHandwriting) window.DictHandwriting.clear();
      if (window.JlasUI) window.JlasUI.openModal('dict-handwriting-modal');
    });
    byId('dict-mode-radical').addEventListener('click', function () {
      if (window.DictRadical) window.DictRadical.open();
    });
    window.addEventListener('popstate', function () { state.selectedId = null; renderRoute(); });
    renderRoute();
    loadRecent();
  }
  document.addEventListener('DOMContentLoaded', init);
})();
