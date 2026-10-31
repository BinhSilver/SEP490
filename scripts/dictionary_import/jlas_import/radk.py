"""Parser for RADKFILE/RADKFILEX (EDRDG multi-radical lookup data).

Format (EUC-JP / EUC-JIS-2004)::

    # comment
    $ 一 1                 <- component, stroke count
    亜唖阿姶悪...          <- kanji containing that component
    $ 化 2 js01            <- component written with a substitute kanji (亻 is not in JIS)

Each component becomes one :class:`KanjiComponentRecord` listing its kanji.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

from .models import ImportWarning

# RADKFILE writes a few components with a kanji that contains them, because the
# component glyph itself is not in JIS X 0208 (see radkfile header). Show the real glyph.
SUBSTITUTE_GLYPHS = {
    "化": "亻", "个": "𠆢", "并": "丷", "刈": "刂", "乞": "𠂉", "込": "辶", "尚": "⺌",
    "忙": "忄", "扎": "扌", "汁": "氵", "犯": "犭", "艾": "艹", "邦": "⻏", "阡": "⻖",
    "老": "耂", "杰": "灬", "礼": "礻", "疔": "疒", "禹": "禸", "初": "衤", "買": "罒",
    "滴": "啇", "｜": "丨", "ノ": "丿",
}
# Component glyph -> Kangxi radical character in the radical table (for the Vietnamese name).
RADICAL_OF_VARIANT = {
    "亻": "人", "𠆢": "人", "刂": "刀", "辶": "辵", "⺌": "小", "忄": "心", "扌": "手",
    "氵": "水", "犭": "犬", "艹": "艸", "⻏": "邑", "⻖": "阜", "耂": "老", "灬": "火",
    "礻": "示", "衤": "衣", "罒": "网",
}


@dataclass(frozen=True, slots=True)
class KanjiComponentRecord:
    source_character: str
    character: str
    stroke_count: int
    display_order: int
    radical_character: str
    kanji: tuple[str, ...] = ()
    warnings: tuple[ImportWarning, ...] = field(default=())

    @property
    def record_id(self) -> str:
        return self.source_character


def read_radkfile(path: str | Path) -> str:
    raw = Path(path).read_bytes()
    for encoding in ("euc_jis_2004", "euc_jp"):
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8")


def iter_radkfile(path: str | Path) -> Iterator[KanjiComponentRecord]:
    current: dict | None = None
    order = 0
    for line in read_radkfile(path).splitlines():
        if not line or line.startswith("#"):
            continue
        if line.startswith("$"):
            if current:
                yield _record(current)
            parts = line.split()
            order += 1
            source = parts[1]
            glyph = SUBSTITUTE_GLYPHS.get(source, source)
            current = {
                "source": source,
                "glyph": glyph,
                "strokes": int(parts[2]),
                "order": order,
                "kanji": [],
            }
        elif current is not None:
            current["kanji"].extend(char for char in line.strip() if not char.isspace())
    if current:
        yield _record(current)


def _record(current: dict) -> KanjiComponentRecord:
    seen = dict.fromkeys(current["kanji"])
    return KanjiComponentRecord(
        source_character=current["source"],
        character=current["glyph"],
        stroke_count=current["strokes"],
        display_order=current["order"],
        radical_character=RADICAL_OF_VARIANT.get(current["glyph"], current["glyph"]),
        kanji=tuple(seen),
    )
