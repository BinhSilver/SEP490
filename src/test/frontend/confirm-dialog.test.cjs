'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');

// Minimal DOM stub sufficient for confirm-dialog.js: createElement/appendChild/querySelector(All),
// addEventListener/removeEventListener, getElementById, focus tracking (doc.activeElement) and contains.
// The stub flattens the dialog's innerHTML into direct querySelector children, which is enough to
// exercise the dialog's behaviour (resolve, focus trap, focus return, accessible name) without jsdom.
function makeEl(doc) {
  return {
    className: '', textContent: '', innerHTML: '', attributes: {}, children: {}, handlers: {}, removed: false,
    setAttribute(k, v) { this.attributes[k] = v; },
    focus() { if (doc) doc.activeElement = this; },
    appendChild() {},
    remove() { this.removed = true; },
    addEventListener(type, fn) { (this.handlers[type] = this.handlers[type] || []).push(fn); },
    querySelector(sel) { if (!this.children[sel]) this.children[sel] = makeEl(doc); return this.children[sel]; },
    querySelectorAll(sel) {
      // Document order of the two buttons (cancel before ok in the authored markup).
      if (sel === '.jlas-confirm__ok, .jlas-confirm__cancel') {
        return [this.querySelector('.jlas-confirm__cancel'), this.querySelector('.jlas-confirm__ok')];
      }
      return [];
    },
    contains(other) { return other === this || Object.values(this.children).includes(other); },
  };
}

function makeDoc() {
  const doc = {
    created: [], listeners: {}, activeElement: null,
    addEventListener(type, fn) { (doc.listeners[type] = doc.listeners[type] || []).push(fn); },
    removeEventListener(type) { delete doc.listeners[type]; },
    getElementById() { return null; },
    createElement() { const el = makeEl(doc); doc.created.push(el); return el; },
    head: { appendChild() {} },
    body: { appendChild() {} },
  };
  return doc;
}

function fire(el, type, event) {
  (el.handlers[type] || []).forEach((fn) => fn(event || { key: undefined, preventDefault() {} }));
}

/** Fires a keydown on the document (where the dialog registers Escape/Tab) and reports whether it was prevented. */
function pressKey(doc, key, shiftKey) {
  let prevented = false;
  (doc.listeners.keydown || []).forEach((fn) => fn({ key, shiftKey, preventDefault() { prevented = true; } }));
  return prevented;
}

function load() {
  const modulePath = require.resolve('../../main/resources/static/js/pages/confirm-dialog.js');
  delete require.cache[modulePath]; // fresh factory + fresh doc per test (the factory captures `doc`).
  const doc = makeDoc();
  global.window = { document: doc };
  const api = require(modulePath);
  return { api, doc };
}

function rootOf(doc) {
  // createElement order: style element (ensureStyles) then the dialog root (build).
  return doc.created[doc.created.length - 1];
}

const okOf = (doc) => rootOf(doc).querySelector('.jlas-confirm__ok');
const cancelOf = (doc) => rootOf(doc).querySelector('.jlas-confirm__cancel');

test('confirm() returns a Promise resolving true on OK click', async () => {
  const { api, doc } = load();
  const pending = api.confirm('Xoá mục này?');
  fire(okOf(doc), 'click');
  assert.equal(await pending, true);
});

test('confirm() resolves false on cancel, backdrop and Escape', async () => {
  const { api, doc } = load();

  const p1 = api.confirm('Rút về nháp?');
  fire(cancelOf(doc), 'click');
  assert.equal(await p1, false);

  const p2 = api.confirm('Bỏ trống?');
  fire(rootOf(doc).querySelector('.jlas-confirm__backdrop'), 'click');
  assert.equal(await p2, false);

  const p3 = api.confirm('Chắc chứ?');
  pressKey(doc, 'Escape');
  assert.equal(await p3, false);
});

test('opening a second dialog closes the first as cancelled', async () => {
  const { api, doc } = load();
  const p1 = api.confirm('thứ nhất');
  const p2 = api.confirm('thứ hai'); // supersedes p1 immediately
  assert.equal(await p1, false);
  fire(okOf(doc), 'click');
  assert.equal(await p2, true);
});

test('dialog moves focus to the primary action on open and traps Tab/Shift+Tab inside', async () => {
  const { api, doc } = load();
  const pending = api.confirm('Chắc chứ?');
  assert.equal(doc.activeElement, okOf(doc), 'open focuses the OK action');

  // Tab from the last focusable (OK) wraps to the first (Cancel) and prevents the default.
  assert.equal(pressKey(doc, 'Tab', false), true, 'Tab from last is trapped');
  assert.equal(doc.activeElement, cancelOf(doc));

  // Shift+Tab from the first focusable (Cancel) wraps back to the last (OK).
  assert.equal(pressKey(doc, 'Tab', true), true, 'Shift+Tab from first is trapped');
  assert.equal(doc.activeElement, okOf(doc));

  fire(okOf(doc), 'click');
  assert.equal(await pending, true);
});

test('dialog returns focus to the previously focused element on close', async () => {
  const { api, doc } = load();
  const trigger = makeEl(doc);
  doc.activeElement = trigger;
  const pending = api.confirm('Xác nhận?');
  assert.equal(doc.activeElement, okOf(doc), 'focus moved into the dialog');
  fire(cancelOf(doc), 'click');
  assert.equal(await pending, false);
  assert.equal(doc.activeElement, trigger, 'focus returned to the trigger after close');
});

test('dialog exposes an accessible name via aria-label and aria-labelledby', () => {
  const { api, doc } = load();
  const pending = api.confirm('Bạn có muốn thoát?', { label: 'Thoát bài học' });
  const root = rootOf(doc);
  assert.equal(root.attributes['role'], 'dialog');
  assert.equal(root.attributes['aria-modal'], 'true');
  assert.equal(root.attributes['aria-label'], 'Thoát bài học');
  assert.equal(root.attributes['aria-labelledby'], 'jlas-confirm-message');
  fire(okOf(doc), 'click');
  return pending;
});
