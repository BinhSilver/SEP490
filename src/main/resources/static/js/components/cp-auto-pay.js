/*
 * "Không hỏi lại hôm nay": sau khi người học bật, các lần dùng tốn CP của chức năng đó tự trả, không hiện popup.
 * Mỗi chức năng (aiCall, pronunciation, quickLookup, shadowingCreate) nhớ riêng theo tài khoản, sang ngày mới thì hỏi
 * lại, và tự dùng tối đa DAILY_CAP CP rồi cũng hỏi lại. Lựa chọn được lưu trên máy chủ (/api/credits/auto-pay) nên
 * đăng nhập máy khác vẫn nhớ; trình duyệt giữ một bản sao để trang hỏi được ngay, không phải chờ mạng. Máy chủ vẫn tự
 * kiểm tra số dư mỗi lần trừ.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) {
    root.JlasCpAutoPay = api;
    api.sync();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var DAILY_CAP = 100;
  var API = '/api/credits/auto-pay';
  // Used when the browser blocks localStorage (private window): the choice then lasts until the page reloads.
  var memory = {};

  function today() {
    var now = new Date();
    return now.getFullYear() + '-' + (now.getMonth() + 1) + '-' + now.getDate();
  }

  function auth() {
    return typeof window !== 'undefined' ? window.JlasAuth : null;
  }

  function key(feature) {
    var session = auth();
    var user = session && typeof session.getUser === 'function' ? session.getUser() : null;
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

  /** Sends the request only for a signed-in browser page; tests in Node and guests keep the local copy. */
  function request(method, url, body) {
    var session = auth();
    if (typeof window === 'undefined' || typeof window.fetch !== 'function' || !session
        || typeof session.authHeaders !== 'function' || typeof session.getAccessToken !== 'function'
        || !session.getAccessToken()) {
      return null;
    }
    return window.fetch(url, { method: method, headers: session.authHeaders(), body: body ? JSON.stringify(body) : undefined })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (envelope) {
        if (envelope && envelope.data) apply(envelope.data);
        return envelope ? envelope.data : null;
      })
      .catch(function () { return null; });
  }

  /** Replaces the local copy with the account's choices from the server. */
  function apply(states) {
    Object.keys(states).forEach(function (feature) {
      var s = states[feature] || {};
      write(feature, { date: today(), on: !!s.on, spent: Number(s.spent) || 0, cost: Number(s.cost) || 0 });
    });
  }

  /** Loads today's choices of the signed-in account; runs once when the script loads. */
  function sync() {
    return request('GET', API) || Promise.resolve(null);
  }

  /** Turns it on for today; a new confirmation starts a new DAILY_CAP. {@code cost} is the price of one use. */
  function enable(feature, cost) {
    write(feature, { date: today(), on: true, spent: 0, cost: Number(cost) || 0 });
    request('PUT', API + '/' + encodeURIComponent(feature), { on: true, cost: Number(cost) || 0 });
  }

  function disable(feature) {
    write(feature, { date: today(), on: false, spent: 0, cost: 0 });
    request('PUT', API + '/' + encodeURIComponent(feature), { on: false, cost: 0 });
  }

  function state(feature) {
    var saved = read(feature);
    return { on: !!(saved && saved.on), spent: saved ? saved.spent : 0, cost: saved ? saved.cost : 0, cap: DAILY_CAP };
  }

  /** The next use's price: {@code cost} when the page knows it (a Shadowing video priced by its minutes), else the saved one. */
  function nextCost(current, cost) {
    return cost != null && !isNaN(Number(cost)) ? Number(cost) : current.cost;
  }

  /** True when the next use can be paid without asking. */
  function covers(feature, cost) {
    var current = state(feature);
    return current.on && current.spent + nextCost(current, cost) <= DAILY_CAP;
  }

  /** On today, but the next use would pass the cap: ask again and say why. */
  function capReached(feature, cost) {
    var current = state(feature);
    return current.on && current.spent + nextCost(current, cost) > DAILY_CAP;
  }

  /** Counts a payment locally; the server counts it from the CP ledger on its own. */
  function add(feature, amount) {
    var current = read(feature);
    if (!current || !current.on) return;
    current.spent += Number(amount) || 0;
    write(feature, current);
  }

  return {
    DAILY_CAP: DAILY_CAP, enable: enable, disable: disable, state: state, covers: covers, capReached: capReached,
    add: add, sync: sync, apply: apply
  };
});
