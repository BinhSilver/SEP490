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
    RecordValidationError,
    VocabularyReadingRecord,
    VocabularyRecord,
    VocabularySenseRecord,
)
from .normalization import normalize_text


_XML_LANG = "{http://www.w3.org/XML/1998/namespace}lang"
_ENTITY_DECLARATION = re.compile(r'<!ENTITY\s+([^\s]+)\s+["\']([^"\']*)["\']\s*>')


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
