"""Database-independent records emitted by dictionary source parsers."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class CatalogValue:
    code: str
    name_en: str | None = None


@dataclass(frozen=True, slots=True)
class GlossRecord:
    language_code: str
    text: str


@dataclass(frozen=True, slots=True)
class VocabularyReadingRecord:
    reading: str
    usage_note: str | None = None


@dataclass(frozen=True, slots=True)
class VocabularySenseRecord:
    order: int
    usage_note: str | None = None
    glosses: tuple[GlossRecord, ...] = ()
    parts_of_speech: tuple[CatalogValue, ...] = ()
    domains: tuple[CatalogValue, ...] = ()


@dataclass(frozen=True, slots=True)
class VocabularyRecord:
    external_id: str
    expression: str
    reading: str
    variants: tuple[str, ...] = ()
    alternate_readings: tuple[VocabularyReadingRecord, ...] = ()
    senses: tuple[VocabularySenseRecord, ...] = ()
    warnings: tuple["ImportWarning", ...] = ()

    @property
    def record_id(self) -> str:
        return self.external_id


@dataclass(frozen=True, slots=True)
class KanjiReadingRecord:
    kind: str
    text: str


@dataclass(frozen=True, slots=True)
class KanjiRecord:
    literal: str
    unicode_code: str
    stroke_count: int | None
    radical_number: int | None
    readings: tuple[KanjiReadingRecord, ...] = ()
    jlpt_level: None = field(default=None, init=False)
    warnings: tuple["ImportWarning", ...] = ()

    @property
    def record_id(self) -> str:
        return self.unicode_code


@dataclass(frozen=True, slots=True)
class KanjiSvgRecord:
    unicode_code: str
    svg_uri: str
    warnings: tuple["ImportWarning", ...] = ()

    @property
    def record_id(self) -> str:
        return self.unicode_code


@dataclass(frozen=True, slots=True)
class ImportWarning:
    code: str
    message: str


class RecordValidationError(ValueError):
    """A recoverable source-record error with a traceable source identifier."""

    def __init__(self, record_id: str, message: str):
        super().__init__(message)
        self.record_id = record_id

