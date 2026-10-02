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

test('describes survey-only next action separately from assessment', () => {
  assert.equal(survey.nextAction(definition, { assessmentRequirement: 'NONE' }, true), '/learning/path/v2');
  assert.equal(survey.nextAction({ ...definition, goalCode: 'BA', requiresAssessment: true },
    { assessmentRequirement: 'BRSE_FOUNDATION' }, false), '/smart-test');
  assert.equal(survey.nextAction({ ...definition, goalCode: 'BA', requiresAssessment: true },
    { assessmentRequirement: 'BRSE_FOUNDATION' }, true), '/learning/path/v2');
});

test('builds goal path request from immutable submission and server-side attempt reference', () => {
  assert.deepEqual(survey.buildPathRequest({ submissionId: 51 }, '?assessmentAttemptId=9'),
    { submissionId: 51, assessmentAttemptId: 9 });
  assert.deepEqual(survey.buildPathRequest({ submissionId: 50 }, ''),
    { submissionId: 50, assessmentAttemptId: null });
});
