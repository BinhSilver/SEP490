const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pages/smart-test.js'), 'utf8');

test('review start levels above the target are disabled, not only hidden (Safari ignores hidden <option>)', () => {
  const picker = source.slice(source.indexOf("document.getElementById('reviewStartLevel')"));
  assert.match(picker, /option\.disabled\s*=/, 'options above the target must be unselectable in every browser');
  assert.match(picker, /select\.value\s*=\s*'N5'/, 'N5 stays the safe default start');
});

test('a result below the self-reported level asks to accept or decline before building the path', () => {
  const template = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/smart-test.html'), 'utf8');
  assert.match(source, /selfReportedLevel/, 'the result page compares against the self-reported level');
  assert.match(source, /discrepancyChoice=/, 'the chosen answer travels to the path page');
  assert.match(template, /name="discrepancyChoice" value="ACCEPT_RECOMMENDATION"/);
  assert.match(template, /name="discrepancyChoice" value="DECLINE_RECOMMENDATION"/);
});
