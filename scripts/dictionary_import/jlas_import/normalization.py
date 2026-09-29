"""Shared normalization rules for stable dictionary lookup keys."""

from __future__ import annotations

import re
import unicodedata
from functools import lru_cache

from pykakasi import kakasi


_WHITESPACE = re.compile(r"\s+")


def normalize_text(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value or "")
    return _WHITESPACE.sub(" ", normalized).strip()


def normalize_reading(value: str) -> str:
    return "".join(_katakana_character_to_hiragana(char) for char in normalize_text(value))


def _katakana_character_to_hiragana(char: str) -> str:
    codepoint = ord(char)
    if 0x30A1 <= codepoint <= 0x30F6 or 0x30FD <= codepoint <= 0x30FE:
        return chr(codepoint - 0x60)
    return char


@lru_cache(maxsize=16_384)
def reading_to_romaji(value: str) -> str:
    reading = normalize_reading(value)
    if not reading:
        return ""
    return "".join(item["hepburn"] for item in _converter().convert(reading))


@lru_cache(maxsize=1)
def _converter():
    return kakasi()


def is_kanji_codepoint(value: str) -> bool:
    if len(value) != 1:
        return False
    codepoint = ord(value)
    return any(
        start <= codepoint <= end
        for start, end in (
            (0x3400, 0x4DBF),
            (0x4E00, 0x9FFF),
            (0xF900, 0xFAFF),
            (0x20000, 0x2FA1F),
            (0x30000, 0x323AF),
        )
    )

