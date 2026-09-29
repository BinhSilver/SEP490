const test = require('node:test');
const assert = require('node:assert/strict');

const api = require('../../main/resources/static/js/pages/lesson-quiz.js');

test('quizUrl and submitUrl build the stage-final endpoints from stageId', () => {
  assert.equal(api.quizUrl({ mode: 'stage-final', stageId: 42 }),
    '/api/learning/stages/42/final-quiz');
  assert.equal(api.submitUrl({ mode: 'stage-final', stageId: 42 }),
    '/api/learning/stages/42/final-quiz/submit');
});

test('quizUrl and submitUrl are unchanged for the existing modes', () => {
  assert.equal(api.quizUrl({ mode: 'gate' }), '/api/learning/paths/gate-quiz');
  assert.equal(api.submitUrl({ mode: 'gate' }), '/api/learning/paths/gate-quiz/submit');
  assert.equal(api.quizUrl({ mode: 'grammar', grammarPointId: 7 }),
    '/api/learning/grammar/7/quiz');
  assert.equal(api.quizUrl({ mode: 'lesson', lessonId: 9 }),
    '/api/learning/lessons/9/quiz');
});
