"""Parsers for the Vietnamese Yomitan dictionaries (Mazii terms, KanjiDictVN kanji).

A Yomitan dictionary is a folder or ZIP archive of ``term_bank_N.json`` /
``kanji_bank_N.json`` files. Nested ZIP archives (as distributed for the JMdict
Vietnamese build) are searched as well.
"""

from __future__ import annotations

import hashlib
import io
import json
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

from .models import (
    CatalogValue,
    GlossRecord,
    ImportWarning,
    InvalidRecord,
    KanjiVietnameseRecord,
    VocabularyRecord,
    VocabularySenseRecord,
)
from .normalization import is_kanji_codepoint, normalize_reading, normalize_text


VIETNAMESE = "vie"
MAX_GLOSS_LENGTH = 2000
MEANING_SUMMARY_SENSES = 3
MEANING_SUMMARY_LENGTH = 500

_BANK_FILE = re.compile(r"(?:^|/)(term|kanji|tag)_bank_\d+\.json$")
_NUMBERED_LINE = re.compile(r"^\s*(\d+)\s*\.\s*")
_WHITESPACE = re.compile(r"\s+")
_RADICAL = re.compile(r"^\s*(.+?)\s+(\S)\s*\(")
_BRACKETED_MEANING = re.compile(r"^\[([^\]]+)\]\s*(.*)$")

# Vietnamese labels for JMdict part-of-speech codes. The dictionary UI filters
# on "danh từ", "động từ", "tính từ", "trạng từ|phó từ", so every label keeps
# one of those words where it applies.
POS_NAME_VI = {
    "n": "Danh từ",
    "n-adv": "Danh từ làm trạng từ",
    "n-t": "Danh từ chỉ thời gian",
    "n-pr": "Danh từ riêng",
    "n-pref": "Danh từ làm tiền tố",
    "n-suf": "Danh từ làm hậu tố",
    "pn": "Đại từ",
    "num": "Số từ",
    "ctr": "Trợ số từ",
    "pref": "Tiền tố",
    "suf": "Hậu tố",
    "prt": "Trợ từ",
    "conj": "Liên từ",
    "int": "Thán từ",
    "cop": "Hệ từ",
    "cop-da": "Hệ từ (だ)",
    "aux": "Trợ từ phụ trợ",
    "aux-v": "Trợ động từ",
    "aux-adj": "Tính từ phụ trợ",
    "exp": "Cụm từ",
    "id": "Thành ngữ",
    "unc": "Chưa phân loại",
    "adj-i": "Tính từ đuôi い",
    "adj-ix": "Tính từ đuôi い (いい/よい)",
    "adj-na": "Tính từ đuôi な",
    "adj-no": "Danh từ dùng như tính từ (+の)",
    "adj-pn": "Liên thể từ",
    "adj-t": "Tính từ đuôi たる",
    "adj-f": "Từ bổ nghĩa đứng trước danh từ",
    "adv": "Trạng từ",
    "adv-to": "Trạng từ (+と)",
    "v1": "Động từ nhóm II",
    "v1-s": "Động từ nhóm II (くれる)",
    "v5": "Động từ nhóm I",
    "vs": "Động từ nhóm III (する)",
    "vs-s": "Động từ nhóm III (する)",
    "vs-i": "Động từ nhóm III (する)",
    "vs-c": "Động từ nhóm III (す)",
    "vk": "Động từ nhóm III (来る)",
    "vz": "Động từ nhóm III (ずる)",
    "vi": "Tự động từ",
    "vt": "Tha động từ",
}
_POS_PREFIX_VI = (
    ("v5", "Động từ nhóm I"),
    ("v4", "Động từ (cổ)"),
    ("v2", "Động từ (cổ)"),
    ("vr", "Động từ (cổ)"),
    ("vn", "Động từ (cổ)"),
    ("v-", "Động từ"),
    ("adj-", "Tính từ (cổ)"),
)
# Mazii only marks conjugation classes through Yomitan deinflection rules.
_MAZII_RULE_POS = ("v5", "v1", "vs", "vk", "vz", "adj-i")


def pos_name_vi(code: str) -> str:
    if code in POS_NAME_VI:
        return POS_NAME_VI[code]
    for prefix, label in _POS_PREFIX_VI:
        if code.startswith(prefix):
            return label
    return code


def iter_bank_json(path: str | Path, kind: str) -> Iterator[list]:
    """Yield every row of every ``<kind>_bank_N.json`` under a folder or ZIP."""

    source = Path(path)
    if source.is_dir():
        for bank in sorted(source.rglob(f"{kind}_bank_*.json"), key=_bank_sort_key):
            yield from json.loads(bank.read_text(encoding="utf-8"))
    elif zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            yield from _iter_zip_banks(archive, kind)
    else:
        raise ValueError(f"{source.name} is not a Yomitan folder or ZIP archive")


