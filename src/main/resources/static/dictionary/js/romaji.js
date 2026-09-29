/**
 * Chuyển Romaji (Hepburn, kiểu gõ IME) sang Hiragana. Bảng tra cố định, hữu hạn — không phải
 * hệ thống dịch âm tự do; dùng khi người dùng bấm Enter với một chuỗi Romaji thuần trong ô search
 * (xem dictionary-page.js) để hiển thị lại đúng dạng Hiragana, giống Mazii/IME tiếng Nhật.
 */
(function (global) {
  'use strict';

  var TABLE = {
    a: 'あ', i: 'い', u: 'う', e: 'え', o: 'お',
    ka: 'か', ki: 'き', ku: 'く', ke: 'け', ko: 'こ',
    sa: 'さ', shi: 'し', su: 'す', se: 'せ', so: 'そ',
    ta: 'た', chi: 'ち', tsu: 'つ', te: 'て', to: 'と',
    na: 'な', ni: 'に', nu: 'ぬ', ne: 'ね', no: 'の',
    ha: 'は', hi: 'ひ', fu: 'ふ', he: 'へ', ho: 'ほ',
    ma: 'ま', mi: 'み', mu: 'む', me: 'め', mo: 'も',
    ya: 'や', yu: 'ゆ', yo: 'よ',
    ra: 'ら', ri: 'り', ru: 'る', re: 'れ', ro: 'ろ',
    wa: 'わ', wo: 'を', n: 'ん',
    ga: 'が', gi: 'ぎ', gu: 'ぐ', ge: 'げ', go: 'ご',
    za: 'ざ', ji: 'じ', zu: 'ず', ze: 'ぜ', zo: 'ぞ',
    da: 'だ', di: 'ぢ', du: 'づ', de: 'で', do: 'ど',
    ba: 'ば', bi: 'び', bu: 'ぶ', be: 'べ', bo: 'ぼ',
    pa: 'ぱ', pi: 'ぴ', pu: 'ぷ', pe: 'ぺ', po: 'ぽ',
    vu: 'ゔ',
    kya: 'きゃ', kyu: 'きゅ', kyo: 'きょ',
    sha: 'しゃ', shu: 'しゅ', sho: 'しょ',
    cha: 'ちゃ', chu: 'ちゅ', cho: 'ちょ',
    nya: 'にゃ', nyu: 'にゅ', nyo: 'にょ',
    hya: 'ひゃ', hyu: 'ひゅ', hyo: 'ひょ',
    mya: 'みゃ', myu: 'みゅ', myo: 'みょ',
    rya: 'りゃ', ryu: 'りゅ', ryo: 'りょ',
    gya: 'ぎゃ', gyu: 'ぎゅ', gyo: 'ぎょ',
    ja: 'じゃ', ju: 'じゅ', jo: 'じょ',
    bya: 'びゃ', byu: 'びゅ', byo: 'びょ',
    pya: 'ぴゃ', pyu: 'ぴゅ', pyo: 'ぴょ'
  };

  var CONSONANTS = 'kstpghzdbfjmyrw';

  /** Chuỗi t+ch dùng để nhân đôi âm "ch" (ví dụ matcha -> まっちゃ), Hepburn quy ước riêng. */
  function isDoubledChDigraph(text, index) {
    return text[index] === 't' && text[index + 1] === 'c' && text[index + 2] === 'h';
  }

  function toHiragana(value) {
    var text = String(value || '').toLowerCase();
    var out = '';
    var i = 0;
    while (i < text.length) {
      if (isDoubledChDigraph(text, i)) { out += 'っ'; i += 1; continue; }
      if (text[i] === "'" || text[i] === '-') { i += 1; continue; }
      if (text[i] === text[i + 1] && text[i] !== 'n' && CONSONANTS.indexOf(text[i]) !== -1) {
        out += 'っ';
        i += 1;
        continue;
      }
      var matched = false;
      for (var len = 3; len >= 1; len -= 1) {
        var chunk = text.substr(i, len);
        if (Object.prototype.hasOwnProperty.call(TABLE, chunk)) {
          out += TABLE[chunk];
          i += chunk.length;
          matched = true;
          break;
        }
      }
      if (matched) continue;
      // Ký tự không nhận diện được (số, khoảng trắng, đã là kana/kanji...) -> giữ nguyên.
      out += text[i];
      i += 1;
    }
    return out;
  }

  global.DictRomaji = { toHiragana: toHiragana };
})(window);
