import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { buildKanaRuleBundle, renderDraftKanaRuleSeed, renderPublishedKanaRuleSeed, embedKanaRuleBlock, PUBLISHED_OUTPUT } from './build_kana_rules_draft_seed.mjs';

const source = JSON.parse(fs.readFileSync(new URL('../../data/learning-owned/learning-content.json', import.meta.url), 'utf8')).sections.kana_special_sounds;

test('maps all reviewed-source IDs into ordered task-owned draft lessons without loss', () => {
  const before = structuredClone(source);
  const bundle = buildKanaRuleBundle(source);

  assert.deepEqual(source, before);
  assert.equal(bundle.bookCode, 'LP-OWNED-KANA-SOUNDS');
  assert.equal(bundle.status, 'DRAFT');
  assert.deepEqual(bundle.lessons.map(lesson => lesson.items.length), [8, 10, 8]);
  assert.deepEqual(bundle.lessons.map(lesson => lesson.code), ['KANA-RULE-01', 'KANA-RULE-02', 'KANA-RULE-03']);
  assert.deepEqual(bundle.lessons.flatMap(lesson => lesson.items.map(item => item.code)),
    source.lessons.flatMap(lesson => lesson.examples.map(example => example.id)));
  assert.deepEqual(bundle.lessons[0].items[1], {
    code: 'KANA-RULE-01-02', orderIndex: 2, written: 'きっぷ', romaji: 'kippu', meaningVi: 'vé', focus: 'っ',
  });
});

test('rejects duplicate item IDs, out-of-order IDs, missing meanings, and oversized lessons', () => {
  const duplicate = structuredClone(source);
  duplicate.lessons[0].examples[1].id = duplicate.lessons[0].examples[0].id;
  assert.throws(() => buildKanaRuleBundle(duplicate), /duplicate|sequence/i);

  const missing = structuredClone(source);
  missing.lessons[1].examples[0].meaning_vi = '';
  assert.throws(() => buildKanaRuleBundle(missing), /meaning/i);

  const large = structuredClone(source);
  large.lessons[2].examples.push(...Array.from({ length: 8 }, (_, i) => ({
    ...large.lessons[2].examples[i], id: `KANA-RULE-03-${String(i + 9).padStart(2, '0')}`,
  })));
  assert.throws(() => buildKanaRuleBundle(large), /15/);
});

test('renders an additive DRAFT-only seed with stable source IDs and word-level meanings', () => {
  const sql = renderDraftKanaRuleSeed(buildKanaRuleBundle(source));

  assert.match(sql, /START TRANSACTION/);
  assert.match(sql, /COMMIT/);
  assert.match(sql, /LP-OWNED-KANA-SOUNDS/);
  assert.match(sql, /INSERT INTO content_item/);
  assert.match(sql, /translated_text/);
  assert.match(sql, /INSERT INTO content_source/);
  assert.match(sql, /KANA-RULE-01-02/);
  assert.ok(sql.includes(Buffer.from('Bản nháp ví dụ âm đặc biệt Kana đang chờ duyệt.', 'utf8').toString('hex')));
  assert.ok(!sql.includes(Buffer.from('Học âm ngắt, âm dài và tổ hợp Katakana qua từ ví dụ đã kiểm duyệt.', 'utf8').toString('hex')));
  assert.equal((sql.match(/INSERT INTO content_item/g) ?? []).length, 26);
  assert.equal((sql.match(/INSERT INTO content_source/g) ?? []).length, 26);
  assert.doesNotMatch(sql, /\b(?:DELETE|TRUNCATE|DROP|assessment_question|learning_quiz_session)\b/i);
  assert.ok(sql.split('\n').filter(line => /^INSERT INTO (?:textbook|lesson) \(/.test(line))
    .every(line => line.includes("'DRAFT'") && !line.includes("'PUBLISHED'")));
});

test('same-code rerun fails closed if an existing draft row has different content or a different owner', () => {
  const sql = renderDraftKanaRuleSeed(buildKanaRuleBundle(source));
  assert.match(sql, /CREATE TEMPORARY TABLE.*CHECK \(ok = 1\)/);
  assert.match(sql, /INSERT INTO lp_kana_draft_guard/);
  assert.match(sql, /BINARY ci\.content = BINARY CONVERT\(0x[0-9a-f]+ USING utf8mb4\)/);
  assert.match(sql, /cs\.entity_id = @jlasKanaItemId/);
  assert.match(sql, /t\.status = 'DRAFT'/);
  assert.match(sql, /base\.id < @jlasKanaBookId/);
});

test('OD-33: the published seed publishes the Kana rule book and its lessons, and stays re-runnable after that', () => {
  const sql = renderPublishedKanaRuleSeed(buildKanaRuleBundle(source));
  assert.match(sql, /UPDATE textbook SET status='PUBLISHED' WHERE id=@jlasKanaBookId AND status='DRAFT';/);
  assert.match(sql, /UPDATE lesson SET status='PUBLISHED' WHERE unit_id=@jlasKanaUnitId AND status='DRAFT' AND source_code IN \('KANA-RULE-01','KANA-RULE-02','KANA-RULE-03'\);/);
  // A second run finds the rows already PUBLISHED; its guards must accept that instead of failing.
  assert.doesNotMatch(sql, /t\.status = 'DRAFT'|l\.status = 'DRAFT'/);
  assert.match(sql, /t\.status IN \('DRAFT','PUBLISHED'\)/);
  assert.match(sql, /l\.status IN \('DRAFT','PUBLISHED'\)/);
  // Examples stay marked as not reviewed (content_source CHUA_DUYET); nothing is deleted.
  assert.equal((sql.match(/'CHUA_DUYET'/g) ?? []).length, 52);
  assert.doesNotMatch(sql, /\b(?:DELETE|TRUNCATE|DROP TABLE)\b/i);
  assert.ok(sql.indexOf("UPDATE textbook SET status='PUBLISHED'") < sql.lastIndexOf('COMMIT;'));
});

test('OD-33: the seed embeds the published Kana rule block', () => {
  const seed = fs.readFileSync(new URL('../../sql/seeds/learning_owned.sql', import.meta.url), 'utf8');
  assert.equal(embedKanaRuleBlock(seed, buildKanaRuleBundle(source)), seed);
  assert.ok(seed.includes(`-- BEGIN ${PUBLISHED_OUTPUT} SHA256 `));
  assert.ok(!seed.includes('-- BEGIN outputs/2026-10-10-kana-rules-draft-v3/seed-draft.sql'));
});