def _iter_zip_banks(archive: zipfile.ZipFile, kind: str) -> Iterator[list]:
    names = sorted(archive.namelist(), key=_bank_sort_key)
    for name in names:
        match = _BANK_FILE.search(name)
        if match and match.group(1) == kind:
            yield from json.loads(archive.read(name).decode("utf-8"))
    for name in names:
        if name.lower().endswith(".zip"):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as nested:
                yield from _iter_zip_banks(nested, kind)


def _bank_sort_key(value) -> tuple:
    text = str(value).replace("\\", "/")
    numbers = re.findall(r"\d+", text.rsplit("/", 1)[-1])
    return (text.rsplit("/", 1)[0], int(numbers[-1]) if numbers else 0, text)


# ---------------------------------------------------------------------------
# Mazii Japanese-Vietnamese terms
# ---------------------------------------------------------------------------


@dataclass(slots=True)
class _TermGroup:
    expression: str
    reading: str
    score: float
    order: int
    sequences: set[int] = field(default_factory=set)
    han_viet: str | None = None
    meanings: list[str] = field(default_factory=list)
    meaning_keys: set[str] = field(default_factory=set)
    rules: list[str] = field(default_factory=list)
    warnings: list[ImportWarning] = field(default_factory=list)

    def add(self, row: list, han_viet: str | None, meanings: list[str]) -> None:
        if row[6]:
            self.sequences.add(int(row[6]))
        if han_viet and not self.han_viet:
            self.han_viet = han_viet
        for meaning in meanings:
            key = meaning.lower().rstrip(". ")
            if key and key not in self.meaning_keys:
                self.meaning_keys.add(key)
                self.meanings.append(meaning)
        for rule in (row[3] or "").split():
            if rule in _MAZII_RULE_POS and rule not in self.rules:
                self.rules.append(rule)


def parse_mazii_definition(text: str) -> tuple[str | None, list[str]]:
    """Split ``"HÁN VIỆT\\n1. nghĩa\\n2. nghĩa"`` into Han Viet and meanings."""

    lines = [line.strip() for line in (text or "").replace("\r", "\n").split("\n")]
    han_viet = None
    meanings: list[str] = []
    current: list[str] | None = None
    first_content = True
    for line in lines:
        if not line:
            continue
        numbered = _NUMBERED_LINE.match(line)
        if numbered:
            current = [_NUMBERED_LINE.sub("", line, count=1)]
            meanings.append(current)  # type: ignore[arg-type]
        elif first_content and current is None and line == line.upper():
            han_viet = _WHITESPACE.sub(" ", line)
        elif current is not None:
            current.append(line)
        else:
            current = [line]
            meanings.append(current)  # type: ignore[arg-type]
        first_content = False
    cleaned = [_clean_meaning(" ".join(parts)) for parts in meanings]  # type: ignore[arg-type]
    return han_viet or None, [meaning for meaning in cleaned if meaning]


def _clean_meaning(value: str) -> str:
    text = _WHITESPACE.sub(" ", value).strip()
    # Some community entries repeat the number ("1. 1.Công ty ma").
    text = _NUMBERED_LINE.sub("", text, count=1).strip()
    if text.endswith(".") and text.count(".") == 1:
        text = text[:-1].rstrip()
    return text


def load_jmdict_parts_of_speech(path: str | Path) -> dict[tuple[str, str], tuple[str, ...]]:
    """Map (normalized expression, normalized reading) to JMdict POS codes."""

    pos_tags: set[str] = set()
    for tag in iter_bank_json(path, "tag"):
        if tag[1] in {"partOfSpeech", "expression"}:
            pos_tags.add(tag[0])
    if not pos_tags:
        pos_tags = set(POS_NAME_VI)

    result: dict[tuple[str, str], list[str]] = {}
    for row in iter_bank_json(path, "term"):
        expression = normalize_text(row[0])
        reading = normalize_reading(row[1] or row[0])
        codes = result.setdefault((expression, reading), [])
        for tag in (row[2] or "").split():
            if tag in pos_tags and tag not in codes:
                codes.append(tag)
    return {key: tuple(value) for key, value in result.items() if value}


