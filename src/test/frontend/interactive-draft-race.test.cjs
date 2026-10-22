const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const pages = [
  'interactive-study.js',
  'interactive-foundation-study.js',
  'interactive-kanji-study.js',
  'interactive-grammar-study.js',
  'interactive-comprehension-study.js'
];

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

for (const page of pages) {
  test(`${page} saves the latest choice when it changes during an earlier draft request`, async () => {
    const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pages', page), 'utf8');
    const start = source.indexOf('    function setDraftStatus(');
    const end = source.indexOf('    async function loadStudy()', start);
    assert.ok(start >= 0 && end > start, 'draft functions should be present');
    const draftFunctions = source.slice(start, end);
    let choice = 1;
    const form = {
      dataset: { attemptId: '7' },
      querySelectorAll: () => [{ name: 'q_8', value: String(choice) }]
    };
    const status = { textContent: '', classList: { toggle() {} } };
    const node = { querySelector: (selector) => selector === '.ilp-check__form' ? form : status };
    const requests = [];
    const api = (_, options) => {
      const pending = deferred();
      requests.push({ answers: JSON.parse(options.body).answers, pending });
      return pending.promise;
    };
    const harness = new Function('node', 'api', 'window', 'setTimeout', 'clearTimeout',
      `var draftPath='/micro-check/draft', mode='LEARN', draftTimer=null, draftRetries=0, draftDirty=false, draftInFlight=null, draftSeq=0;\n${draftFunctions}\nreturn { flushDraft, scheduleDraftSave, dirty: () => draftDirty };`);
    const save = harness(node, api, { JlasAuth: { authHeaders: () => ({}) } }, () => 1, () => {});

    save.scheduleDraftSave(form);
    const firstSave = save.flushDraft();
    assert.deepEqual(requests[0].answers, [{ questionId: 8, selectedOption: 1 }]);

    choice = 2;
    save.scheduleDraftSave(form);
    const leaveSave = save.flushDraft();
    requests[0].pending.resolve({});
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(requests.length, 2, 'the new choice must be sent after the first request settles');
    assert.deepEqual(requests[1].answers, [{ questionId: 8, selectedOption: 2 }]);
    requests[1].pending.resolve({});
    assert.equal(await firstSave, true);
    assert.equal(await leaveSave, true);
    assert.equal(save.dirty(), false);
  });
}
