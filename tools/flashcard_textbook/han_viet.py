"""Âm Hán Việt cho từ vựng giáo trình (giáo trình chỉ ghi Hán Việt cho từng chữ Kanji, không cho từ).

Mỗi chữ có danh sách âm ứng viên: âm giáo trình ghi (data/vnjpclub*/kanji.json.gz, xếp theo số lần dùng)
rồi âm KANJIDIC2 (data/seed_jlas/kanji_data.json). Chữ nhiều âm (行 HÀNH/HÀNG, 楽 LẠC/NHẠC) cần chọn theo nghĩa
của từ, nên `resolve` hỏi AI chọn trong các ứng viên và chỉ nhận câu trả lời khớp ứng viên từng chữ.
Kết quả lưu ở data/flashcard_textbook/han_viet_words.json để build SQL tất định, không cần gọi lại AI.

Chạy: python tools/flashcard_textbook/han_viet.py   (chỉ hỏi những từ chưa có trong cache)
"""
from __future__ import annotations

import collections
import gzip
import json
import os
import re
import sys
import time
import unicodedata
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from entries import KANJI, MISSING, SYLLABLE, VOCAB_PARTS, entry

ROOT = Path(__file__).resolve().parents[2]
SOURCES = ("vnjpclub", "vnjpclub_minna")
CACHE = ROOT / "data/flashcard_textbook/han_viet_words.json"
REPEAT = "々"
BATCH = 60


def norm(text: str) -> str:
    return unicodedata.normalize("NFC", text).strip().upper()


def load(source: str, name: str):
    with gzip.open(ROOT / f"data/{source}/{name}.json.gz", "rt", encoding="utf-8") as f:
        return json.load(f)


def kanji_candidates() -> dict[str, list[str]]:
    """Âm ứng viên của từng chữ: âm giáo trình (hay dùng trước) rồi âm KANJIDIC2, không trùng."""
    textbook: dict[str, collections.Counter] = collections.defaultdict(collections.Counter)
    for source in SOURCES:
        for row in load(source, "kanji"):
            char, value = (row.get("kanji") or "").strip(), row.get("han_viet") or ""
            if len(char) != 1:
                continue
            for part in re.split(r"[,/;、]", value):
                if SYLLABLE.fullmatch(norm(part)):
                    textbook[char][norm(part)] += 1
    table: dict[str, list[str]] = {char: [hv for hv, _ in counts.most_common()] for char, counts in textbook.items()}
    kanjidic = json.loads((ROOT / "data/seed_jlas/kanji_data.json").read_text(encoding="utf-8"))["kanji"]
    for row in kanjidic:
        options = table.setdefault(row["character"], [])
        for part in (row.get("han_viet") or "").split(","):
            if SYLLABLE.fullmatch(norm(part)) and norm(part) not in options:
                options.append(norm(part))
    return {char: options for char, options in table.items() if options}


def kanji_slots(word: str) -> list[str]:
    """Các chữ Kanji của từ theo thứ tự; 々 lặp lại chữ đứng trước (人々 → 人, 人)."""
    slots: list[str] = []
    for char in word:
        if KANJI.match(char):
            slots.append(char)
        elif char == REPEAT and slots:
            slots.append(slots[-1])
    return slots


def compose(word: str, table: dict[str, list[str]]) -> str | None:
    """Ghép âm đầu tiên của mỗi chữ; None khi có chữ không có âm nào."""
    slots = kanji_slots(word)
    if not slots or any(char not in table for char in slots):
        return None
    return " ".join(table[char][0] for char in slots)


def accept(word: str, answer: str, table: dict[str, list[str]]) -> str | None:
    """Câu trả lời của AI chỉ được nhận khi đủ âm cho từng chữ và mỗi âm nằm trong ứng viên của chữ đó."""
    slots = kanji_slots(word)
    syllables = norm(answer or "").replace(",", " ").split()
    if len(syllables) != len(slots):
        return None
    for char, syllable in zip(slots, syllables):
        if not SYLLABLE.fullmatch(syllable):
            return None
        if char in table and syllable not in table[char]:
            return None
    return " ".join(syllables)


def textbook_words() -> list[dict]:
    """Mỗi từ có Kanji của phần Từ vựng một lần (đã làm sạch), kèm cách đọc và nghĩa đầu tiên gặp để AI hiểu từ."""
    seen: dict[str, dict] = {}
    for source in SOURCES:
        for row in load(source, "tu_vung"):
            if row["phan"] not in VOCAB_PARTS:
                continue
            item, reason = entry(row)
            if kanji_slots(item["word"]) and reason in (None, *MISSING):
                known = seen.setdefault(item["word"], item)
                known["reading"] = known["reading"] or item["reading"]
                known["meaning"] = known["meaning"] or item["meaning"]
    return list(seen.values())