def iter_mazii_vi(
    path: str | Path, pos_path: str | Path | None = None
) -> Iterator[VocabularyRecord | InvalidRecord]:
    """Yield merged Mazii entries, most frequent first.

    Records are emitted in descending Yomitan score so new vocabulary ids follow
    popularity; the search API orders equal-tier matches by id.
    """

    rows = list(iter_bank_json(path, "term"))
    jmdict_pos = load_jmdict_parts_of_speech(pos_path) if pos_path else {}
    jmdict_readings: dict[str, list[str]] = {}
    for expression, reading in jmdict_pos:
        jmdict_readings.setdefault(expression, []).append(reading)

    known_readings: dict[str, dict[str, float]] = {}
    for row in rows:
        expression = normalize_text(row[0])
        if row[1]:
            readings = known_readings.setdefault(expression, {})
            reading = normalize_reading(row[1])
            readings[reading] = max(readings.get(reading, float("-inf")), float(row[4] or 0))

    groups: dict[tuple[str, str], _TermGroup] = {}
    for order, row in enumerate(rows):
        display_expression = (row[0] or "").strip()
        expression = normalize_text(display_expression)
        if not expression:
            continue
        display_reading = (row[1] or "").strip()
        warnings: list[ImportWarning] = []
        if not display_reading:
            display_reading, warning = _resolve_missing_reading(
                expression, display_expression, known_readings, jmdict_readings
            )
            if warning:
                warnings.append(warning)
        key = (expression, normalize_reading(display_reading))
        group = groups.get(key)
        score = float(row[4] or 0)
        if group is None:
            group = groups[key] = _TermGroup(display_expression, display_reading, score, order)
        elif score > group.score:
            group.score = score
            group.expression, group.reading = display_expression, display_reading
        for warning in warnings:
            if warning not in group.warnings:
                group.warnings.append(warning)
        for definition in row[5]:
            if isinstance(definition, str):
                han_viet, meanings = parse_mazii_definition(definition)
                group.add(row, han_viet, meanings)

    for (expression, reading), group in sorted(
        groups.items(), key=lambda item: (-item[1].score, item[1].order)
    ):
        external_id = _external_id(group, expression, reading)
        if not group.meanings:
            yield InvalidRecord(external_id, "entry has no Vietnamese meaning")
            continue
        yield _mazii_record(group, external_id, jmdict_pos.get((expression, reading), ()))


def _resolve_missing_reading(
    expression: str,
    display_expression: str,
    known_readings: dict[str, dict[str, float]],
    jmdict_readings: dict[str, list[str]],
) -> tuple[str, ImportWarning | None]:
    if not any(is_kanji_codepoint(char) for char in expression):
        return display_expression, None
    if expression in known_readings:
        readings = known_readings[expression]
        return max(readings, key=readings.get), None
    if expression in jmdict_readings:
        return jmdict_readings[expression][0], None
    from .normalization import _converter  # local import keeps pykakasi lazy

    reading = "".join(item["hira"] for item in _converter().convert(expression))
    return reading, ImportWarning(
        "READING_DERIVED", "Source has no reading; reading was derived automatically"
    )


def _external_id(group: _TermGroup, expression: str, reading: str) -> str:
    if group.sequences:
        return str(min(group.sequences))
    digest = hashlib.sha1(f"{expression}\t{reading}".encode("utf-8")).hexdigest()
    return f"k:{digest[:20]}"


def _mazii_record(
    group: _TermGroup, external_id: str, jmdict_codes: tuple[str, ...]
) -> VocabularyRecord:
    warnings = list(group.warnings)
    codes = jmdict_codes or tuple(group.rules)
    parts_of_speech = tuple(CatalogValue(code, None, pos_name_vi(code)) for code in codes)
    senses = []
    for order, meaning in enumerate(group.meanings, start=1):
        if len(meaning) > MAX_GLOSS_LENGTH:
            meaning = meaning[: MAX_GLOSS_LENGTH - 1] + "…"
            warnings.append(ImportWarning("GLOSS_TRUNCATED", f"Meaning {order} was truncated"))
        senses.append(
            VocabularySenseRecord(
                order=order,
                glosses=(GlossRecord(VIETNAMESE, meaning),),
                # Mazii senses do not align with JMdict senses: keep word-level POS on sense 1.
                parts_of_speech=parts_of_speech if order == 1 else (),
            )
        )
    summary = "; ".join(group.meanings[:MEANING_SUMMARY_SENSES])
    if len(summary) > MEANING_SUMMARY_LENGTH:
        summary = summary[: MEANING_SUMMARY_LENGTH - 1] + "…"
    return VocabularyRecord(
        external_id=external_id,
        expression=group.expression,
        reading=group.reading,
        senses=tuple(senses),
        warnings=tuple(warnings),
        han_viet=group.han_viet[:255] if group.han_viet else None,
        meaning=summary,
    )


# ---------------------------------------------------------------------------
# KanjiDictVN (Han Viet readings and Vietnamese meanings for Kanji)
# ---------------------------------------------------------------------------


