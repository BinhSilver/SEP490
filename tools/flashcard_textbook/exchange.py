"""Giao phần cần AI cho Antigravity (không tốn API) rồi nhận lại kết quả, có kiểm tra.

  python tools/flashcard_textbook/exchange.py export <todo.json>
      Ghi danh sách việc: từ thiếu cách đọc/nghĩa, chữ Kanji thiếu âm Hán Việt/cách đọc/nghĩa, từ có Kanji chưa có
      âm Hán Việt (kèm âm ứng viên của từng chữ). Mỗi mục có ô trống cần điền.
  python tools/flashcard_textbook/exchange.py import <answers.json>
      Đọc file cùng cấu trúc đã điền, kiểm tra bằng đúng luật của han_viet.py / fill_words.py, ghi phần hợp lệ vào
      data/flashcard_textbook/*.json và in các mục bị từ chối.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from fill_words import FILL, KANJI_FILL, accept, accept_kanji, missing_kanji, missing_words
from han_viet import accept as accept_han_viet
from han_viet import kanji_candidates, kanji_slots, read_cache, read_json, textbook_words, write_cache, write_json

GUIDE = {
    "words": "Điền 'reading' (hiragana; katakana nếu từ viết katakana; giữ （する）/（な）) và 'meaning' (tiếng Việt, "
             "dưới 60 ký tự, nghĩa thường gặp trong giáo trình JLPT) chỉ ở ô đang trống.",
    "kanji": "Điền ô trống: 'han_viet' (âm Hán Việt VIẾT HOA, chọn trong 'options' nếu có, nhiều âm cách nhau ', '), "
             "'reading' (On bằng katakana, Kun bằng hiragana, cách nhau '、'), 'meaning' (nghĩa tiếng Việt ngắn).",
    "han_viet": "Điền 'hv': âm Hán Việt cả từ, VIẾT HOA, mỗi chữ Kanji một âm chọn trong 'options' của chữ đó, đúng "
                "nghĩa của từ (銀行 NGÂN HÀNG, 旅行 LỮ HÀNH); số âm bằng số chữ Kanji, cách nhau một dấu cách.",
}


def todo() -> dict:
    table, cache = kanji_candidates(), read_cache()
    filled_words, filled_kanji = read_json(FILL), read_json(KANJI_FILL)
    words = [w for w in missing_words() if w["word"] not in filled_words]
    chars = [dict(k, options=table.get(k["kanji"], [])) for k in missing_kanji() if k["kanji"] not in filled_kanji]
    pending_hv = [dict(w, hv="", kanji=[{"char": c, "options": table.get(c, [])} for c in kanji_slots(w["word"])])
                  for w in textbook_words() if w["word"] not in cache]
    return {"guide": GUIDE, "words": words, "kanji": chars, "han_viet": pending_hv}


def export(path: Path) -> None:
    data = todo()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"Đã ghi {path}: {len(data['words'])} từ, {len(data['kanji'])} chữ Kanji, {len(data['han_viet'])} từ cần Hán Việt.")


def merge(path: Path) -> dict:
    answers = json.loads(path.read_text(encoding="utf-8"))
    table = kanji_candidates()
    words = {w["word"]: w for w in missing_words()}
    chars = {k["kanji"]: k for k in missing_kanji()}
    filled_words, filled_kanji, cache = read_json(FILL), read_json(KANJI_FILL), read_cache()
    rejected: list[str] = []
    counts = {"words": 0, "kanji": 0, "han_viet": 0}
    for answer in answers.get("words", []):
        item = words.get(answer.get("word"))
        result = accept(item, answer) if item else None
        if result:
            filled_words[item["word"]] = result
            counts["words"] += 1
        else:
            rejected.append(f"từ {answer.get('word')}: {answer.get('reading')} / {answer.get('meaning')}")
    for answer in answers.get("kanji", []):
        item = chars.get(answer.get("kanji"))
        result = accept_kanji(item, answer, table.get(item["kanji"], [])) if item else None
        if result:
            filled_kanji[item["kanji"]] = result
            counts["kanji"] += 1
        else:
            rejected.append(f"Kanji {answer.get('kanji')}: {answer.get('han_viet')} / {answer.get('reading')} / {answer.get('meaning')}")
    for answer in answers.get("han_viet", []):
        word = answer.get("word") or ""
        hv = accept_han_viet(word, answer.get("hv", ""), table) if kanji_slots(word) else None
        if hv:
            cache[word] = hv
            counts["han_viet"] += 1
        else:
            rejected.append(f"Hán Việt {word}: {answer.get('hv')}")
    write_json(FILL, filled_words)
    write_json(KANJI_FILL, filled_kanji)
    write_cache(cache)
    print(f"Nhận {counts['words']} từ, {counts['kanji']} chữ Kanji, {counts['han_viet']} âm Hán Việt; từ chối {len(rejected)}.")
    for line in rejected[:40]:
        print("  - " + line)
    return {"accepted": counts, "rejected": rejected}


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("export", "import"):
        sys.exit(__doc__)
    (export if sys.argv[1] == "export" else merge)(Path(sys.argv[2]))
