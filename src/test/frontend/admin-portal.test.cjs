const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resources = path.resolve(__dirname, '../../main/resources');
const read = file => fs.readFileSync(path.join(resources, file), 'utf8');

function setup(role, pathname = '/admin/jlpt', status = 200) {
  const elements = {};
  const element = id => elements[id] ||= {
    innerHTML: '', textContent: '', hidden: false, dataset: {}, handlers: {},
    setAttribute(name, value) { this[name] = value; },
    getAttribute(name) { return this[name]; },
    addEventListener(name, handler) { this.handlers[name] = handler; },
    focus() { this.focused = true; }
  };
  element('jlabBaseContent').hidden = true;
  const calls = [];
  const document = {
    getElementById: element,
    addEventListener(name, handler) { this[name] = handler; }
  };
  const window = {
    document,
    location: { pathname, search: '', replace(url) { this.redirect = url; } },
    JlasAuth: {
      getUser: () => ({ role: 'SUPER_ADMIN' }),
      authHeaders: () => ({ Authorization: 'Bearer test' }),
      setSession(...args) { calls.push(['session', ...args]); },
      clearSession() { calls.push(['clear']); },
      async logout(destination) { calls.push(['logout', destination]); }
    }
  };
  const fetch = async url => {
    calls.push(['fetch', url]);
    if (status === 0) throw new Error('offline');
    return { ok: status === 200, status, json: async () => ({ success: true, data: { role, fullName: '<Admin>' } }) };
  };
  const context = vm.createContext({ window, document, fetch });
  vm.runInContext(read('static/js/pages/admin-core.js'), context);
  vm.runInContext(read('static/js/admin-shell.js'), context);
  return { window, elements, calls };
}

test('role navigation follows backend identifiers without granting creator rights to system admin', async () => {
  for (const [role, route, expected, excluded] of [
    ['CONTENT_CREATOR', '/admin/jlpt', '/textbooks/manage', '/admin/system/users'],
    ['MODERATOR', '/admin/moderation/reports', '/admin/moderation/queue', '/textbooks/manage'],
    ['SUPER_ADMIN', '/admin/system/dashboard', '/admin/system/users', '/admin/jlpt']
  ]) {
    const runtime = setup(role, route);
    assert.equal(await runtime.window.AdminShell.ready, true);
    const nav = runtime.elements.adminShellNav.innerHTML;
    assert.ok(nav.includes(expected));
    assert.ok(!nav.includes(excluded));
    assert.ok(nav.includes('aria-current="page"'));
    assert.equal(runtime.elements.jlabBaseContent.hidden, false);
    assert.equal(runtime.window.Admin.role(), role);
  }
});

test('direct editor URLs activate content navigation; mismatched and unknown roles fail closed', async () => {
  const creator = setup('CONTENT_CREATOR', '/textbooks/lessons/12/edit');
  assert.equal(await creator.window.AdminShell.ready, true);
  assert.ok(creator.elements.adminShellNav.innerHTML.includes('aria-current="page"'));
  for (const role of ['MODERATOR', 'SUPER_ADMIN', 'FREE', 'ADMIN']) {
    const runtime = setup(role);
    assert.equal(await runtime.window.AdminShell.ready, false);
    assert.equal(runtime.elements.jlabBaseContent.hidden, true);
    assert.ok(runtime.elements.adminShellStatus.innerHTML.includes('không có quyền'));
  }
});

test('authentication is verified on server, not trusted from cached role; failures keep content hidden', async () => {
  for (const status of [401, 403, 500, 0]) {
    const runtime = setup('CONTENT_CREATOR', '/admin/jlpt', status);
    assert.equal(await runtime.window.AdminShell.ready, false);
    assert.equal(runtime.elements.jlabBaseContent.hidden, true);
    assert.equal(runtime.calls[0][1], '/api/auth/me');
    if (status === 401) assert.match(runtime.window.location.redirect, /^\/login\?redirect=/);
    else assert.ok(runtime.elements.adminShellStatus.innerHTML.length > 0);
  }
});

