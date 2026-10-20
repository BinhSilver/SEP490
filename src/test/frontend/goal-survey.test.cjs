const test = require('node:test');
const assert = require('node:assert/strict');
const survey = require('../../main/resources/static/js/pages/goal-survey.js');

const definition = {
  goalCode: 'TRAVEL', displayNameVi: 'Du lịch', entryPolicy: 'SURVEY_ONLY', requiresAssessment: false,
  questions: [{ questionCode: 'SV-TRAVEL-TOPICS', order: 1, textVi: 'Bạn muốn học gì?',
    selectionType: 'MULTIPLE', required: true,
    options: [{ optionCode: 'AIRPORT', order: 1, labelVi: 'Sân bay <nhanh>', valueCode: 'TRAVEL-AIRPORT' }] }]
};

test('renders approved server definition without injecting option HTML', () => {
  const html = survey.renderDefinition(definition);
  assert.match(html, /Du lịch/);
  assert.match(html, /type="checkbox"/);
  assert.match(html, /Sân bay &lt;nhanh&gt;/);
  assert.doesNotMatch(html, /Sân bay <nhanh>/);
});

test('builds structured answers and never sends competency evidence', () => {
  const request = survey.buildSubmission('BALANCED', [
    { questionCode: 'SV-TRAVEL-TOPICS', optionCode: 'AIRPORT', checked: true },
    { questionCode: 'SV-TRAVEL-TOPICS', optionCode: 'HOTEL', checked: false }
  ]);
  assert.deepEqual(request, { learningMode: 'BALANCED', answers: [
    { questionCode: 'SV-TRAVEL-TOPICS', optionCodes: ['AIRPORT'] }
  ] });
  assert.equal('competencyEvidence' in request, false);
});

test('survey completion always proceeds to the goal path, never to Smart Test', () => {
  assert.equal(survey.nextAction(definition, { assessmentRequirement: 'NONE' }, true), '/learning/path/v2');
  assert.equal(survey.nextAction({ ...definition, goalCode: 'BA', requiresAssessment: false },
    { assessmentRequirement: 'NONE' }, false), '/learning/path/v2');
});

test('builds goal path request from immutable submission without an assessment attempt', () => {
  assert.deepEqual(survey.buildPathRequest({ submissionId: 51 }, '?assessmentAttemptId=9'),
    { submissionId: 51, assessmentAttemptId: null });
  assert.deepEqual(survey.buildPathRequest({ submissionId: 50 }, ''),
    { submissionId: 50, assessmentAttemptId: null });
});

test('renders a safe retry state instead of leaving an unavailable survey stuck in loading', () => {
  const html = survey.renderUnavailable('Mục tiêu học chưa sẵn sàng. <script>alert(1)</script>');
  assert.match(html, /role="status"/);
  assert.match(html, /Khảo sát hiện chưa thể tải/);
  assert.match(html, /Thử tải lại khảo sát/);
  assert.match(html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('goal survey options have no icon column, so their label is not squeezed into the icon slot', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const css = fs.readFileSync(path.join(__dirname, '../../main/resources/static/css/pages/smart-test.css'), 'utf8');
  const html = survey.renderDefinition({ displayNameVi: 'Du lịch', questions: [{ questionCode: 'Q', selectionType: 'MULTIPLE',
    textVi: 'Chủ đề?', required: true, options: [{ optionCode: 'A', labelVi: 'Sân bay & xuất nhập cảnh' }] }] });
  assert.doesNotMatch(html, /survey-option__icon/, 'goal survey options render no icon');
  assert.match(css, /\.survey-option\.goal-survey-option\s*\{[^}]*grid-template-columns:\s*1fr auto/);
});
