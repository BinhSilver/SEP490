/**
 * Hồ sơ → "Khung avatar & màu tên": chọn khung và màu tên, xem thử cả món chưa mua và mua ngay tại đây
 * (không cần sang Cửa hàng). Món đã có: bấm Lưu → PUT /api/profile/cosmetics. Món chưa có: nút đổi thành
 * "Mua · N CP" (bấm thêm lần nữa để xác nhận) → POST /api/profile/cosmetics/frames/{code}/buy.
 * Xong thì làm mới thông tin người dùng để header và sidebar dùng khung/màu mới.
 */
(function () {
  'use strict';

  var card = document.getElementById('profileCosmeticsCard');
  if (!card || typeof JlasAuth === 'undefined' || !window.JlasAvatarFrame) return;

  var API = '/api/profile/cosmetics';
  /** Thời gian chờ bấm xác nhận mua lần hai. */
  var CONFIRM_MS = 4000;
  var el = {
    preview: card.querySelector('[data-cosmetics-preview]'),
    name: card.querySelector('[data-cosmetics-name]'),
    frames: card.querySelector('[data-cosmetics-frames]'),
    names: card.querySelector('[data-cosmetics-names]'),
    save: card.querySelector('[data-cosmetics-save]'),
    status: card.querySelector('[data-cosmetics-status]')
  };
  var state = { view: null, frame: null, nameStyle: null, busy: false, confirming: null, confirmTimer: null };

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function toast(message, type) {
    if (window.JlasUI) window.JlasUI.toast(message, type || 'info');
  }

  async function call(method, body, path) {
    var res = await fetch(API + (path || ''), { method: method, headers: JlasAuth.authHeaders(), body: body ? JSON.stringify(body) : undefined });
    var json = await res.json().catch(function () { return {}; });
    if (!res.ok || !json.success) throw new Error(json.message || 'Không tải được khung avatar');
    return json.data;
  }

  function frameByCode(code) {
    return state.view.frames.find(function (frame) { return frame.code === code; }) || null;
  }

  /** Khung cần mua để dùng lựa chọn đang xem thử (khung chưa có, hoặc khung mở màu tên chưa có); null nếu đủ. */
  function frameToBuy() {
    var frame = state.frame ? frameByCode(state.frame) : null;
    if (frame && !frame.owned) return frame;
    if (!state.nameStyle) return null;
    var style = state.view.nameStyles.find(function (item) { return item.code === state.nameStyle; });
    if (!style || style.owned) return null;
    return state.view.frames.find(function (item) { return item.nameStyle === state.nameStyle; }) || null;
  }

  function dirty() {
    return state.view && (state.frame !== state.view.frameCode || state.nameStyle !== state.view.nameStyle);
  }

  function renderPreview() {
    window.JlasAvatarFrame.mount(el.preview, state.frame);
    el.name.textContent = JlasAuth.displayName(JlasAuth.getUser());
    window.JlasAvatarFrame.applyNameStyle(el.name, state.nameStyle);
    el.preview.parentElement.classList.toggle('is-trying', !!frameToBuy());
  }

  function frameOption(frame) {
    var selected = frame ? state.frame === frame.code : state.frame === null;
    var locked = frame && !frame.owned;
    return '<button type="button" role="radio" aria-checked="' + selected + '" class="profile-cosmetics__frame'
      + (selected ? ' is-selected' : '') + (locked ? ' is-locked' : '') + '" data-frame="' + esc(frame ? frame.code : '') + '"'
      + (locked ? ' title="Bấm để xem thử, rồi mua ngay tại đây"' : '') + '>'
      + (frame ? '<img src="' + esc(frame.image) + '" alt="" loading="lazy">'
        : '<span class="profile-cosmetics__none"><i class="fa-solid fa-ban" aria-hidden="true"></i></span>')
      + '<span class="profile-cosmetics__frame-name">' + esc(frame ? frame.name : 'Không dùng khung') + '</span>'
      + (locked ? '<span class="profile-cosmetics__lock"><i class="fa-solid fa-lock" aria-hidden="true"></i> ' + frame.price + ' CP</span>' : '')
      + '</button>';
  }

  function nameOption(style) {
    var selected = state.nameStyle === style.code;
    var label = '<span' + (style.code ? ' data-name-style="' + esc(style.code) + '"' : '') + '>' + esc(style.label) + '</span>';
    return '<button type="button" role="radio" aria-checked="' + selected + '" class="profile-cosmetics__chip'
      + (selected ? ' is-selected' : '') + (style.owned ? '' : ' is-locked') + '" data-name="' + esc(style.code || '') + '"'
      + (style.owned ? '' : ' title="Bấm để xem thử; mua khung cùng chủ đề để dùng"') + '>' + label
      + (style.owned ? '' : ' <i class="fa-solid fa-lock" aria-hidden="true"></i>') + '</button>';
  }

  function renderAction() {
    var buy = frameToBuy();
    var balance = state.view.balance;
    el.save.classList.toggle('is-buy', !!buy);
    if (!buy) {
      el.save.textContent = 'Lưu khung & màu tên';
      el.save.disabled = state.busy || !dirty();
      el.status.textContent = state.view.frames.some(function (f) { return f.owned; })
        ? (dirty() ? 'Chưa lưu thay đổi.' : '')
        : 'Bấm vào một khung để xem thử trên avatar của bạn, rồi mua ngay tại đây.';
      return;
    }
    var enough = balance >= buy.price;
    el.save.disabled = state.busy || !enough;
    el.save.textContent = !enough ? 'Cần thêm ' + (buy.price - balance) + ' CP'
      : state.confirming === buy.code ? 'Bấm lần nữa để mua · ' + buy.price + ' CP'
      : 'Mua · ' + buy.price + ' CP';
    el.status.textContent = 'Đang xem thử ' + buy.name + ' (kèm màu tên ' + buy.nameStyleLabel + ') · Bạn có ' + balance + ' CP.';
  }

  function render() {
    var view = state.view;
    el.frames.innerHTML = frameOption(null) + view.frames.map(frameOption).join('');
    el.names.innerHTML = view.nameStyles.map(nameOption).join('');
    renderAction();
    renderPreview();
  }

  function apply(view) {
    state.view = view;
    state.frame = view.frameCode;
    state.nameStyle = view.nameStyle;
    render();
  }

  function resetConfirm() {
    clearTimeout(state.confirmTimer);
    state.confirming = null;
  }

  el.frames.addEventListener('click', function (event) {
    var option = event.target.closest('[data-frame]');
    if (!option || !state.view || state.busy) return;
    resetConfirm();
    state.frame = option.dataset.frame || null;
    render();
  });

  el.names.addEventListener('click', function (event) {
    var option = event.target.closest('[data-name]');
    if (!option || !state.view || state.busy) return;
    resetConfirm();
    state.nameStyle = option.dataset.name || null;
    render();
  });

  async function buy(frame) {
    // Giữ màu tên đang xem thử nếu mua xong đã dùng được (vd. xem khung A với màu của khung B đã có).
    var wanted = { frame: state.frame, nameStyle: state.nameStyle };
    var view = await call('POST', null, '/frames/' + encodeURIComponent(frame.code) + '/buy');
    var nameOwned = !wanted.nameStyle || view.nameStyles.some(function (s) { return s.code === wanted.nameStyle && s.owned; });
    var frameOwned = !wanted.frame || view.frames.some(function (f) { return f.code === wanted.frame && f.owned; });
    if (nameOwned && frameOwned && (wanted.frame !== view.frameCode || wanted.nameStyle !== view.nameStyle)) {
      view = await call('PUT', { frameCode: wanted.frame, nameStyle: wanted.nameStyle });
    }
    apply(view);
    if (window.RewardFX && typeof window.RewardFX.refreshChip === 'function') window.RewardFX.refreshChip(true);
    toast('Đã mua ' + frame.name + ' và đang dùng.', 'success');
  }

  el.save.addEventListener('click', async function () {
    if (state.busy || !state.view) return;
    var toBuy = frameToBuy();
    if (toBuy && state.confirming !== toBuy.code) {
      // Lần bấm đầu chỉ hỏi lại, tránh mua nhầm.
      state.confirming = toBuy.code;
      clearTimeout(state.confirmTimer);
      state.confirmTimer = setTimeout(function () { state.confirming = null; if (state.view) renderAction(); }, CONFIRM_MS);
      renderAction();
      return;
    }
    if (!toBuy && !dirty()) return;
    resetConfirm();
    state.busy = true;
    renderAction();
    try {
      if (toBuy) await buy(toBuy);
      else {
        apply(await call('PUT', { frameCode: state.frame, nameStyle: state.nameStyle }));
        toast('Đã lưu khung avatar và màu tên.', 'success');
      }
      await JlasAuth.refreshUser();
    } catch (error) {
      toast(error.message, 'error');
    } finally {
      state.busy = false;
      if (state.view) render();
    }
  });

  call('GET').then(apply).catch(function (error) {
    el.status.textContent = error.message;
  });
})();