test('account text is escaped, logout goes to login, and mobile menu supports Escape', async () => {
  const runtime = setup('CONTENT_CREATOR');
  await runtime.window.AdminShell.ready;
  assert.equal(runtime.elements.adminShellName.textContent, '<Admin>');
  await runtime.elements.adminShellLogout.handlers.click();
  assert.deepEqual(runtime.calls.at(-1), ['logout', '/login']);
  runtime.elements.adminShellToggle.handlers.click();
  assert.equal(runtime.elements.adminShellToggle['aria-expanded'], 'true');
  runtime.window.document.keydown({ key: 'Escape' });
  assert.equal(runtime.elements.adminShellToggle['aria-expanded'], 'false');
});

test('shared logout retains student destination and clears the existing session for admin', async () => {
  for (const destination of [undefined, '/login', 'https://untrusted.example']) {
    const storage = new Map([['accessToken', 'token'], ['refreshToken', 'refresh'], ['user', '{}']]);
    const requests = [];
    const document = { cookie: '', addEventListener() {}, querySelectorAll: () => [] };
    const window = {
      document, location: { href: '', pathname: '/admin/jlpt', origin: 'http://localhost' },
      fetch: async (url, options) => { requests.push([url, options]); return { status: 200 }; }
    };
    const localStorage = {
      getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value),
      removeItem: key => storage.delete(key)
    };
    const context = vm.createContext({ window, document, localStorage, fetch: (...args) => window.fetch(...args) });
    vm.runInContext(read('static/js/auth-session.js'), context);
    await window.JlasAuth.logout(destination);
    assert.equal(window.location.href, destination === '/login' ? '/login' : '/');
    assert.equal(storage.size, 0);
    assert.equal(requests[0][0], '/api/auth/logout');
    assert.equal(JSON.parse(requests[0][1].body).refreshToken, 'refresh');
  }
});

test('default login destinations separate all staff roles without changing student or explicit return URLs', () => {
  for (const [role, expected] of [
    ['CONTENT_CREATOR', '/textbooks/manage'], ['SUPER_ADMIN', '/admin'], ['MODERATOR', '/admin'], ['FREE', '/']
  ]) {
    const window = { location: { search: '' } };
    const context = vm.createContext({ window, document: {}, URLSearchParams,
      localStorage: { getItem: () => JSON.stringify({ role }) } });
    vm.runInContext(read('static/js/pages/auth-common.js'), context);
    assert.equal(window.JLabAuth.getSafeRedirect(), expected);
    window.location.search = '?redirect=%2Fadmin%2Fjlpt';
    assert.equal(window.JLabAuth.getSafeRedirect(), '/admin/jlpt');
    window.location.search = '?redirect=%2F%2Fevil.example';
    assert.equal(window.JLabAuth.getSafeRedirect(), expected);
  }
});

test('denied guards never initialize page actions or fetch protected content', async () => {
  for (const file of ['admin-boot.js', 'jlpt-admin.js', 'textbook-manage.js', 'lesson-editor.js']) {
    const window = { AdminShell: { ready: Promise.resolve(false) } };
    const context = vm.createContext({ window,
      document: { getElementById() { assert.fail('Page initialized before authorization'); } },
      fetch() { assert.fail('Protected API called before authorization'); } });
    vm.runInContext(read('static/js/pages/' + file), context);
    await new Promise(resolve => setImmediate(resolve));
  }
});

test('live admin templates never load student shell and defer page scripts until the admin guard resolves', () => {
  for (const file of ['admin/workspace.html', 'admin/jlpt-content.html', 'textbooks/manage.html', 'textbooks/lesson-editor.html']) {
    const template = read('templates/' + file);
    assert.ok(!template.includes('jlab-base-shell.js'), file);
    assert.ok(template.includes('admin-shell.js'), file);
    assert.match(template, /id="jlabBaseContent"[^>]*hidden/);
  }
  for (const file of ['admin-boot.js', 'jlpt-admin.js', 'textbook-manage.js', 'lesson-editor.js']) {
    assert.ok(read('static/js/pages/' + file).includes('AdminShell.ready'), file);
    assert.match(read('static/js/pages/' + file), /then\(function \(allowed\) \{\s*'use strict';\s*if/);
  }
  assert.ok(read('templates/textbooks/index.html').includes('jlab-base-shell.js'));
  const shell = read('static/js/admin-shell.js');
  assert.ok(!/href=["']\/(?:profile|ai-call|learning|games)(?:[\/"'])/.test(shell));
  assert.ok(!/Switch to Student|Back to Student/.test(shell));
});
