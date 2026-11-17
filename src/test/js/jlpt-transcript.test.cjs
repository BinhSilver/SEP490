const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function functionSource(source, name) {
  const start = source.indexOf('function ' + name + '(');
  assert.ok(start >= 0, name);
  let depth = 0;
  for (let i = source.indexOf('{', start); i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error('Unclosed function: ' + name);
}

for (const page of ['jlpt-exam', 'jlpt-practice']) {
  const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/pages/' + page + '.js'), 'utf8');
  const context = vm.createContext({ translationOpen: true, esc: value => String(value), safeHtml: value => String(value) });
  vm.runInContext(['transcriptWithoutSpeaker', 'transcriptTextHtml', 'transcriptTurns', 'transcriptBilingualHtml'].map(name => functionSource(source, name)).join('\n'), context);

  test(page + ': spaced and compact numbered speakers keep each turn', () => {
    const turns = context.transcriptTurns('Người dẫn: Câu dẫn\nNữ 2: Câu một\nNam2: Câu hai\n女 2: Câu ba\n男2: Câu bốn');
    assert.deepEqual(Array.from(turns), ['Người dẫn: Câu dẫn', 'Nữ 2: Câu một', 'Nam2: Câu hai', '女 2: Câu ba', '男2: Câu bốn']);
    assert.equal(context.transcriptTextHtml('Nữ 2: Câu một'), 'Câu một');
    assert.equal(context.transcriptTextHtml('男2: 二番目'), '二番目');
  });

  test(page + ': each translation stays under its Japanese turn', () => {
    const ja = 'narrator: 導入\nwoman2: 発言一\nman2: 発言二\nwoman2: 発言三\nman2: 発言四\nnarrator: 質問';
    const vi = 'Người dẫn: Dẫn nhập\nNữ 2: Lượt một\nNam 2: Lượt hai\nNữ 2: Lượt ba\nNam 2: Lượt bốn\nNgười dẫn: Câu hỏi';
    const output = context.transcriptBilingualHtml(ja, vi, true);
    const pairs = output.split('<span class="' + page + '-transcript-line">').slice(1);
    assert.ok(!/Người dẫn:|Nữ 2:|Nam 2:|speaker/.test(output));
    assert.equal(pairs.length, 6);
    ['Dẫn nhập', 'Lượt một', 'Lượt hai', 'Lượt ba', 'Lượt bốn', 'Câu hỏi'].forEach((line, i) => {
      assert.ok(pairs[i].includes(line));
      assert.equal((pairs[i].match(/<small /g) || []).length, 1);
    });
  });
}