def iter_kanjidict_vn(
    path: str | Path, variants_path: str | Path | None = None
) -> Iterator[KanjiVietnameseRecord]:
    """Yield Vietnamese Kanji data.

    KanjiDictVN often lists meanings only under the traditional form (驛, 繪, 壓)
    while the Japanese shinjitai (駅, 絵, 圧) has a reading but no meaning. With
    ``variants_path`` (KANJIDIC2 XML) such meanings are borrowed from a variant
    that shares a Han Viet reading.
    """

    rows = {}
    for row in iter_bank_json(path, "kanji"):
        literal = normalize_text(row[0])
        if len(literal) == 1 and literal not in rows:
            rows[literal] = row
    variants = load_kanjidic2_variants(variants_path) if variants_path else {}

    for literal, row in rows.items():
        readings = [reading for reading in (row[1] or "").split() if reading]
        han_viet = ", ".join(reading.upper() for reading in readings) or None
        meaning_vi = format_kanji_meanings(row[4] or ())
        warnings = ()
        if not meaning_vi:
            for variant in sorted(variants.get(literal, ())):
                variant_row = rows.get(variant)
                if variant_row and set(readings) & set((variant_row[1] or "").split()):
                    meaning_vi = format_kanji_meanings(variant_row[4] or ())
                    if meaning_vi:
                        warnings = (ImportWarning(
                            "MEANING_FROM_VARIANT", f"Meaning borrowed from variant {variant}"
                        ),)
                        break
        radical_character, radical_name = parse_radical(row[5].get("Radical") if row[5] else None)
        if not han_viet and not meaning_vi and not radical_name:
            continue
        yield KanjiVietnameseRecord(
            literal=literal,
            han_viet=han_viet[:255] if han_viet else None,
            meaning_vi=meaning_vi,
            radical_character=radical_character,
            radical_name_vi=radical_name,
            warnings=warnings,
        )


def load_kanjidic2_variants(path: str | Path) -> dict[str, set[str]]:
    """Bidirectional map of KANJIDIC2 ``<variant>`` links between characters."""

    from lxml import etree

    variants: dict[str, set[str]] = {}
    for _, element in etree.iterparse(
        str(Path(path)), events=("end",), tag="character", no_network=True, huge_tree=True
    ):
        literal = element.findtext("literal") or ""
        for variant in element.findall("misc/variant"):
            other = decode_kanjidic2_code(variant.get("var_type", ""), variant.text or "")
            if other and other != literal and len(literal) == 1:
                variants.setdefault(literal, set()).add(other)
                variants.setdefault(other, set()).add(literal)
        element.clear()
    return variants


def decode_kanjidic2_code(code_type: str, value: str) -> str | None:
    """Turn a KANJIDIC2 code (``jis208`` "1-81-67", ``ucs`` "9a5b"...) into a character."""

    try:
        if code_type == "ucs":
            return chr(int(value, 16))
        parts = [int(part) for part in value.split("-")]
        if code_type == "jis208" and len(parts) == 3 and parts[0] == 1:
            return bytes([0xA0 + parts[1], 0xA0 + parts[2]]).decode("euc_jp")
        if code_type == "jis212" and len(parts) == 2:
            return bytes([0x8F, 0xA0 + parts[0], 0xA0 + parts[1]]).decode("euc_jp")
        if code_type == "jis213" and len(parts) == 3:
            prefix = [] if parts[0] == 1 else [0x8F]
            return bytes(prefix + [0xA0 + parts[1], 0xA0 + parts[2]]).decode("euc_jis_2004")
    except (ValueError, UnicodeDecodeError):
        return None
    return None


def format_kanji_meanings(meanings: Iterable[str]) -> str | None:
    """``["[thực] ăn", "[tự] cho ăn"]`` -> ``"[thực] ăn; [tự] cho ăn"``.

    The bracketed Han Viet reading is only kept when it disambiguates.
    """

    parsed = []
    for meaning in meanings:
        text = _WHITESPACE.sub(" ", str(meaning)).strip()
        match = _BRACKETED_MEANING.match(text)
        parsed.append((match.group(1), match.group(2)) if match else (None, text))
    readings = {reading for reading, _ in parsed if reading}
    keep_reading = len(readings) > 1
    values = [
        f"[{reading}] {text}" if keep_reading and reading else text
        for reading, text in parsed
        if text
    ]
    return "; ".join(values) or None


def parse_radical(value: str | None) -> tuple[str | None, str | None]:
    """``"thực 食 (+1 nét)"`` -> ``("食", "Thực")``."""

    if not value:
        return None, None
    match = _RADICAL.match(value)
    if not match:
        return None, None
    name = match.group(1).strip()
    return normalize_text(match.group(2)), name[:1].upper() + name[1:]
