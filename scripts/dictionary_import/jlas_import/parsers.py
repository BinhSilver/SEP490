"""Streaming parsers for the supported dictionary and Kanji datasets."""

from __future__ import annotations

import re
from pathlib import Path
from typing import Iterator

from lxml import etree

from .models import (
    CatalogValue,
    GlossRecord,
    ImportWarning,
    InvalidRecord,
    KanjiReadingRecord,
    KanjiRecord,
    KanjiSvgRecord,
    RecordValidationError,
    VocabularyReadingRecord,
    VocabularyRecord,
    VocabularySenseRecord,
)
from .normalization import normalize_text


_XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"
_ENTITY_DECLARATION = re.compile(r'<!ENTITY\s+([^\s]+)\s+["\']([^"\']*)["\']\s*>')
_KANJIVG_FILENAME = re.compile(r"^([0-9a-fA-F]{5})\.svg$")
_KANJIVG_ID = re.compile(r"_([0-9a-fA-F]{4,6})(?:$|[^0-9a-fA-F])")
_JOYO_GRADES = {1, 2, 3, 4, 5, 6, 8}


def iter_jmdict(path: str | Path) -> Iterator[VocabularyRecord | InvalidRecord]:
    """Yield one normalized JMdict entry at a time and release its XML tree."""

    source_path = Path(path)
    entity_names = _read_internal_entities(source_path)
    context = etree.iterparse(
        str(source_path),
        events=("end",),
        tag="entry",
        load_dtd=True,
        resolve_entities=False,
        no_network=True,
        huge_tree=True,
    )
    entry_number = 0
    for _, element in context:
        entry_number += 1
        fallback_id = _text(element.find("ent_seq")) or f"entry-{entry_number}"
        try:
            record: VocabularyRecord | InvalidRecord = _parse_jmdict_entry(
                element, entity_names, fallback_id
            )
        except RecordValidationError as error:
            record = InvalidRecord(error.record_id, str(error))
        yield record
        _release_element(element)


def iter_kanjidic2(path: str | Path) -> Iterator[KanjiRecord | InvalidRecord]:
    """Yield the Joyo subset of KANJIDIC2 without retaining the full XML tree."""

    context = etree.iterparse(
        str(Path(path)), events=("end",), tag="character", no_network=True, huge_tree=True
    )
    character_number = 0
    for _, element in context:
        character_number += 1
        grade = _positive_int(_text(element.find("misc/grade")))
        if grade not in _JOYO_GRADES:
            _release_element(element)
            continue
        fallback_id = _text(element.find("literal")) or f"character-{character_number}"
        try:
            record: KanjiRecord | InvalidRecord = _parse_kanjidic2_character(
                element, fallback_id
            )
        except RecordValidationError as error:
            record = InvalidRecord(error.record_id, str(error))
        yield record
        _release_element(element)


def _parse_kanjidic2_character(
    element: etree._Element, fallback_id: str
) -> KanjiRecord:
    literal = _text(element.find("literal"))
    if len(literal) != 1:
        raise RecordValidationError(fallback_id, "KANJIDIC2 literal must be one character")

    unicode_value = next(
        (
            _text(candidate)
            for candidate in element.findall("codepoint/cp_value")
            if candidate.get("cp_type") == "ucs" and _text(candidate)
        ),
        "",
    )
    try:
        unicode_code = f"{int(unicode_value, 16):X}"
    except ValueError as error:
        raise RecordValidationError(literal, "KANJIDIC2 UCS code is required") from error

    radical_number = next(
        (
            _positive_int(_text(candidate))
            for candidate in element.findall("radical/rad_value")
            if candidate.get("rad_type") == "classical"
        ),
        None,
    )
    stroke_count = next(
        (
            value
            for value in (
                _positive_int(_text(candidate))
                for candidate in element.findall("misc/stroke_count")
            )
            if value is not None
        ),
        None,
    )

    readings: list[KanjiReadingRecord] = []
    seen: set[tuple[str, str]] = set()
    reading_types = {"ja_on": "ON", "ja_kun": "KUN"}
    for candidate in element.findall("reading_meaning/rmgroup/reading"):
        kind = reading_types.get(candidate.get("r_type", ""))
        text = _text(candidate)
        if kind and text and (kind, text) not in seen:
            seen.add((kind, text))
            readings.append(KanjiReadingRecord(kind=kind, text=text))
    for candidate in element.findall("reading_meaning/nanori"):
        text = _text(candidate)
        if text and ("NANORI", text) not in seen:
            seen.add(("NANORI", text))
            readings.append(KanjiReadingRecord(kind="NANORI", text=text))

    return KanjiRecord(
        literal=literal,
        unicode_code=unicode_code,
        stroke_count=stroke_count,
        radical_number=radical_number,
        readings=tuple(readings),
    )


def iter_kanjivg(
    path: str | Path, uri_prefix: str
) -> Iterator[KanjiSvgRecord | InvalidRecord]:
    """Yield stable URI mappings from one KanjiVG SVG or a directory tree."""

    source_path = Path(path)
    files = [source_path] if source_path.is_file() else sorted(source_path.rglob("*.svg"))
    prefix = uri_prefix.rstrip("/")
    for svg_file in files:
        try:
            unicode_code = _kanjivg_unicode_code(svg_file)
            uri = f"{prefix}/{svg_file.name}" if prefix else svg_file.name
            yield KanjiSvgRecord(unicode_code=unicode_code, svg_uri=uri)
        except (OSError, etree.XMLSyntaxError, RecordValidationError) as error:
            yield InvalidRecord(svg_file.name, str(error))


