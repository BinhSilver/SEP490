#!/usr/bin/env python3
"""Build stroke-order SVGs for dictionary Kanji that KanjiVG r20250816 does not cover.

Candidates are tried in priority order (Japanese stroke order first); the first
one whose stroke count matches KANJIDIC2 is written:

1. ``kanjivg``  - a newer KanjiVG release ZIP, copied unchanged (CC BY-SA 3.0).
2. ``ja``       - AnimCJK ``svgsJa`` (Japanese order, Arphic Public License).
3. ``composed`` - built from KanjiVG components: a character whose IDS is
   ⿰/⿱/⿸ of two parts takes each part's strokes from a KanjiVG character that
   has the same part at the same position (鱮 = 魚 of 鯉 + 與 of 嶼). Japanese
   order, approximate shapes (CC BY-SA 3.0, like KanjiVG).
4. ``zh``       - AnimCJK ``svgsZhHant``/``svgsZhHans`` or Make Me a Hanzi
   (Chinese order, Arphic Public License).

AnimCJK/Make Me a Hanzi medians are written in KanjiVG's layout and 109x109 frame
(one ``<path id="kvg:XXXXX-sN">`` per stroke plus ``StrokeNumbers``) so both
``stroke-order.js`` and the handwriting matcher can read them. Only Kanji used by
dictionary vocabulary and lacking ``stroke_svg_uri`` are processed;
``--update-db`` fills that column.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

import mysql.connector
from lxml import etree


DEFAULT_OUT = Path("src/main/resources/static/assets/stroke-order-extra")
DEFAULT_URI_PREFIX = "/assets/stroke-order-extra"
_ANIMCJK_MEDIAN = re.compile(r'<path[^>]*clip-path="url\(#z\d+c(\d+)\)"[^>]*\sd="([^"]+)"')
_KVG_STROKE = re.compile(r'<path[^>]*\sid="kvg:[0-9a-f]{5}-s\d+"')
_POINT = re.compile(r"(-?\d+(?:\.\d+)?)[ ,](-?\d+(?:\.\d+)?)")
_KVG_NS = "http://kanjivg.tagaini.net"
_SVG_NS = "http://www.w3.org/2000/svg"
_IDS_POSITIONS = {"⿰": ("left", "right"), "⿱": ("top", "bottom"), "⿸": ("tare", "tarec")}
# IDS data and KanjiVG name the same component differently (radical forms).
_COMPONENT_ALIASES = {
    "⺘": "扌", "手": "扌", "糹": "糸", "⻊": "𧾷", "足": "𧾷", "⺡": "氵", "⺅": "亻",
    "⺮": "竹", "⺾": "艹", "艸": "艹", "飠": "𩙿", "⻝": "𩙿", "釒": "金", "⻖": "阝",
    "⻏": "阝", "⺨": "犭", "⺗": "忄", "⻌": "辶", "靑": "青", "⺼": "月",
}

ANIMCJK_HEADER = """<!--
Stroke order for {char} (U+{code}) converted to the KanjiVG layout from
AnimCJK {folder} (https://github.com/parsimonhi/animCJK), Copyright FM-SH,
derived from Make Me a Hanzi and the Arphic PL KaitiM fonts.
Distributed under the Arphic Public License:
https://ftp.gnu.org/non-gnu/chinese-fonts-truetype/LICENSE
Stroke order standard: {standard}.
-->
"""
MMAH_HEADER = """<!--
Stroke order for {char} (U+{code}) converted to the KanjiVG layout from
Make Me a Hanzi (https://github.com/skishore/makemeahanzi), derived from the
Arphic PL KaitiM fonts. Distributed under the Arphic Public License:
https://ftp.gnu.org/non-gnu/chinese-fonts-truetype/LICENSE
Stroke order standard: zh (Chinese).
-->
"""
COMPOSED_HEADER = """<!--
Stroke order for {char} (U+{code}) composed from KanjiVG components ({ids}):
strokes of {first}, then strokes of {second}.
KanjiVG is copyright (C) 2009-2026 Ulrich Apel (http://kanjivg.tagaini.net),
distributed under Creative Commons Attribution-Share Alike 3.0; this derived
file is distributed under the same licence.
Stroke order standard: ja (Japanese, composed - shapes are approximate).
-->
"""


def _svg_document(char: str, strokes: list[str], first_points: list[tuple[float, float]]) -> str:
    code = f"{ord(char):05x}"
    paths = [f'\t<path id="kvg:{code}-s{i}" d="{d}"/>' for i, d in enumerate(strokes, start=1)]
    numbers = [
        f'\t<text transform="matrix(1 0 0 1 {x - 4:.2f} {y - 2:.2f})">{i}</text>'
        for i, (x, y) in enumerate(first_points, start=1)
    ]
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="109" height="109" viewBox="0 0 109 109">\n'
        + f'<g id="kvg:StrokePaths_{code}" style="fill:none;stroke:#000000;stroke-width:3;'
        + 'stroke-linecap:round;stroke-linejoin:round;">\n'
        + "\n".join(paths)
        + "\n</g>\n"
        + f'<g id="kvg:StrokeNumbers_{code}" style="font-size:8px;fill:#808080">\n'
        + "\n".join(numbers)
        + "\n</g>\n</svg>\n"
    )


def kanjivg_layout(char: str, medians: list[list[tuple[float, float]]], header: str) -> str:
    """Write medians (1024 grid) in KanjiVG's 109x109 frame, which the handwriting matcher assumes."""
    scale = 109 / 1024
    strokes = [
        "M" + " L".join(f"{x * scale:.2f},{y * scale:.2f}" for x, y in points)
        for points in medians
    ]
    starts = [(points[0][0] * scale, points[0][1] * scale) for points in medians]
    return header + _svg_document(char, strokes, starts)


def animcjk_medians(svg_text: str) -> list[list[tuple[float, float]]]:
    medians = sorted(
        (int(order), [(float(x), float(y)) for x, y in _POINT.findall(d)])
        for order, d in _ANIMCJK_MEDIAN.findall(svg_text)
    )
    return [points for _, points in medians if points]


def load_mmah(path: Path) -> dict[str, list[list[tuple[float, float]]]]:
    result = {}
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            row = json.loads(line)
            # Make Me a Hanzi uses a y-up 1024 grid shifted by 900.
            result[row["character"]] = [
                [(float(x), 900.0 - float(y)) for x, y in median] for median in row["medians"]
            ]
    return result


def kanjivg_zip_files(path: Path) -> dict[str, tuple[zipfile.ZipFile, str]]:
    archive = zipfile.ZipFile(path)
    files = {}
    for name in archive.namelist():
        match = re.search(r"/([0-9a-f]{5})\.svg$", name)
        if match:
            files[chr(int(match.group(1), 16))] = (archive, name)
    return files


# ---------------------------------------------------------------------------
# Composition from KanjiVG components
# ---------------------------------------------------------------------------


def _component(value: str | None) -> str | None:
    return _COMPONENT_ALIASES.get(value, value) if value else None


@dataclass
class ComponentIndex:
    # (component, position) -> [(donor character, stroke paths, {position: stroke paths})]
    parts: dict = field(default_factory=lambda: collections.defaultdict(list))
    # character -> stroke paths of its whole KanjiVG file
    standalone: dict = field(default_factory=dict)

    def get(self, key, default=None):
        return [(donor, strokes) for donor, strokes, _ in self.parts.get(key, [])] or default

    def __bool__(self):
        return bool(self.parts)


def load_component_index(kanjivg_dir: Path) -> ComponentIndex:
    """Top-level component splits of every KanjiVG character plus each whole character."""
    index = ComponentIndex()
    parser = etree.XMLParser(resolve_entities=False, no_network=True, load_dtd=False)
    for svg in sorted(kanjivg_dir.glob("*.svg")):
        if not re.fullmatch(r"[0-9a-f]{5}\.svg", svg.name):
            continue
        root = etree.parse(str(svg), parser).getroot()
        character_group = root.find(f".//{{{_SVG_NS}}}g[@id='kvg:{svg.stem}']")
        if character_group is None:
            continue
        donor = character_group.get(f"{{{_KVG_NS}}}element") or chr(int(svg.stem, 16))
        index.standalone[chr(int(svg.stem, 16))] = [
            path.get("d") for path in character_group.iter(f"{{{_SVG_NS}}}path")
        ]
        groups = []
        for group in character_group.findall(f"{{{_SVG_NS}}}g"):
            position = group.get(f"{{{_KVG_NS}}}position")
            if not position or group.get(f"{{{_KVG_NS}}}part"):
                continue
            strokes = [path.get("d") for path in group.iter(f"{{{_SVG_NS}}}path")]
            names = {_component(group.get(f"{{{_KVG_NS}}}element")),
                     _component(group.get(f"{{{_KVG_NS}}}original"))} - {None}
            groups.append((position, strokes, names))
        slots = {position: strokes for position, strokes, _ in groups}
        for position, strokes, names in groups:
            for name in names:
                index.parts[(name, position)].append((donor, strokes, slots))
    return index


# --- SVG path geometry (KanjiVG uses M/m, C/c, S/s and occasionally L/l) -----------------


def parse_path(path_data: str) -> list[tuple[str, list[tuple[float, float]]]]:
    """Absolute M/L/C segments; S is expanded to C so every segment can be transformed."""
    tokens = re.findall(r"[MmLlHhVvCcSsZz]|-?(?:\d+\.?\d*|\.\d+)(?:e-?\d+)?", path_data)
    segments: list[tuple[str, list[tuple[float, float]]]] = []
    x = y = 0.0
    last_control = None
    command = None
    i = 0

    def number():
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
            if command in "Zz":
                segments.append(("Z", []))
                continue
        relative = command.islower()
        kind = command.upper()
        if kind == "M":
            px, py = number(), number()
            x, y = (x + px, y + py) if relative else (px, py)
            segments.append(("M", [(x, y)]))
            command = "l" if relative else "L"
            last_control = None
        elif kind in "LHV":
            if kind == "L":
                px, py = number(), number()
                nx, ny = (x + px, y + py) if relative else (px, py)
            elif kind == "H":
                value = number()
                nx, ny = (x + value if relative else value), y
            else:
                value = number()
                nx, ny = x, (y + value if relative else value)
            x, y = nx, ny
            segments.append(("L", [(x, y)]))
            last_control = None
        elif kind == "C":
            values = [number() for _ in range(6)]
            points = [(x + values[k], y + values[k + 1]) if relative else (values[k], values[k + 1])
                      for k in (0, 2, 4)]
            segments.append(("C", points))
            last_control = points[1]
            x, y = points[2]
        elif kind == "S":
            values = [number() for _ in range(4)]
            first = (2 * x - last_control[0], 2 * y - last_control[1]) if last_control else (x, y)
            second, end = [(x + values[k], y + values[k + 1]) if relative else (values[k], values[k + 1])
                           for k in (0, 2)]
            segments.append(("C", [first, second, end]))
            last_control = second
            x, y = end
    return segments


def bbox(paths: list[str]) -> tuple[float, float, float, float]:
    points = [point for d in paths for _, segment in parse_path(d) for point in segment]
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    return min(xs), min(ys), max(xs), max(ys)


def fit_into(paths: list[str], target: tuple[float, float, float, float]) -> list[str]:
    """Scale/translate whole-character strokes into a component slot's bounding box."""
    sx0, sy0, sx1, sy1 = bbox(paths)
    tx0, ty0, tx1, ty1 = target
    scale_x = (tx1 - tx0) / max(sx1 - sx0, 1e-6)
    scale_y = (ty1 - ty0) / max(sy1 - sy0, 1e-6)

    def map_point(point):
        return (tx0 + (point[0] - sx0) * scale_x, ty0 + (point[1] - sy0) * scale_y)

    result = []
    for d in paths:
        parts = []
        for kind, segment in parse_path(d):
            if kind == "Z":
                parts.append("Z")
            else:
                parts.append(kind + " ".join(f"{px:.2f},{py:.2f}" for px, py in map(map_point, segment)))
        result.append("".join(parts))
    return result


def load_ids(cjkvi_ids: Path | None, kanjidict_vn: Path | None) -> dict[str, list[str]]:
    """Character -> IDS candidates, Japanese-tagged cjkvi variants first."""
    result: dict[str, list[str]] = collections.defaultdict(list)
    if cjkvi_ids:
        for line in cjkvi_ids.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if line.startswith("#") or len(parts) < 3:
                continue
            variants = parts[2:]
            japanese = [v for v in variants if re.search(r"\[[^\]]*J[^\]]*\]", v)]
            for variant in japanese + [v for v in variants if v not in japanese]:
                result[parts[1]].append(re.sub(r"\[[^\]]*\]$", "", variant))
    if kanjidict_vn:
        from jlas_import.yomitan import iter_bank_json

        for row in iter_bank_json(kanjidict_vn, "kanji"):
            shape = (row[5] or {}).get("Shape") if len(row) > 5 else None
            if shape:
                result[row[0]].append(shape)
    return result


def compose(char: str, stroke_count: int | None, ids_list: list[str], index: ComponentIndex) -> str | None:
    """Try, per IDS: both parts taken from KanjiVG donors at the right position; otherwise one
    part from a donor ("frame") and the other part's whole KanjiVG character fitted into the
    frame's opposite slot (篊 = 竹 of 箱 + 洪 scaled into 相's box)."""
    for ids in ids_list:
        if len(ids) != 3 or ids[0] not in _IDS_POSITIONS:
            continue
        first_name, second_name = _component(ids[1]), _component(ids[2])
        first_position, second_position = _IDS_POSITIONS[ids[0]]
        firsts = index.get((first_name, first_position), [])
        seconds = index.get((second_name, second_position), [])
        # Prefer the most common stroke count per component (skips odd variant forms).
        for first in _by_common_stroke_count(firsts):
            for second in _by_common_stroke_count(seconds):
                if not stroke_count or len(first[1]) + len(second[1]) == stroke_count:
                    return _composed_svg(char, ids, first[1] + second[1],
                                         f"{ids[1]} of {first[0]}", f"{ids[2]} of {second[0]}")
        if ids[0] == "⿸":
            # An enclosure slot spans most of the frame; fitting a whole character there
            # produces distorted shapes (checked visually on 厴, 羗).
            continue
        frames = (
            (first_name, first_position, second_position, ids[2], True),
            (second_name, second_position, first_position, ids[1], False),
        )
        for name, position, slot_position, fill, frame_first in frames:
            whole = index.standalone.get(fill)
            if not whole:
                continue
            donors = [(donor, strokes, slots) for donor, strokes, slots in index.parts.get((name, position), [])
                      if slot_position in slots]
            counts = collections.Counter(len(strokes) for _, strokes, _ in donors)
            for donor, strokes, slots in sorted(donors, key=lambda d: (-counts[len(d[1])], d[0])):
                if stroke_count and len(strokes) + len(whole) != stroke_count:
                    continue
                fitted = fit_into(whole, bbox(slots[slot_position]))
                ordered = strokes + fitted if frame_first else fitted + strokes
                frame_label = f"{name} of {donor}"
                fill_label = f"{fill} (whole character) in the {slot_position} slot of {donor}"
                return _composed_svg(char, ids, ordered,
                                     frame_label if frame_first else fill_label,
                                     fill_label if frame_first else frame_label)
    return None


def _composed_svg(char: str, ids: str, strokes: list[str], first_label: str, second_label: str) -> str:
    header = COMPOSED_HEADER.format(char=char, code=f"{ord(char):05X}", ids=ids,
                                    first=first_label, second=second_label)
    return header + _svg_document(char, strokes, [_first_point(d) for d in strokes])


def _by_common_stroke_count(candidates):
    counts = collections.Counter(len(strokes) for _, strokes in candidates)
    return sorted(candidates, key=lambda item: (-counts[len(item[1])], ord(item[0][0])))


def _first_point(path_data: str) -> tuple[float, float]:
    match = _POINT.search(path_data)
    return (float(match.group(1)), float(match.group(2))) if match else (0.0, 0.0)


# ---------------------------------------------------------------------------


def connect(environment):
    return mysql.connector.connect(
        host=environment.get("DB_HOST", "localhost"),
        port=int(environment.get("DB_PORT", "3306")),
        database=environment["DB_NAME"],
        user=environment["DB_USERNAME"],
        password=environment["DB_PASSWORD"],
        charset="utf8mb4",
    )


def candidates(char, stroke_count, args, kanjivg, mmah, ids, component_index):
    """Yield (standard, svg text) for one character in priority order."""
    code = f"{ord(char):05x}"
    if char in kanjivg:
        archive, name = kanjivg[char]
        yield "kanjivg", archive.read(name).decode("utf-8")
    animcjk = {}
    for folder in ("svgsJa", "svgsZhHant", "svgsZhHans"):
        path = args.animcjk / folder / f"{ord(char)}.svg"
        if path.is_file():
            medians = animcjk_medians(path.read_text(encoding="utf-8"))
            if medians:
                animcjk[folder] = medians
    if "svgsJa" in animcjk:
        header = ANIMCJK_HEADER.format(char=char, code=code.upper(), folder="svgsJa",
                                       standard="ja (Japanese)")
        yield "ja", kanjivg_layout(char, animcjk["svgsJa"], header)
    if component_index:
        composed = compose(char, stroke_count, ids.get(char, []), component_index)
        if composed:
            yield "composed", composed
    for folder in ("svgsZhHant", "svgsZhHans"):
        if folder in animcjk:
            header = ANIMCJK_HEADER.format(char=char, code=code.upper(), folder=folder,
                                           standard="zh (Chinese)")
            yield "zh", kanjivg_layout(char, animcjk[folder], header)
    if char in mmah:
        yield "zh", kanjivg_layout(char, mmah[char], MMAH_HEADER.format(char=char, code=code.upper()))


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--animcjk", type=Path, required=True, help="AnimCJK repository folder")
    parser.add_argument("--mmah", type=Path, help="Make Me a Hanzi graphics.txt")
    parser.add_argument("--kanjivg-zip", type=Path, help="newer KanjiVG release ZIP")
    parser.add_argument("--kanjivg-dir", type=Path,
                        default=Path("src/main/resources/static/assets/kanjivg/kanji"),
                        help="KanjiVG SVG folder used as component donors")
    parser.add_argument("--cjkvi-ids", type=Path, help="cjkvi-ids ids.txt (component structure)")
    parser.add_argument("--kanjidict-vn", type=Path, help="KANJIDIC_vietnamese.zip (Shape field)")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--uri-prefix", default=DEFAULT_URI_PREFIX)
    parser.add_argument("--update-db", action="store_true")
    args = parser.parse_args(argv)

    environment = os.environ
    missing_env = [n for n in ("DB_NAME", "DB_USERNAME", "DB_PASSWORD") if not environment.get(n)]
    if missing_env:
        print(f"error: missing environment variable(s): {', '.join(missing_env)}", file=sys.stderr)
        return 2

    connection = connect(environment)
    cursor = connection.cursor()
    cursor.execute(
        "SELECT k.id, k.`character`, k.stroke_count FROM kanji k WHERE k.stroke_svg_uri IS NULL "
        "AND EXISTS (SELECT 1 FROM vocabulary_kanji vk WHERE vk.kanji_id = k.id) ORDER BY k.id"
    )
    targets = cursor.fetchall()

    kanjivg = kanjivg_zip_files(args.kanjivg_zip) if args.kanjivg_zip else {}
    mmah = load_mmah(args.mmah) if args.mmah else {}
    ids = load_ids(args.cjkvi_ids, args.kanjidict_vn)
    component_index = load_component_index(args.kanjivg_dir) if ids else {}
    args.out.mkdir(parents=True, exist_ok=True)

    built: dict[str, list[str]] = {"kanjivg": [], "ja": [], "composed": [], "zh": []}
    not_found = []
    mismatched = []
    updates = []
    for kanji_id, char, stroke_count in targets:
        accepted = None
        rejected = []
        for standard, text in candidates(char, stroke_count, args, kanjivg, mmah, ids,
                                         component_index):
            drawn = len(_KVG_STROKE.findall(text))
            if stroke_count and drawn != stroke_count:
                rejected.append(f"{standard}:{drawn}")
                continue
            accepted = (standard, text)
            break
        if accepted is None:
            (mismatched if rejected else not_found).append(
                f"{char}({','.join(rejected)}/{stroke_count})" if rejected else char
            )
            continue
        code = f"{ord(char):05x}"
        (args.out / f"{code}.svg").write_text(accepted[1], encoding="utf-8", newline="\n")
        built[accepted[0]].append(char)
        updates.append((f"{args.uri_prefix.rstrip('/')}/{code}.svg", kanji_id))

    if args.update_db and updates:
        cursor.executemany(
            "UPDATE kanji SET stroke_svg_uri = %s WHERE id = %s AND stroke_svg_uri IS NULL", updates
        )
        connection.commit()
    connection.close()

    print(json.dumps({
        "targets": len(targets),
        "built": {key: len(value) for key, value in built.items()},
        "composed": "".join(built["composed"]),
        "chinese_order": "".join(built["zh"]),
        "not_found": "".join(not_found),
        "stroke_count_mismatch": " ".join(mismatched),
        "db_updated": bool(args.update_db),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
