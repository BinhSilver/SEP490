/* [JLAS-W0108] F36 + F37: jpath-ai-explain.js — AI giải thích & hỏi đáp tiếp nối cho câu hỏi trong Lộ trình học
 * (JLPT Check/Quiz, Đọc/Nghe, Giao tiếp Nhập vai & Kiểm tra tuần).
 *
 * Cách gắn trên trang câu hỏi (chỉ xuất khi người học ĐÃ chọn/khóa đáp án):
 *   <div class="jp-ai-slot" data-jpath-ai-explain='{"source":"JPATH_CHECK","checkToken":"...","questionId":"..."}'></div>
 *
 * Chính sách lượt & CP:
 *   - Tài khoản thường: 1 lượt dùng thử miễn phí đầu tiên, sau đó mua 1 lượt = 3 CP.
 *   - Tài khoản Pro: miễn phí 30 lượt/ngày, vượt trần mua bằng CP.
 *   - Xem lại cùng câu đã giải thích trong ngày: 0 CP.
 *   - Hộp xác nhận mua (F37): tích "Không hỏi lại lần sau" thì các lần sau bấm nút Mua là trừ CP ngay 1 chạm.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathAiExplain = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', function () { api.mount(); }, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE_URL = '/api/learning/ai-explain';
  var MAX_FOLLOW_UPS = 5;
  var MAX_Q_LEN = 300;
  var BOUND_ATTR = 'data-jp-ai-bound';
  var skipMemory = {};
  var sessionCache = {};

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function sparkleSvg() {
    return '<svg class="jp-ai__sparkle" viewBox="0 0 20 20" fill="none" aria-hidden="true">'
      + '<path d="M10 2l1.8 5.2L17 9l-5.2 1.8L10 16l-1.8-5.2L3 9l5.2-1.8L10 2z" fill="currentColor"/>'
      + '<path d="M16 13l.8 2.2L19 16l-2.2.8L16 19l-.8-2.2L13 16l2.2-.8L16 13z" fill="currentColor" opacity=".7"/></svg>';
  }

  function userIdPart() {
    var auth = root && root.JlasAuth;
    var u = auth && typeof auth.getUser === 'function' ? auth.getUser() : null;
    return u ? String(u.id || u.userId || 'me') : 'me';
  }

  function skipKey() {
    return 'jlas_buy_confirm_skip_' + userIdPart() + '_aiExplain';
  }

  function isSkipConfirm(storage) {
    var k = skipKey();
    var st = storage !== undefined ? storage : (root && root.localStorage);
    try {
      if (st && typeof st.getItem === 'function') return st.getItem(k) === '1';
    } catch (e) { /* memory fallback */ }
    return skipMemory[k] === '1';
  }

  function setSkipConfirm(skip, storage) {
    var k = skipKey();
    var val = skip ? '1' : '0';
    skipMemory[k] = val;
    var st = storage !== undefined ? storage : (root && root.localStorage);
    try {
      if (st && typeof st.setItem === 'function') {
        if (skip) st.setItem(k, '1');
        else if (typeof st.removeItem === 'function') st.removeItem(k);
      }
    } catch (e) { /* memory fallback */ }
  }

  function parseContext(raw) {
    var obj;
    try {
      obj = typeof raw === 'string' ? JSON.parse(raw) : raw;
    } catch (e) {
      return null;
    }
    if (!obj || typeof obj !== 'object') return null;
    var hasCheck = !!(obj.checkToken && obj.questionId);
    var hasDirect = !!(obj.prompt && obj.correctAnswer);
    if (!hasCheck && !hasDirect) return null;
    return {
      source: String(obj.source || 'JPATH_CHECK'),
      checkToken: obj.checkToken ? String(obj.checkToken) : null,
      questionId: obj.questionId ? String(obj.questionId) : null,
      contextText: obj.contextText ? String(obj.contextText) : null,
      prompt: obj.prompt ? String(obj.prompt) : null,
      options: Array.isArray(obj.options) ? obj.options.map(function (x) { return String(x == null ? '' : x); }) : null,
      userAnswer: obj.userAnswer != null ? String(obj.userAnswer) : null,
      correctAnswer: obj.correctAnswer != null ? String(obj.correctAnswer) : null,
      baseExplanation: obj.baseExplanation != null ? String(obj.baseExplanation) : null
    };
  }

  function slotCacheId(ctx) {
    if (ctx.checkToken && ctx.questionId) return ctx.source + ':' + ctx.checkToken + ':' + ctx.questionId;
    return ctx.source + ':' + (ctx.prompt || '') + ':' + (ctx.userAnswer || '') + ':' + (ctx.correctAnswer || '');
  }

  function explainCardHtml(data) {
    var ex = (data && data.explanation) || {};
    var pts = Array.isArray(ex.keyPoints) ? ex.keyPoints : [];
    var pointsHtml = pts.length ? '<div class="jp-ai__section"><h4 class="jp-ai__sub">Từ vựng &amp; cấu trúc trọng tâm</h4>'
      + '<ul class="jp-ai__points">' + pts.map(function (p) {
        return '<li class="jp-ai__point">'
          + '<div class="jp-ai__point-head"><strong lang="ja">' + esc(p.term) + '</strong>'
          + (p.reading ? ' <span class="jp-ai__reading" lang="ja">(' + esc(p.reading) + ')</span>' : '')
          + (p.meaningVi ? ' — <span>' + esc(p.meaningVi) + '</span>' : '') + '</div>'
          + (p.noteVi ? '<p class="jp-ai__point-note">' + esc(p.noteVi) + '</p>' : '')
          + '</li>';
      }).join('') + '</ul></div>' : '';

    return '<div class="jp-ai__body">'
      + (ex.summaryVi ? '<p class="jp-ai__summary">' + esc(ex.summaryVi) + '</p>' : '')
      + (ex.whyRightVi ? '<div class="jp-ai__section"><h4 class="jp-ai__sub jp-ai__sub--ok">Vì sao đáp án đúng</h4><p>' + esc(ex.whyRightVi) + '</p></div>' : '')
      + (ex.whyWrongVi ? '<div class="jp-ai__section"><h4 class="jp-ai__sub jp-ai__sub--warn">Lưu ý chỗ dễ nhầm</h4><p>' + esc(ex.whyWrongVi) + '</p></div>' : '')
      + pointsHtml
      + (ex.tipVi ? '<div class="jp-ai__tip"><strong>Mẹo nhớ:</strong> ' + esc(ex.tipVi) + '</div>' : '')
      + (data && data.chargeNote ? '<p class="jp-ai__meta">' + esc(data.chargeNote) + '</p>' : '')
      + '</div>';
  }

  function chatHtml(turns, pendingNote) {
    var list = Array.isArray(turns) ? turns : [];
    var userCount = list.filter(function (t) { return t.role === 'user'; }).length;
    var left = Math.max(0, MAX_FOLLOW_UPS - userCount);
    var msgs = list.length ? '<div class="jp-ai__chat-log">' + list.map(function (t) {
      var isAi = t.role === 'assistant';
      return '<div class="jp-ai__msg ' + (isAi ? 'jp-ai__msg--ai' : 'jp-ai__msg--user') + '">'
        + '<span class="jp-ai__msg-who">' + (isAi ? 'Gia sư AI' : 'Bạn') + '</span>'
        + '<p class="jp-ai__msg-text">' + esc(t.text) + '</p>'
        + (t.note ? '<span class="jp-ai__msg-note">' + esc(t.note) + '</span>' : '')
        + '</div>';
    }).join('') + '</div>' : '';

    var form = left > 0
      ? '<form class="jp-ai__ask-form" data-ai-ask-form novalidate>'
        + '<input type="text" class="jp-ai__ask-input no-inline-dictionary" data-ai-ask-input maxlength="' + MAX_Q_LEN + '"'
        + ' placeholder="Hỏi thêm AI về câu này (còn ' + left + ' câu hỏi thêm)…" autocomplete="off">'
        + '<button type="submit" class="jp-btn jp-btn--primary jp-ai__ask-btn" data-ai-ask-send>Hỏi AI</button>'
        + '</form>'
      : '<p class="jp-ai__meta">Đã dùng tối đa ' + MAX_FOLLOW_UPS + ' câu hỏi thêm cho câu này.</p>';

    return '<div class="jp-ai__chat">' + msgs + form
      + (pendingNote ? '<p class="jp-ai__status" role="status">' + esc(pendingNote) + '</p>' : '<p class="jp-ai__status" role="status" data-ai-chat-status></p>')
      + '<div data-ai-chat-paywall></div></div>';
  }

  function paywallHtml(allowance, skipConfirm) {
    var st = allowance || {};
    var price = Number(st.packPrice) || 3;
    var packTurns = Number(st.packTurns) || 1;
    var balance = Number(st.creditBalance) || 0;
    var enough = balance >= price;
    var title = st.pro
      ? 'Bạn đã dùng hết ' + (st.proDailyLimit || 30) + ' lượt AI giải thích miễn phí hôm nay'
      : 'Bạn đã hết lượt AI giải thích miễn phí';
    var detail = enough
      ? 'Mua ' + packTurns + ' lượt giải thích: <strong>' + price + ' CP</strong>. Số dư: ' + balance + ' CP → ' + (balance - price) + ' CP.'
      : 'Mua ' + packTurns + ' lượt cần <strong>' + price + ' CP</strong>. Bạn đang có ' + balance + ' CP, còn thiếu <strong>' + (price - balance) + ' CP</strong>.';
    var hint = st.pro
      ? 'Lượt miễn phí gói Pro sẽ làm mới vào ngày mai.'
      : 'Tài khoản thường có 1 lượt dùng thử đầu tiên; gói Pro có ' + (st.proDailyLimit || 30) + ' lượt miễn phí mỗi ngày. Xem lại cùng câu trong ngày không tốn lượt.';
    var skipLine = enough && skipConfirm
      ? '<p class="jp-ai__meta" data-ai-skip-note>Mua nhanh 1 chạm đang bật · <button type="button" class="jp-ai__inline-link" data-ai-reset-confirm>Hỏi lại trước khi mua</button></p>'
      : '';
    var confirmBox = enough
      ? '<div class="jp-ai__confirm" data-ai-confirm-box hidden>'
        + '<p>Xác nhận dùng <strong>' + price + ' CP</strong> để mua ' + packTurns + ' lượt AI giải thích? (Số dư sau mua: ' + (balance - price) + ' CP)</p>'
        + '<label class="jp-ai__check"><input type="checkbox" data-ai-skip-box> <span>Không hỏi lại lần sau (chỉ cần bấm Mua là xong)</span></label>'
        + '<div class="jp-ai__actions">'
        + '<button type="button" class="jp-btn jp-btn--primary" data-ai-confirm-yes>Xác nhận mua</button>'
        + '<button type="button" class="jp-btn" data-ai-confirm-no>Hủy</button>'
        + '</div></div>'
      : '';
    var buyBtn = enough
      ? '<button type="button" class="jp-btn jp-btn--primary" data-ai-buy>Mua ' + packTurns + ' lượt (' + price + ' CP)</button>'
      : '<a class="jp-btn jp-btn--primary" href="/credits">Nạp thêm CP</a>';

    return '<div class="jp-ai__paywall">'
      + '<strong class="jp-ai__paywall-title">' + esc(title) + '</strong>'
      + '<p class="jp-ai__paywall-detail">' + detail + '</p>'
      + '<p class="jp-ai__meta">' + esc(hint) + '</p>'
      + skipLine
      + confirmBox
      + '<div class="jp-ai__actions" data-ai-main-actions>' + buyBtn
      + (st.pro ? '' : '<a class="jp-btn" href="/premium">Nâng cấp Pro</a>')
      + '</div>'
      + '<p class="jp-ai__status" role="status" data-ai-pay-status></p>'
      + '</div>';
  }

  function client() {
    return root && root.JpathApi;
  }

  function renderSlotState(host, entry) {
    var panel = host.querySelector('[data-ai-panel]');
    var toggle = host.querySelector('[data-ai-toggle]');
    if (!panel || !toggle) return;
    toggle.setAttribute('aria-expanded', entry.open ? 'true' : 'false');
    panel.hidden = !entry.open;
    if (!entry.open) return;

    if (entry.loading) {
      panel.innerHTML = '<div class="jp-ai__card"><p class="jp-ai__loading" role="status">' + sparkleSvg()
        + ' <span>AI đang phân tích câu hỏi…</span></p></div>';
      return;
    }
    if (entry.paywall && !entry.data) {
      panel.innerHTML = '<div class="jp-ai__card">' + paywallHtml(entry.paywall, isSkipConfirm()) + '</div>';
      return;
    }
    if (entry.error && !entry.data) {
      panel.innerHTML = '<div class="jp-ai__card"><p class="jp-ai__error" role="alert">' + esc(entry.error) + '</p>'
        + '<div class="jp-ai__actions"><button type="button" class="jp-btn" data-ai-retry>Thử lại</button></div></div>';
      return;
    }
    if (entry.data) {
      panel.innerHTML = '<div class="jp-ai__card">'
        + '<div class="jp-ai__head"><strong>' + sparkleSvg() + ' Giải thích chi tiết từ AI</strong>'
        + '<button type="button" class="jp-ai__close" data-ai-close aria-label="Thu gọn giải thích">Thu gọn</button></div>'
        + explainCardHtml(entry.data)
        + chatHtml(entry.turns, entry.asking ? 'AI đang trả lời…' : '')
        + '</div>';
      if (entry.chatPaywall) {
        var pwHost = panel.querySelector('[data-ai-chat-paywall]');
        if (pwHost) pwHost.innerHTML = paywallHtml(entry.chatPaywall, isSkipConfirm());
      }
      if (entry.chatError) {
        var st = panel.querySelector('[data-ai-chat-status]');
        if (st) st.textContent = entry.chatError;
      }
      var inp = panel.querySelector('[data-ai-ask-input]');
      if (inp && entry.draftQuestion) inp.value = entry.draftQuestion;
    }
  }

  function bindSlot(host) {
    if (!host || host.getAttribute(BOUND_ATTR)) return;
    var ctx = parseContext(host.getAttribute('data-jpath-ai-explain'));
    if (!ctx) return;
    host.setAttribute(BOUND_ATTR, '1');

    var key = slotCacheId(ctx);
    var entry = sessionCache[key] || {
      open: false,
      loading: false,
      asking: false,
      data: null,
      turns: [],
      error: null,
      paywall: null,
      chatPaywall: null,
      chatError: null,
      pendingAction: null,
      draftQuestion: ''
    };
    sessionCache[key] = entry;

    host.innerHTML = '<div class="jp-ai">'
      + '<button type="button" class="jp-ai__btn" data-ai-toggle aria-expanded="' + (entry.open ? 'true' : 'false') + '">'
      + sparkleSvg() + '<span>AI giải thích</span></button>'
      + '<div class="jp-ai__panel" data-ai-panel' + (entry.open ? '' : ' hidden') + '></div>'
      + '</div>';

    if (entry.open) renderSlotState(host, entry);

    function fetchAllowance(forChat) {
      var api = client();
      if (!api) return;
      api.get(BASE_URL + '/allowance').then(function (st) {
        if (forChat) entry.chatPaywall = st;
        else entry.paywall = st;
        renderSlotState(host, entry);
      }, function () {
        var msg = 'Chưa kiểm tra được số lượt AI. Hãy thử lại.';
        if (forChat) entry.chatError = msg;
        else entry.error = msg;
        renderSlotState(host, entry);
      });
    }

    function runExplain() {
      var api = client();
      if (!api) return;
      entry.loading = true;
      entry.error = null;
      entry.paywall = null;
      entry.pendingAction = 'explain';
      renderSlotState(host, entry);
      api.send('POST', BASE_URL + '/explain', ctx).then(function (res) {
        entry.loading = false;
        entry.data = res;
        entry.pendingAction = null;
        renderSlotState(host, entry);
      }, function (err) {
        entry.loading = false;
        if (err && err.code === 'JPATH_AI_EXPLAIN_EXHAUSTED') {
          fetchAllowance(false);
          return;
        }
        entry.error = (err && (err.code || err.status) && err.message) || 'AI giải thích tạm thời chưa phản hồi. Lượt của bạn không bị trừ.';
        renderSlotState(host, entry);
      });
    }

    function runAsk(questionText) {
      var api = client();
      var qText = String(questionText || '').trim();
      if (!api || !qText || entry.asking) return;
      entry.asking = true;
      entry.chatError = null;
      entry.chatPaywall = null;
      entry.draftQuestion = qText;
      entry.pendingAction = 'ask';
      renderSlotState(host, entry);
      var historyPayload = entry.turns.map(function (t) { return { role: t.role, text: t.text }; });
      var body = {
        source: ctx.source,
        checkToken: ctx.checkToken,
        questionId: ctx.questionId,
        contextText: ctx.contextText,
        prompt: ctx.prompt,
        options: ctx.options,
        userAnswer: ctx.userAnswer,
        correctAnswer: ctx.correctAnswer,
        question: qText,
        history: historyPayload
      };
      api.send('POST', BASE_URL + '/ask', body).then(function (res) {
        entry.asking = false;
        entry.draftQuestion = '';
        entry.pendingAction = null;
        entry.turns.push({ role: 'user', text: qText });
        entry.turns.push({ role: 'assistant', text: (res && res.replyVi) || '', note: res && res.chargeNote });
        renderSlotState(host, entry);
      }, function (err) {
        entry.asking = false;
        if (err && err.code === 'JPATH_AI_EXPLAIN_EXHAUSTED') {
          fetchAllowance(true);
          return;
        }
        entry.chatError = (err && (err.code || err.status) && err.message) || 'Chưa nhận được phản hồi AI. Lượt của bạn không bị trừ.';
        renderSlotState(host, entry);
      });
    }

    function executeBuy(triggerBtn) {
      var api = client();
      if (!api) return;
      if (triggerBtn) triggerBtn.disabled = true;
      var payStatus = host.querySelector('[data-ai-pay-status]');
      if (payStatus) payStatus.textContent = 'Đang mua lượt…';
      api.send('POST', BASE_URL + '/pack', {}).then(function () {
        entry.paywall = null;
        entry.chatPaywall = null;
        if (entry.pendingAction === 'ask' && entry.draftQuestion) {
          runAsk(entry.draftQuestion);
        } else {
          runExplain();
        }
      }, function (err) {
        if (triggerBtn) triggerBtn.disabled = false;
        if (payStatus) payStatus.textContent = (err && (err.code || err.status) && err.message) || 'Không thể mua lượt lúc này.';
      });
    }

    host.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;

      if (t.closest('[data-ai-toggle]')) {
        ev.stopPropagation();
        entry.open = !entry.open;
        if (entry.open && !entry.data && !entry.loading) {
          runExplain();
        } else {
          renderSlotState(host, entry);
        }
        return;
      }
      if (t.closest('[data-ai-close]')) {
        ev.stopPropagation();
        entry.open = false;
        renderSlotState(host, entry);
        return;
      }
      if (t.closest('[data-ai-retry]')) {
        ev.stopPropagation();
        runExplain();
        return;
      }
      if (t.closest('[data-ai-reset-confirm]')) {
        ev.stopPropagation();
        setSkipConfirm(false);
        renderSlotState(host, entry);
        return;
      }
      var buyBtn = t.closest('[data-ai-buy]');
      if (buyBtn) {
        ev.stopPropagation();
        if (!isSkipConfirm()) {
          var cBox = host.querySelector('[data-ai-confirm-box]');
          var mAct = host.querySelector('[data-ai-main-actions]');
          if (cBox) {
            cBox.hidden = false;
            if (mAct) mAct.hidden = true;
            return;
          }
        }
        executeBuy(buyBtn);
        return;
      }
      var yesBtn = t.closest('[data-ai-confirm-yes]');
      if (yesBtn) {
        ev.stopPropagation();
        var skipBox = host.querySelector('[data-ai-skip-box]');
        if (skipBox && skipBox.checked) setSkipConfirm(true);
        executeBuy(yesBtn);
        return;
      }
      if (t.closest('[data-ai-confirm-no]')) {
        ev.stopPropagation();
        var cBox2 = host.querySelector('[data-ai-confirm-box]');
        var mAct2 = host.querySelector('[data-ai-main-actions]');
        if (cBox2) cBox2.hidden = true;
        if (mAct2) mAct2.hidden = false;
        return;
      }
      if (t.closest('.jp-ai__panel')) {
        ev.stopPropagation();
      }
    });

    host.addEventListener('submit', function (ev) {
      var form = ev.target && ev.target.closest && ev.target.closest('[data-ai-ask-form]');
      if (!form) return;
      ev.preventDefault();
      ev.stopPropagation();
      var inp = form.querySelector('[data-ai-ask-input]');
      if (!inp) return;
      runAsk(inp.value);
    });
  }

  function bindAll(scope) {
    var doc = scope || (root && root.document);
    if (!doc || typeof doc.querySelectorAll !== 'function') return;
    var nodes = doc.querySelectorAll('[data-jpath-ai-explain]');
    for (var i = 0; i < nodes.length; i++) bindSlot(nodes[i]);
  }

  var mounted = false;
  function mount() {
    var doc = root && root.document;
    if (mounted || !doc) return;
    mounted = true;
    bindAll(doc);
    if (typeof doc.addEventListener === 'function') {
      doc.addEventListener('jpath:rendered', function (ev) {
        bindAll((ev && ev.detail && ev.detail.root) || doc);
      });
    }
    var main = doc.getElementById && doc.getElementById('jlabBaseContent');
    if (main && typeof root.MutationObserver === 'function') {
      var obs = new root.MutationObserver(function () { bindAll(main); });
      obs.observe(main, { childList: true, subtree: true });
    }
  }

  return {
    parseContext: parseContext,
    explainCardHtml: explainCardHtml,
    chatHtml: chatHtml,
    paywallHtml: paywallHtml,
    isSkipConfirm: isSkipConfirm,
    setSkipConfirm: setSkipConfirm,
    bindAll: bindAll,
    mount: mount
  };
});
