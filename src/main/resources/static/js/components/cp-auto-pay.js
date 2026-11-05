/*
 * "Không hỏi lại hôm nay": sau khi người học bật, các lần dùng tốn CP của chức năng đó tự trả, không hiện popup.
 * Mỗi chức năng (aiCall, pronunciation, quickLookup) nhớ riêng theo tài khoản, sang ngày mới thì hỏi lại, và tự dùng
 * tối đa DAILY_CAP CP rồi cũng hỏi lại. Lưu ở trình duyệt nên tải lại trang vẫn nhớ; máy chủ vẫn tự kiểm tra số dư.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasCpAutoPay = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var DAILY_CAP = 100;
  // Used when the browser blocks localStorage (private window): the choice then lasts until the page reloads.
  var memory = {};

  function today() {
    var now = new Date();
    return now.getFullYear() + '-' + (now.getMonth() + 1) + '-' + now.getDate();
  }

  function key(feature) {
    var auth = typeof window !== 'undefined' ? window.JlasAuth : null;
    var user = auth && typeof auth.getUser === 'function' ? auth.getUser() : null;
    return 'jlas_cp_auto_' + (user ? (user.id || user.userId || 'me') : 'me') + '_' + feature;
  }

  function read(feature) {
    var name = key(feature);
    var raw = memory[name];
    try { raw = window.localStorage.getItem(name) || raw; } catch (ignored) { /* memory copy only */ }
    var saved = null;
    try { saved = raw ? JSON.parse(raw) : null; } catch (ignored) { saved = null; }
    return saved && saved.date === today() ? saved : null;
  }

  function write(feature, value) {
    var name = key(feature);
    var raw = JSON.stringify(value);
    memory[name] = raw;
    try { window.localStorage.setItem(name, raw); } catch (ignored) { /* memory copy only */ }
  }

  /** Turns it on for today; a new confirmation starts a new DAILY_CAP. {@code cost} is the price of one use. */
  function enable(feature, cost) {
    write(feature, { date: today(), on: true, spent: 0, cost: Number(cost) || 0 });
  }

  function disable(feature) {
    write(feature, { date: today(), on: false, spent: 0, cost: 0 });
  }

  function state(feature) {
    var saved = read(feature);
    return { on: !!(saved && saved.on), spent: saved ? saved.spent : 0, cost: saved ? saved.cost : 0, cap: DAILY_CAP };
  }

  /** True when the next use can be paid without asking. */
  function covers(feature) {
    var current = state(feature);
    return current.on && current.spent + current.cost <= DAILY_CAP;
  }

  /** On today, but the next use would pass the cap: ask again and say why. */
  function capReached(feature) {
    var current = state(feature);
    return current.on && current.spent + current.cost > DAILY_CAP;
  }

  function add(feature, amount) {
    var current = read(feature);
    if (!current || !current.on) return;
    current.spent += Number(amount) || 0;
    write(feature, current);
  }

  return { DAILY_CAP: DAILY_CAP, enable: enable, disable: disable, state: state, covers: covers, capReached: capReached, add: add };
});
