const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/static/js/pages/premium.js', 'utf8');

async function page(kind, responses) {
  const handlers = {};
  const fields = {
    '[data-qr]': {}, '[data-countdown]': { textContent: '' },
    '[data-coupon]': { value: 'FREE100', disabled: false },
    '[data-error]': { textContent: '' }
  };
  const root = {
    dataset: { page: kind, order: '123' }, innerHTML: '',
    addEventListener(type, handler) { handlers[type] = handler; },
    querySelector(selector) { return fields[selector]; }
  };
  const redirects = [];
  const QRCode = function () {};
  QRCode.CorrectLevel = { M: 1 };
  const location = { pathname: '/premium/checkout/123', search: '',
    assign(url) { redirects.push(url); }, replace(url) { redirects.push(url); } };
  const window = { QRCode };
  vm.runInNewContext(source, {
    document: { getElementById() { return root; } }, window, location, URLSearchParams,
    fetch: async () => ({ ok: true, status: 200, json: async () => ({ data: responses.shift() }) }),
    setInterval() { return 1; }, clearInterval() {}, setTimeout() { return 1; }, clearTimeout() {}
  });
  await new Promise(resolve => setImmediate(resolve));
  return { root, handlers, redirects, fields };
}

test('yearly pricing shows the monthly total only when the yearly plan is cheaper', async () => {
  const plans = { current: null, paymentsEnabled: true,
    aiCall: { freeDailyMinutes: 1, freeMonthlyMinutes: 10, premiumDailyMinutes: 5, premiumMonthlyMinutes: 50 },
    plans: [{ id: 1, price: 10000, durationDays: 30 }, { id: 2, price: 100000, durationDays: 365 }] };
  const view = await page('pricing', [plans]);
  assert.doesNotMatch(view.root.innerHTML, /pm-price__original/);
  view.handlers.click({ target: { closest(selector) { return selector === '[data-period]' ? { dataset: { period: 'year' } } : null; } } });
  assert.match(view.root.innerHTML, /<s class="pm-price__original">120\.000đ<\/s>/);
});

test('credits cards keep the price and purchase action without a repeated description', async () => {
  const view = await page('credits', [{ balance: 50, paymentsEnabled: true,
    packages: [{ id: 3, name: 'Gói 100 CP', description: '100 CP', creditAmount: 100, price: 20000 }] }]);
  assert.match(view.root.innerHTML, /pm-credits-balance/);
  assert.match(view.root.innerHTML, /pm-credits-price">20\.000đ/);
  assert.match(view.root.innerHTML, /data-buy-credit="3"/);
  assert.doesNotMatch(view.root.innerHTML, /pm-credits-card__desc/);
});

test('applying a free coupon redirects to the paid order', async () => {
  const pending = { orderCode: 123, status: 'PENDING', productType: 'CREDIT', planName: '100 CP',
    creditAmount: 100, amount: 20000, originalAmount: 20000, discountAmount: 0,
    expiresAt: new Date(Date.now() + 600000).toISOString(), qrCode: 'qr' };
  const paid = { ...pending, orderCode: 456, status: 'PAID', amount: 0, discountAmount: 20000, couponCode: 'FREE100' };
  const view = await page('checkout', [pending, paid]);
  const apply = { disabled: false, textContent: '' };
  await view.handlers.click({ target: { closest(selector) { return selector === '[data-apply-coupon]' ? apply : null; } } });
  assert.deepEqual(view.redirects, ['/premium/checkout/456']);
  const settled = await page('checkout', [paid]);
  assert.match(settled.root.innerHTML, /Thanh toán thành công/);
});
