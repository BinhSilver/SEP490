"""Bổ sung chỗ giáo trình bỏ trống: cách đọc / nghĩa của từ (観光, 気持ち...) và nghĩa / cách đọc của chữ Kanji (寺...).

Lưu ở data/flashcard_textbook/word_fill.json và kanji_fill.json; build_seed.py chỉ dùng khi sách để trống.
Cách đọc chỉ được nhận khi là kana, nghĩa khi không rỗng, ngắn và không phải nhãn bài tập.
Hai cách điền: chạy file này (gọi OpenRouter), hoặc giao cho Antigravity qua exchange.py (không tốn API).

Chạy: python tools/flashcard_textbook/fill_words.py   (chỉ hỏi những mục chưa có trong file)
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from entries import (JUNK_MEANING, KANJI_READING, MISSING, READING, VOCAB_PARTS, clean, entry, han_viet_list,
                     kanji_entry, kanji_reading, tidy)
from han_viet import ROOT, SOURCES, chat_items, load, openrouter_settings, read_json, write_json

FILL = ROOT / "data/flashcard_textbook/word_fill.json"
KANJI_FILL = ROOT / "data/flashcard_textbook/kanji_fill.json"
BATCH = 40

SYSTEM = """Bạn là từ điển Nhật–Việt cho người học JLPT. Với mỗi từ, ghi cách đọc bằng hiragana (giữ katakana nếu
từ viết katakana, giữ phần （する）/（な） nếu từ có) và nghĩa tiếng Việt ngắn gọn (dưới 60 ký tự) đúng nghĩa thường gặp.
Nếu mục đã có "reading" hoặc "meaning" đúng thì giữ nguyên.
Trả ĐÚNG JSON thuần, không markdown: {"items":[{"id":0,"reading":"かんこう","meaning":"tham quan, du lịch"}]}"""


def missing_words() -> list[dict]:
    words: dict[str, dict] = {}
    for source in SOURCES:
        for row in load(source, "tu_vung"):
            if row["phan"] in VOCAB_PARTS:
                item, reason = entry(row)
                if reason in MISSING:
                    words.setdefault(item["word"], item)
    return list(words.values())


def missing_kanji() -> list[dict]:
    chars: dict[str, dict] = {}
    for source in SOURCES:
        for row in load(source, "kanji"):
            item, reason = kanji_entry(row)
            if reason in (*MISSING, "thiếu âm Hán Việt"):
                chars.setdefault(item["kanji"], item)
    return list(chars.values())


def meaning_ok(meaning: str) -> bool:
    return 0 < len(meaning) <= 120 and not JUNK_MEANING.fullmatch(meaning)


def accept(item: dict, answer: dict) -> dict | None:
    """Câu trả lời cho một từ: chỉ điền chỗ trống, cách đọc phải là kana."""
    reading = item["reading"] if READING.fullmatch(item["reading"]) else tidy(answer.get("reading"))
    meaning = item["meaning"] or clean(answer.get("meaning"))
    if not READING.fullmatch(reading) or not meaning_ok(meaning):
        return None
    return {"reading": reading, "meaning": meaning}


def accept_kanji(item: dict, answer: dict, options: list[str]) -> dict | None:
    """Câu trả lời cho một chữ Kanji: âm Hán Việt trong ứng viên của chữ, cách đọc On/Kun bằng kana (cách nhau
    bởi 、), nghĩa tiếng Việt ngắn. Chỉ điền chỗ giáo trình để trống."""
    han_viet = item["han_viet"] or han_viet_list(answer.get("han_viet"))
    reading = item["reading"] or kanji_reading(answer.get("reading"))
    meaning = item["meaning"] or clean(answer.get("meaning"))
    if not han_viet or (options and any(hv not in options for hv in han_viet.split(", "))):
        return None
    if not KANJI_READING.fullmatch(reading) or not meaning_ok(meaning):
        return None
    return {"han_viet": han_viet, "reading": reading, "meaning": meaning}


def ask(batch: list[dict], settings) -> dict[str, dict]:
    items = [dict(item, id=i) for i, item in enumerate(batch)]
    result = {}
    for answer in chat_items(SYSTEM, items, settings):
        index = answer.get("id")
        if isinstance(index, int) and 0 <= index < len(batch):
            filled = accept(batch[index], answer)
            if filled:
                result[batch[index]["word"]] = filled
    return result


def fill(workers: int = 6) -> dict[str, dict]:
    words = missing_words()
    known = read_json(FILL)
    done = {w["word"]: known[w["word"]] for w in words if w["word"] in known}
    pending = [w for w in words if w["word"] not in done]
    print(f"{len(done)} từ đã bổ sung, cần hỏi {len(pending)} từ.")
    if pending:
        settings = openrouter_settings()
        batches = [pending[i:i + BATCH] for i in range(0, len(pending), BATCH)]
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for answers in pool.map(lambda b: ask(b, settings), batches):
                done.update(answers)
    write_json(FILL, done)
    print(f"Đã bổ sung {len(done)}/{len(words)} từ. Chữ Kanji thiếu nghĩa/cách đọc: {len(missing_kanji())} "
          "(điền qua exchange.py).")
    return done


if __name__ == "__main__":
    fill()
