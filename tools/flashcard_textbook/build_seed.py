"""Sinh seed flashcard từ phần Từ vựng và phần Hán tự của giáo trình: mỗi bài một bộ thẻ, tên bộ ghi cấp Nx.

Nguồn: data/vnjpclub/ và data/vnjpclub_minna/ (giáo trình, bài, từ vựng, chữ Kanji, từ ghép, câu ví dụ). Chỉ lấy
mục của phần Từ vựng và phần Hán tự (entries.py); bài đọc, bài nghe, đề luyện và từ chú giải không làm thẻ. Bài còn
dưới 15 thẻ thì bỏ. Chỗ sách để trống lấy từ data/flashcard_textbook/: âm Hán Việt của từ (han_viet_words.json),
cách đọc/nghĩa của từ (word_fill.json) và của chữ Kanji (kanji_fill.json); hai bộ Hiragana/Katakana giữ lại ở
kana_sets.json. Build không gọi mạng hay DB; cùng dữ liệu → cùng SQL.

Chạy: python tools/flashcard_textbook/build_seed.py
Ra:   sql/seeds/seed_data_flashcard_textbook.sql (thêm bản giống hệt ở sqlrun/39_flashcard_textbook.sql nếu có sqlrun/)
      data/flashcard_textbook/report.json
"""
from __future__ import annotations

import collections
import json
import re
from pathlib import Path

from entries import MISSING, READING, VOCAB_PARTS, clean, entry, kanji_entry
from fill_words import FILL, KANJI_FILL, meaning_ok
from han_viet import ROOT, SOURCES, accept, compose, kanji_candidates, kanji_slots, load, read_cache, read_json

DATA = ROOT / "data/flashcard_textbook"
TEMPLATE = Path(__file__).with_name("template.sql")
OUTPUT = ROOT / "sql/seeds/seed_data_flashcard_textbook.sql"
RUN_OUTPUT = ROOT / "sqlrun/39_flashcard_textbook.sql"
REPORT = DATA / "report.json"
LEVELS = ("N5", "N4", "N3", "N2", "N1")
MIN_CARDS = 15
ROWS_PER_INSERT = 300
WORD_TYPES = {"n": "Danh từ", "v": "Động từ", "adj": "Tính từ", "adi": "Tính từ", "a-i": "Tính từ đuôi い",
              "a-na": "Tính từ đuôi な", "adv": "Phó từ", "suf": "Hậu tố"}


def word_type(code: str) -> str | None:
    labels = []
    for token in re.split(r"[\s,/()]+", (code or "").lower()):
        label = WORD_TYPES.get(token)
        if label and label not in labels:
            labels.append(label)
    return " / ".join(labels) or None


def book_name(book: dict) -> str:
    name = clean(book["ten"])
    return name if book["cap"] in name else f"{name} {book['cap']}"


def lesson_label(book: dict, unit: dict, lesson: dict, kanji_part: bool = False) -> tuple[str, str]:
    """Nhãn bài trong tên bộ và phần mô tả bài. Minna: "Bài n" là unit; sách khác: số bài trong sách.
    Bộ phần Hán tự thêm "(Hán tự)" trừ khi tên bài đã nói là chữ Hán."""
    if book["chien_luoc"] == "minna":
        label = detail = clean(unit["unit"])
    else:
        title = clean(lesson["bai"])
        japanese = clean(lesson.get("tieu_de_ja"))
        label = f"Bài {lesson['so_thu_tu_trong_sach']}: {title}"
        detail = f"{clean(unit['tieu_de_vi'])} – bài {lesson['so_thu_tu_trong_sach']}: {title}" + (f" ({japanese})" if japanese else "")
    if kanji_part and not re.search(r"chữ hán|hán tự", label, re.IGNORECASE):
        label += " (Hán tự)"
    return label, detail