def _kanjivg_unicode_code(path: Path) -> str:
    filename_match = _KANJIVG_FILENAME.match(path.name)
    if filename_match:
        return f"{int(filename_match.group(1), 16):X}"

    root = etree.parse(str(path)).getroot()
    for element in root.iter():
        element_id = element.get("id", "")
        id_match = _KANJIVG_ID.search(element_id)
        if id_match:
            return f"{int(id_match.group(1), 16):X}"
        literal = element.get("{http://kanjivg.tagaini.net}element", "")
        if len(literal) == 1:
            return f"{ord(literal):X}"
    raise RecordValidationError(path.name, "KanjiVG Unicode code point is required")


def _parse_jmdict_entry(
    element: etree._Element,
    entity_names: dict[str, str],
    fallback_id: str,
) -> VocabularyRecord:
    external_id = _text(element.find("ent_seq"))
    if not external_id:
        raise RecordValidationError(fallback_id, "JMdict external ID is required")

    written_forms = _non_empty_texts(element.findall("k_ele/keb"))
    raw_readings: list[tuple[str, tuple[str, ...]]] = []
    for reading_element in element.findall("r_ele"):
        reading = _text(reading_element.find("reb"))
        if reading:
            raw_readings.append(
                (reading, tuple(_non_empty_texts(reading_element.findall("re_restr"))))
            )
    if not raw_readings:
        raise RecordValidationError(external_id, "JMdict reading is required")

    expression = written_forms[0] if written_forms else raw_readings[0][0]
    primary_index = next(
        (
            index
            for index, (_, restrictions) in enumerate(raw_readings)
            if not restrictions or expression in restrictions
        ),
        0,
    )
    reading = raw_readings[primary_index][0]

    warnings: list[ImportWarning] = []
    alternate_readings: list[VocabularyReadingRecord] = []
    has_reading_restriction = False
    for index, (alternate, restrictions) in enumerate(raw_readings):
        if restrictions:
            has_reading_restriction = True
        if index != primary_index:
            alternate_readings.append(
                VocabularyReadingRecord(
                    reading=alternate,
                    usage_note=_restriction_note("written forms", restrictions),
                )
            )
    if has_reading_restriction:
        warnings.append(
            ImportWarning(
                "JMDICT_READING_RESTRICTION",
                "Reading restrictions are retained as usage notes, not typed relations",
            )
        )

    senses: list[VocabularySenseRecord] = []
    has_sense_restriction = False
    for order, sense_element in enumerate(element.findall("sense"), start=1):
        written_restrictions = tuple(_non_empty_texts(sense_element.findall("stagk")))
        reading_restrictions = tuple(_non_empty_texts(sense_element.findall("stagr")))
        notes = tuple(
            note
            for note in (
                _restriction_note("written forms", written_restrictions),
                _restriction_note("readings", reading_restrictions),
            )
            if note
        )
        if notes:
            has_sense_restriction = True
        glosses = tuple(
            GlossRecord(
                language_code=gloss.get(_XML_LANG, "eng").lower(),
                text=_text(gloss),
            )
            for gloss in sense_element.findall("gloss")
            if _text(gloss)
        )
        senses.append(
            VocabularySenseRecord(
                order=order,
                usage_note="; ".join(notes) or None,
                glosses=glosses,
                parts_of_speech=_catalog_values(
                    sense_element.findall("pos"), entity_names
                ),
                domains=_catalog_values(sense_element.findall("field"), entity_names),
            )
        )
    if has_sense_restriction:
        warnings.append(
            ImportWarning(
                "JMDICT_SENSE_RESTRICTION",
                "Sense restrictions are retained as usage notes, not typed relations",
            )
        )

    return VocabularyRecord(
        external_id=external_id,
        expression=expression,
        reading=reading,
        variants=tuple(form for form in written_forms if form != expression),
        alternate_readings=tuple(alternate_readings),
        senses=tuple(senses),
        warnings=tuple(warnings),
    )


def _catalog_values(
    elements: list[etree._Element], entity_names: dict[str, str]
) -> tuple[CatalogValue, ...]:
    values: list[CatalogValue] = []
    seen: set[str] = set()
    for element in elements:
        entity = next(
            (child for child in element if isinstance(child, etree._Entity)), None
        )
        if entity is not None:
            code = entity.name
            name_en = entity_names.get(code)
        else:
            name_en = _text(element)
            code = _catalog_fallback_code(name_en)
        if code and code not in seen:
            seen.add(code)
            values.append(CatalogValue(code=code, name_en=name_en or None))
    return tuple(values)


def _catalog_fallback_code(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")[:80]


def _read_internal_entities(path: Path) -> dict[str, str]:
    entities: dict[str, str] = {}
    with path.open("r", encoding="utf-8", errors="replace") as stream:
        for line in stream:
            for name, value in _ENTITY_DECLARATION.findall(line):
                entities[name] = normalize_text(value)
            if "]>" in line or ("<JMdict" in line and "<!DOCTYPE" not in line):
                break
    return entities


def _restriction_note(label: str, values: tuple[str, ...]) -> str | None:
    return f"{label}: {', '.join(values)}" if values else None


def _non_empty_texts(elements: list[etree._Element]) -> list[str]:
    return [value for value in (_text(element) for element in elements) if value]


def _text(element: etree._Element | None) -> str:
    if element is None:
        return ""
    return normalize_text("".join(element.itertext()))


def _release_element(element: etree._Element) -> None:
    element.clear()
    parent = element.getparent()
    while parent is not None and element.getprevious() is not None:
        del parent[0]


def _positive_int(value: str) -> int | None:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed > 0 else None
