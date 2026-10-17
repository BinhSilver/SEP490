const test = require('node:test');
const assert = require('node:assert/strict');

const api = require('../../main/resources/static/js/pages/stage-complete.js');

function pathFixture(overrides) {
  return Object.assign({
    id: 1,
    status: 'ACTIVE',
    stages: [
      { id: 10, level: 'N4', stageIndex: 0, status: 'COMPLETED' },
      { id: 11, level: 'N3', stageIndex: 1, status: 'ACTIVE' }
    ]
  }, overrides);
}

test('offers the certificate only when the completed stage was the last one and the path is COMPLETED', () => {
  var midPath = pathFixture();
  assert.equal(api.isPathCompletionFor(midPath, 10), false);

  var finishedPath = pathFixture({
    status: 'COMPLETED',
    stages: [
      { id: 10, level: 'N4', stageIndex: 0, status: 'COMPLETED' },
      { id: 11, level: 'N3', stageIndex: 1, status: 'COMPLETED' }
    ]
  });
  assert.equal(api.isPathCompletionFor(finishedPath, 11), true);
  // Completing an earlier stage never offers the certificate, even after the path finishes later.
  assert.equal(api.isPathCompletionFor(finishedPath, 10), false);
});

test('finds the just-completed stage by id, or null if the id is not in the path', () => {
  var path = pathFixture();
  assert.equal(api.findStage(path, 10).level, 'N4');
  assert.equal(api.findStage(path, 999), null);
});

test('renders only the server-owned next-level recommendation', () => {
  const html = api.renderRecommendation({
    available: true,
    recommendedTarget: 'N2',
    reasonCode: 'NEXT_JLPT_LEVEL',
    actionUrl: '/survey'
  });

  assert.match(html, /Tiếp tục với N2/);
  assert.match(html, /href="\/survey"/);
  assert.equal(html.includes('N1'), false);
});

test('renders maintenance after N1 and no fabricated link when recommendation is unavailable', () => {
  const html = api.renderRecommendation({
    available: false,
    recommendedTarget: null,
    reasonCode: 'HIGHEST_LEVEL_REACHED',
    actionUrl: null
  });

  assert.match(html, /duy trì năng lực N1/i);
  assert.equal(html.includes('href='), false);
});