SYSTEM = """Bạn là chuyên gia âm Hán Việt cho từ điển Nhật–Việt.
Với mỗi từ, chọn âm Hán Việt CHUẨN cho từng chữ Kanji theo đúng nghĩa của từ (ví dụ 銀行 NGÂN HÀNG, 旅行 LỮ HÀNH,
音楽 ÂM NHẠC, 楽しい LẠC, 社長 XÃ TRƯỞNG, 長い TRƯỜNG). Mỗi chữ chỉ được chọn MỘT âm trong danh sách "options" của chữ đó
(nếu chữ không có options thì tự ghi âm chuẩn). Chữ ở vị trí 々 lặp lại chữ trước. Bỏ qua kana.
Trả ĐÚNG JSON thuần, không markdown: {"items":[{"id":0,"hv":"NGÂN HÀNG"}]} — "hv" viết HOA, các âm cách nhau một dấu cách,
số âm bằng số chữ Kanji."""


def openrouter_settings() -> tuple[str, str, str]:
    """Khoá/URL/model: biến môi trường trước, rồi application-local.yaml (không in khoá ra màn hình)."""
    local = {}
    path = ROOT / "src/main/resources/application-local.yaml"
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.match(r"\s*(openrouter\.(?:api\.key|api\.url|model)):\s*['\"]?([^'\"#]+)", line)
            if match:
                local[match.group(1)] = match.group(2).strip()
    key = os.environ.get("OPENROUTER_API_KEY") or local.get("openrouter.api.key", "")
    url = (os.environ.get("OPENROUTER_API_URL") or local.get("openrouter.api.url") or "https://openrouter.ai/api/v1").rstrip("/")
    model = os.environ.get("HAN_VIET_MODEL") or local.get("openrouter.model") or "openai/gpt-4o-mini"
    if not key:
        sys.exit("Thiếu OPENROUTER_API_KEY (hoặc openrouter.api.key trong application-local.yaml).")
    return key, url, model


def chat_items(system: str, items: list[dict], settings: tuple[str, str, str]) -> list[dict]:
    """Gửi {"items": [...]} cho model, nhận lại {"items": [...]}; lỗi mạng/JSON thử lại 3 lần rồi trả []."""
    key, url, model = settings
    body = json.dumps({"model": model, "temperature": 0, "response_format": {"type": "json_object"},
                       "messages": [{"role": "system", "content": system},
                                    {"role": "user", "content": json.dumps({"items": items}, ensure_ascii=False)}]}).encode()
    request = urllib.request.Request(url + "/chat/completions", data=body, headers={
        "Authorization": "Bearer " + key, "Content-Type": "application/json",
        "HTTP-Referer": "http://localhost:8080", "X-Title": "J-LAS flashcard textbook seed"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                content = json.load(response)["choices"][0]["message"]["content"]
            return json.loads(re.sub(r"^```(?:json)?|```$", "", content.strip()))["items"]
        except Exception as error:  # the batch is left for the next run after three failures
            print(f"  lô lỗi ({type(error).__name__}), thử lại {attempt + 1}/3", file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    return []


def ask(batch: list[dict], table: dict[str, list[str]], settings: tuple[str, str, str]) -> dict[str, str]:
    items = [{"id": i, "word": w["word"], "reading": w["reading"], "meaning": w["meaning"],
              "kanji": [{"char": c, "options": table.get(c, [])} for c in kanji_slots(w["word"])]}
             for i, w in enumerate(batch)]
    result = {}
    for answer in chat_items(SYSTEM, items, settings):
        index = answer.get("id")
        if isinstance(index, int) and 0 <= index < len(batch):
            hv = accept(batch[index]["word"], answer.get("hv", ""), table)
            if hv:
                result[batch[index]["word"]] = hv
    return result


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def write_json(path: Path, value: dict) -> None:
    # Ghi file tạm rồi thay, để lần build đọc cache giữa chừng không gặp JSON dở.
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(dict(sorted(value.items())), ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    temp.replace(path)


def read_cache() -> dict[str, str]:
    return read_json(CACHE)


def write_cache(cache: dict[str, str]) -> None:
    write_json(CACHE, cache)


def resolve(limit: int | None = None, workers: int = 6) -> dict[str, str]:
    table, words = kanji_candidates(), textbook_words()
    # Cache chỉ giữ từ còn dùng làm thẻ, để file tất định theo dữ liệu giáo trình.
    known = read_cache()
    cache = {w["word"]: known[w["word"]] for w in words if w["word"] in known}
    pending = [w for w in words if w["word"] not in cache][:limit]
    print(f"{len(cache)} từ đã có Hán Việt, cần hỏi {len(pending)} từ.")
    if not pending:
        return cache
    settings = openrouter_settings()
    batches = [pending[i:i + BATCH] for i in range(0, len(pending), BATCH)]
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for done, answers in enumerate(pool.map(lambda b: ask(b, table, settings), batches), 1):
            cache.update(answers)
            if done % 10 == 0 or done == len(batches):
                write_cache(cache)
                print(f"  {done}/{len(batches)} lô, cache {len(cache)} từ")
    write_cache(cache)
    return cache


if __name__ == "__main__":
    resolve(int(sys.argv[1]) if len(sys.argv) > 1 else None)