def fit(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[:limit - 1].rstrip() + "…"


class Source:
    """Toàn bộ dữ liệu giáo trình cần cho build, đọc một lần."""

    def __init__(self):
        self.books, self.units, self.lessons = [], {}, {}
        self.vocab, self.kanji, self.compounds, self.examples = (collections.defaultdict(list) for _ in range(4))
        for source in SOURCES:
            self.books += [dict(b, minna=source.endswith("minna")) for b in load(source, "giao_trinh")]
            self.units.update({u["ma"]: u for u in load(source, "unit")})
            self.lessons.update({l["ma"]: l for l in load(source, "lesson")})
            for row in load(source, "tu_vung"):
                if row["phan"] in VOCAB_PARTS:
                    self.vocab[row["lesson_ma"]].append(row)
            for row in load(source, "kanji"):
                self.kanji[row["lesson_ma"]].append(row)
            for row in sorted(load(source, "tu_ghep"), key=lambda r: r["order_index"]):
                if clean(row.get("tu")) and clean(row.get("cach_doc")) and clean(row.get("nghia_vi")):
                    self.compounds[row["kanji_ma"]].append(row)
            for row in sorted(load(source, "vi_du"), key=lambda r: r["order_index"]):
                if row["loai_cha"] == "VOC" and clean(row.get("cau_ja")):
                    self.examples[row["ma_cha"]].append(row)
        self.books.sort(key=lambda b: (LEVELS.index(b["cap"]), not b["minna"], b["order_index"]))


def vocab_cards(rows: list[dict], level: str, src: Source, caches: dict, stats: collections.Counter) -> list[dict]:
    cards, seen = [], set()
    for row in sorted(rows, key=lambda r: r["order_index"]):
        item, reason = entry(row)
        fill = caches["words"].get(item["word"])
        if reason in MISSING and fill and READING.fullmatch(fill.get("reading", "")) and meaning_ok(fill.get("meaning", "")):
            item, reason = dict(item, **fill), None
            stats["từ vựng: bổ sung cách đọc/nghĩa"] += 1
        if reason:
            stats["từ vựng bỏ: " + reason] += 1
            continue
        if (item["word"], item["reading"]) in seen:
            stats["từ vựng bỏ: trùng trong bài"] += 1
            continue
        seen.add((item["word"], item["reading"]))
        han_viet = None
        if kanji_slots(item["word"]):
            han_viet = accept(item["word"], caches["han_viet"].get(item["word"], ""), caches["table"])
            stats["từ vựng: Hán Việt đã chọn" if han_viet else "từ vựng: Hán Việt ghép âm đầu (chưa chọn)"] += 1
            han_viet = han_viet or compose(item["word"], caches["table"])
        example = (src.examples.get(row["ma"]) or [{}])[0]
        cards.append({"front": fit(item["word"], 500), "back": fit(item["meaning"], 500), "reading": fit(item["reading"], 255),
                      "meaning": fit(item["meaning"], 500), "han_viet": han_viet, "jlpt_level": level,
                      "word_type": word_type(row.get("tu_loai")),
                      "example_ja": clean(example.get("cau_ja")) or None, "example_vi": clean(example.get("dich_vi")) or None})
    return cards


def kanji_cards(rows: list[dict], level: str, src: Source, caches: dict, stats: collections.Counter) -> list[dict]:
    """Thẻ chữ Kanji: âm Hán Việt, cách đọc, nghĩa; ví dụ là tối đa 3 từ ghép sách in kèm chữ đó."""
    cards, seen = [], set()
    for row in sorted(rows, key=lambda r: r["order_index"]):
        item, reason = kanji_entry(row)
        fill = caches["kanji"].get(item["kanji"])
        if reason in (*MISSING, "thiếu âm Hán Việt") and fill:
            item, reason = dict(item, **{k: v for k, v in fill.items() if not item.get(k)}), None
            stats["Hán tự: bổ sung âm/cách đọc/nghĩa"] += 1
        if reason:
            stats["Hán tự bỏ: " + reason] += 1
            continue
        if item["kanji"] in seen:
            stats["Hán tự bỏ: trùng trong bài"] += 1
            continue
        seen.add(item["kanji"])
        compounds = src.compounds.get(row["ma"], [])[:3]
        cards.append({"front": item["kanji"], "back": fit(item["meaning"], 500), "reading": fit(item["reading"], 255),
                      "meaning": fit(item["meaning"], 500), "han_viet": fit(item["han_viet"], 255), "jlpt_level": level,
                      "word_type": "Kanji",
                      "example_ja": "、".join(f"{clean(c['tu'])}（{clean(c['cach_doc'])}）" for c in compounds) or None,
                      "example_vi": "; ".join(f"{clean(c['tu'])}: {clean(c['nghia_vi'])}" for c in compounds) or None})
    return cards


def textbook_sets(src: Source, caches: dict, stats: collections.Counter) -> list[dict]:
    sets = []
    for book in src.books:
        lessons = sorted((l for l in src.lessons.values() if l["giao_trinh_ma"] == book["ma"]),
                         key=lambda l: (src.units[l["unit_ma"]]["order_index"], l["order_index"]))
        name = book_name(book)
        for lesson in lessons:
            unit = src.units[lesson["unit_ma"]]
            for kanji_part, rows in ((False, src.vocab.get(lesson["ma"])), (True, src.kanji.get(lesson["ma"]))):
                if not rows:
                    continue
                build = kanji_cards if kanji_part else vocab_cards
                cards = build(rows, book["cap"], src, caches, stats)
                if len(cards) < MIN_CARDS:
                    stats[f"bài bỏ: dưới {MIN_CARDS} thẻ"] += 1
                    continue
                label, detail = lesson_label(book, unit, lesson, kanji_part)
                part = "Chữ Kanji" if kanji_part else "Từ vựng"
                sets.append({"key": lesson["ma"] + ("-HANTU" if kanji_part else ""), "kind": "TEXTBOOK", "book": name,
                             "level": book["cap"], "part": part, "title": fit(f"{name} – {label}", 100),
                             "description": fit(f"{part} của {name}, {detail}. Cấp {book['cap']}. Thẻ có cách đọc, "
                                                "nghĩa tiếng Việt, âm Hán Việt và ví dụ trong sách (nếu có).", 500),
                             "cards": cards})
    titles = collections.Counter(s["title"] for s in sets)
    assert max(titles.values(), default=1) == 1, [t for t, n in titles.items() if n > 1][:5]
    return sets


def sql_text(value) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    if "\\" in value:  # an toàn cả khi bật NO_BACKSLASH_ESCAPES
        return f"CONVERT(X'{value.encode('utf-8').hex()}' USING utf8mb4)"
    return "'" + value.replace("'", "''") + "'"


def inserts(table: str, columns: tuple[str, ...], rows: list[tuple]) -> list[str]:
    head = f"INSERT INTO {table} ({', '.join(columns)}) VALUES\n"
    return [head + ",\n".join("(" + ", ".join(sql_text(v) for v in row) + ")" for row in rows[i:i + ROWS_PER_INSERT]) + ";"
            for i in range(0, len(rows), ROWS_PER_INSERT)]


def build() -> dict:
    stats: collections.Counter = collections.Counter()
    kana = json.loads((DATA / "kana_sets.json").read_text(encoding="utf-8"))
    retired = json.loads((DATA / "retired_community_sets.json").read_text(encoding="utf-8"))
    caches = {"han_viet": read_cache(), "words": read_json(FILL), "kanji": read_json(KANJI_FILL), "table": kanji_candidates()}
    books = textbook_sets(Source(), caches, stats)
    sets = [dict(s, key=f"KANA-{i}", kind="KANA") for i, s in enumerate(kana, 1)] + books
    set_rows, card_rows = [], []
    for rank, item in enumerate(sets):
        set_rows.append((item["key"], item["kind"], rank, item["title"], item["description"]))
        for position, card in enumerate(item["cards"]):
            card_rows.append((item["key"], position) + tuple(card.get(c) for c in (
                "front", "back", "reading", "meaning", "han_viet", "jlpt_level", "word_type", "example_ja", "example_vi")))
    data = "\n".join(
        inserts("fct_set", ("set_key", "kind", "sort_rank", "title", "description"), set_rows)
        + inserts("fct_card", ("set_key", "position", "front", "back", "reading", "meaning", "han_viet", "jlpt_level",
                               "word_type", "example_ja", "example_vi"), card_rows)
        + inserts("fct_retired", ("owner_email", "title"), [(r["owner_email"], r["title"]) for r in retired]))
    totals = {"TEXTBOOK_SET_COUNT": len(books), "TEXTBOOK_CARD_COUNT": sum(len(s["cards"]) for s in books),
              "BOOK_COUNT": len({s["book"] for s in books}), "KANA_SET_COUNT": len(kana)}
    sql = TEMPLATE.read_text(encoding="utf-8").replace("{{DATA}}", data)
    for token, count in totals.items():
        sql = sql.replace("{{" + token + "}}", str(count))
    OUTPUT.write_bytes(sql.encode("utf-8"))
    if RUN_OUTPUT.parent.is_dir():  # sqlrun/ chỉ có trên máy giữ file chạy DB local
        RUN_OUTPUT.write_bytes(sql.encode("utf-8"))
    per_book = collections.defaultdict(lambda: {"level": "", "Từ vựng": [0, 0], "Chữ Kanji": [0, 0]})
    for s in books:
        per_book[s["book"]]["level"] = s["level"]
        per_book[s["book"]][s["part"]][0] += 1
        per_book[s["book"]][s["part"]][1] += len(s["cards"])
    REPORT.write_text(json.dumps({"totals": totals, "rows": dict(sorted(stats.items())),
                                  "books (bộ, thẻ)": per_book}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return {"totals": totals, "rows": dict(sorted(stats.items()))}


if __name__ == "__main__":
    print(json.dumps(build(), ensure_ascii=False, indent=2))
