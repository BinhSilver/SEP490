"""Mục giáo trình → thẻ (dùng chung cho han_viet.py, fill_words.py, exchange.py, build_seed.py).

Chỉ lấy phần Từ vựng (tu_vung có phan vocabulary/vocab) và phần Hán tự (kanji) của sách; từ chú giải trong bài đọc,
bài nghe hay bài tổng hợp (reading/listening/generic) không làm thẻ.
Giáo trình lẫn câu, danh sách (・ ／), tiêu đề kèm tiếng Việt và chú thích trong ngoặc. Chú thích được làm sạch
(遅れる（II) → 遅れる, [お]酒 → お酒, 正確[な] → 正確（な）, 出ます[じゅぎょうに] → 出ます); mục vẫn không phải
một từ thì bị bỏ, có ghi lý do. Lý do "thiếu ..." còn bổ sung được (fill_words.py hoặc exchange.py).
"""
from __future__ import annotations

import re
import unicodedata

VOCAB_PARTS = ("vocabulary", "vocab")
KANJI = re.compile(r"[㐀-䶿一-鿿豈-﫿]")
KANA = r"ぁ-ゟ゠-ヿー"
JAPANESE = re.compile(rf"[{KANA}㐀-䶿一-鿿豈-﫿々]")
VERB_GROUP = re.compile(r"\s*[（(]\s*I{1,3}\s*[)）]")
OPTIONAL_PREFIX = re.compile(r"^[\[［（(〔](お|ご)[\]］）)〕]")
SUFFIX_NOTE = re.compile(r"[\[［（(](する|な)[\]］）)]$")
TRAILING_NOTE = re.compile(rf"\s*[\[［（(][{KANA}]+[\]］）)]$")
READING = re.compile(rf"[{KANA}～〜]+(?:（(?:する|な)）)?")
KANJI_READING = re.compile(rf"[{KANA}]+(?:、[{KANA}]+)*")
SYLLABLE = re.compile(r"[A-ZĐÀ-Ỹ]+")
NOT_A_WORD = re.compile(r"[\sA-Za-zÀ-ỹĐđ0-9０-９「」『』【】。、，,！!？?・／/()（）\[\]［］:：…＜＞<>―－□→←~＝=_＋+〔〕]")
JUNK_MEANING = re.compile(r".*:\s*")  # nhãn bài tập kiểu "Đáp án:"
MISSING = ("thiếu cách đọc", "thiếu nghĩa")


def clean(text) -> str:
    return re.sub(r"\s+", " ", str(text or "")).strip()


def tidy(text) -> str:
    """Bỏ nhãn nhóm động từ, mở ngoặc tiền tố お/ご, giữ đuôi （する）/（な）, bỏ ghi chú kana khác ở cuối."""
    text = VERB_GROUP.sub("", clean(text))
    text = OPTIONAL_PREFIX.sub(r"\1", text)
    if SUFFIX_NOTE.search(text):
        return SUFFIX_NOTE.sub(r"（\1）", text)
    return TRAILING_NOTE.sub("", text)


def is_word(word: str, row: dict) -> bool:
    core = SUFFIX_NOTE.sub("", word)
    return (bool(JAPANESE.search(word)) and (row.get("tu_loai") or "").strip() != "s"
            and len(core) <= 12 and not NOT_A_WORD.search(core))


def entry(row: dict) -> tuple[dict, str | None]:
    """Mục của phần Từ vựng → (từ đã làm sạch, lý do bỏ). Từ chỉ có kana đọc đúng như viết."""
    word, reading, meaning = tidy(row.get("tu")), tidy(row.get("cach_doc")), clean(row.get("nghia_vi"))
    if not reading and not KANJI.search(word) and READING.fullmatch(word):
        reading = word
    item = {"word": word, "reading": reading, "meaning": meaning}
    if not is_word(word, row):
        return item, "câu / danh sách / tiêu đề, không phải từ"
    if JUNK_MEANING.fullmatch(meaning):
        return item, "nghĩa là nhãn bài tập"
    if not READING.fullmatch(reading) or len(reading) > 40:
        return item, "thiếu cách đọc"
    if not meaning:
        return item, "thiếu nghĩa"
    return item, None


def han_viet_list(text) -> str:
    """'HÀNG, HÀNH' / 'LẠC / NHẠC' / 'Thượng' → 'HÀNG, HÀNH' / 'LẠC, NHẠC' / 'THƯỢNG'."""
    parts = []
    for part in re.split(r"[,/;、]", unicodedata.normalize("NFC", str(text or ""))):
        part = part.strip().upper()
        if SYLLABLE.fullmatch(part) and part not in parts:
            parts.append(part)
    return ", ".join(parts)


def kanji_reading(text) -> str:
    """'ジョウ / ショウ / あ.げる / -うえ / うたが・う / たおーれる' → 'ジョウ、ショウ、あげる、うえ、うたがう、たおれる'
    (tối đa 6 cách đọc)."""
    readings = []
    for part in re.split(r"[/、,;]", str(text or "")):
        # ".", "・" và "ー" giữa hai hiragana đều là dấu tách okurigana (うたが.う, うたが・う, うたがーう).
        part = re.sub(r"[.・\-]", "", part)
        part = re.sub(r"(?<=[ぁ-ゟ])ー(?=[ぁ-ゟ])", "", part).strip()
        if part and re.fullmatch(rf"[{KANA}]+", part) and part not in readings:
            readings.append(part)
    return "、".join(readings[:6])


def kanji_entry(row: dict) -> tuple[dict, str | None]:
    """Mục của phần Hán tự → (chữ, âm Hán Việt, cách đọc, nghĩa), lý do bỏ."""
    char = clean(row.get("kanji"))
    item = {"kanji": char, "han_viet": han_viet_list(row.get("han_viet")),
            "reading": kanji_reading(row.get("cach_doc")) or kanji_reading(" / ".join([row.get("on") or "", row.get("kun") or ""])),
            "meaning": clean(row.get("nghia_vi"))}
    if len(char) != 1 or not KANJI.match(char):
        return item, "không phải một chữ Kanji"
    if not item["han_viet"]:
        return item, "thiếu âm Hán Việt"
    if not item["reading"]:
        return item, "thiếu cách đọc"
    if not item["meaning"] or JUNK_MEANING.fullmatch(item["meaning"]):
        return item, "thiếu nghĩa"
    return item, None
