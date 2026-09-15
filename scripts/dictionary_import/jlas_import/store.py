"""Prepared MySQL persistence for dictionary import records."""

from __future__ import annotations

import re
import unicodedata
from typing import Any

from mysql.connector import errors as mysql_errors

from .models import (
    CatalogValue,
    ImportWarning,
    KanjiRecord,
    KanjiSvgRecord,
    VocabularyRecord,
)
from .normalization import (
    is_kanji_codepoint,
    normalize_reading,
    normalize_text,
    reading_to_romaji,
)
from .pipeline import ImportOutcome, ImportSummary


_SAVEPOINT_NAME = re.compile(r"^record_[0-9]+$")


def kanji_positions(expression: str) -> tuple[tuple[str, int], ...]:
    return tuple(
        (character, position)
        for position, character in enumerate(expression, start=1)
        if is_kanji_codepoint(character)
    )


def radical_character(number: int) -> str:
    if not 1 <= number <= 214:
        raise ValueError("radical number must be between 1 and 214")
    return unicodedata.normalize("NFKC", chr(0x2F00 + number - 1))


class MySqlDictionaryStore:
    def __init__(self, connection):
        self.connection = connection
        self._source_cache: dict[str, int] = {}
        self._pos_cache: dict[str, int] = {}
        self._domain_cache: dict[str, int] = {}
        self._kanji_character_cache: dict[str, int | None] = {}

    def require_active_source(self, code: str) -> int:
        source_code = normalize_text(code).upper()
        if source_code in self._source_cache:
            return self._source_cache[source_code]
        row = self._fetchone(
            "SELECT id, is_active FROM dictionary_source WHERE code = %s",
            (source_code,),
        )
        if row is None:
            raise ValueError(f"Dictionary source {source_code} does not exist")
        if not bool(row["is_active"]):
            raise ValueError(f"Dictionary source {source_code} is inactive")
        source_id = int(row["id"])
        self._source_cache[source_code] = source_id
        return source_id

    def find_completed_batch(
        self, source_id: int, version: str | None, checksum: str
    ) -> int | None:
        row = self._fetchone(
            "SELECT id FROM dictionary_import_batch "
            "WHERE source_id = %s AND source_version <=> %s "
            "AND source_checksum = %s AND status = 'COMPLETED' "
            "ORDER BY id DESC LIMIT 1",
            (source_id, version, checksum),
        )
        return int(row["id"]) if row else None

    def create_skipped_batch(
        self, source_id: int, version: str | None, checksum: str
    ) -> int:
        batch_id = self._insert(
            "INSERT INTO dictionary_import_batch "
            "(source_id, source_version, source_checksum, status, started_at, completed_at) "
            "VALUES (%s, %s, %s, 'SKIPPED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            (source_id, version, checksum),
        )
        self.connection.commit()
        return batch_id

    def start_batch(self, source_id: int, version: str | None, checksum: str) -> int:
        batch_id = self._insert(
            "INSERT INTO dictionary_import_batch "
            "(source_id, source_version, source_checksum, status, started_at) "
            "VALUES (%s, %s, %s, 'RUNNING', CURRENT_TIMESTAMP(6))",
            (source_id, version, checksum),
        )
        self.connection.commit()
        return batch_id

    def begin_dry_run(self) -> None:
        if not getattr(self.connection, "in_transaction", False):
            self.connection.start_transaction()

    @staticmethod
    def is_fatal_error(error: BaseException) -> bool:
        return isinstance(error, mysql_errors.InterfaceError) or getattr(
            error, "errno", None
        ) in {2006, 2013, 2055}

    def savepoint(self, name: str) -> str:
        if not _SAVEPOINT_NAME.fullmatch(name):
            raise ValueError("Unsafe savepoint name")
        self._execute(f"SAVEPOINT {name}")
        return name

    def rollback_to_savepoint(self, savepoint: str) -> None:
        if not _SAVEPOINT_NAME.fullmatch(savepoint):
            raise ValueError("Unsafe savepoint name")
        self._execute(f"ROLLBACK TO SAVEPOINT {savepoint}")
        self._clear_record_caches()

    def release_savepoint(self, savepoint: str) -> None:
        if not _SAVEPOINT_NAME.fullmatch(savepoint):
            raise ValueError("Unsafe savepoint name")
        self._execute(f"RELEASE SAVEPOINT {savepoint}")

    def commit_chunk(self, batch_id: int, summary: ImportSummary) -> None:
        self._update_batch(batch_id, summary, status="RUNNING")
        self.connection.commit()

    def rollback(self) -> None:
        self.connection.rollback()
        self._clear_record_caches()

    def finish_batch(
        self,
        batch_id: int,
        summary: ImportSummary,
        error_message: str | None = None,
    ) -> None:
        self._execute(
            "UPDATE dictionary_import_batch SET status = %s, completed_at = CURRENT_TIMESTAMP(6), "
            "total_records = %s, inserted_records = %s, updated_records = %s, "
            "skipped_records = %s, failed_records = %s, error_message = %s WHERE id = %s",
            (
                summary.status,
                summary.total,
                summary.inserted,
                summary.updated,
                summary.skipped,
                summary.failed,
                error_message,
                batch_id,
            ),
        )
        self.connection.commit()

    def _update_batch(
        self, batch_id: int, summary: ImportSummary, status: str
    ) -> None:
        self._execute(
            "UPDATE dictionary_import_batch SET status = %s, total_records = %s, "
            "inserted_records = %s, updated_records = %s, skipped_records = %s, "
            "failed_records = %s WHERE id = %s",
            (
                status,
                summary.total,
                summary.inserted,
                summary.updated,
                summary.skipped,
                summary.failed,
                batch_id,
            ),
        )

    def import_record(self, source_id: int, record) -> ImportOutcome:
        if isinstance(record, VocabularyRecord):
            return self._upsert_vocabulary(source_id, record)
        if isinstance(record, KanjiRecord):
            return self._upsert_kanji(record)
        if isinstance(record, KanjiSvgRecord):
            return self._update_kanji_svg(record)
        raise TypeError(f"Unsupported import record: {type(record).__name__}")

    def _upsert_vocabulary(
        self, source_id: int, record: VocabularyRecord
    ) -> ImportOutcome:
        normalized_expression = normalize_text(record.expression)
        normalized_reading = normalize_reading(record.reading)
        if not normalized_expression or not normalized_reading:
            raise ValueError("Vocabulary expression and reading are required")

        row = self._fetchone(
            "SELECT v.id, v.expression, v.reading, v.usage_scope "
            "FROM vocabulary_external_reference r "
            "JOIN vocabulary_entry v ON v.id = r.vocabulary_id "
            "WHERE r.source_id = %s AND r.external_entry_id = %s",
            (source_id, record.external_id),
        )
        if row is None:
            row = self._fetchone(
                "SELECT id, expression, reading, usage_scope FROM vocabulary_entry "
                "WHERE normalized_expression = %s AND normalized_reading = %s",
                (normalized_expression, normalized_reading),
            )

        inserted = row is None
        changed = False
        if inserted:
            vocabulary_id = self._insert(
                "INSERT INTO vocabulary_entry "
                "(expression, normalized_expression, reading, normalized_reading, "
                "reading_romaji, usage_scope, dictionary_status) "
                "VALUES (%s, %s, %s, %s, %s, 'DICTIONARY', 'PUBLISHED')",
                (
                    record.expression,
                    normalized_expression,
                    record.reading,
                    normalized_reading,
                    reading_to_romaji(record.reading),
                ),
            )
            changed = True
        else:
            vocabulary_id = int(row["id"])
            if row["usage_scope"] == "TEXTBOOK":
                changed |= self._execute(
                    "UPDATE vocabulary_entry SET usage_scope = 'BOTH' WHERE id = %s",
                    (vocabulary_id,),
                ) > 0
            elif row["usage_scope"] == "DICTIONARY" and (
                row["expression"] != record.expression or row["reading"] != record.reading
            ):
                changed |= self._execute(
                    "UPDATE vocabulary_entry SET expression = %s, normalized_expression = %s, "
                    "reading = %s, normalized_reading = %s, reading_romaji = %s "
                    "WHERE id = %s",
                    (
                        record.expression,
                        normalized_expression,
                        record.reading,
                        normalized_reading,
                        reading_to_romaji(record.reading),
                        vocabulary_id,
                    ),
                ) > 0

        changed |= self._execute(
            "INSERT INTO vocabulary_external_reference "
            "(vocabulary_id, source_id, external_entry_id, imported_at, updated_at) "
            "VALUES (%s, %s, %s, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)) "
            "ON DUPLICATE KEY UPDATE vocabulary_id = VALUES(vocabulary_id), "
            "updated_at = CURRENT_TIMESTAMP(6)",
            (vocabulary_id, source_id, record.external_id),
        ) > 0

        for variant in record.variants:
            normalized_variant = normalize_text(variant)
            if normalized_variant:
                changed |= self._execute(
                    "INSERT IGNORE INTO vocabulary_variant "
                    "(vocabulary_id, variant_expression, normalized_expression, variant_type) "
                    "VALUES (%s, %s, %s, 'KANJI_VARIANT')",
                    (vocabulary_id, variant, normalized_variant),
                ) > 0

        for alternate in record.alternate_readings:
            normalized_alternate = normalize_reading(alternate.reading)
            if normalized_alternate and normalized_alternate != normalized_reading:
                changed |= self._execute(
                    "INSERT IGNORE INTO vocabulary_reading "
                    "(vocabulary_id, reading, normalized_reading, reading_romaji, usage_note) "
                    "VALUES (%s, %s, %s, %s, %s)",
                    (
                        vocabulary_id,
                        alternate.reading,
                        normalized_alternate,
                        reading_to_romaji(alternate.reading),
                        alternate.usage_note,
                    ),
                ) > 0

        outcome_warnings: list[ImportWarning] = []
        warned_domains: set[str] = set()
        for sense in record.senses:
            sense_row = self._fetchone(
                "SELECT id, usage_note FROM vocabulary_sense "
                "WHERE vocabulary_id = %s AND sense_order = %s",
                (vocabulary_id, sense.order),
            )
            if sense_row is None:
                sense_id = self._insert(
                    "INSERT INTO vocabulary_sense (vocabulary_id, sense_order, usage_note) "
                    "VALUES (%s, %s, %s)",
                    (vocabulary_id, sense.order, sense.usage_note),
                )
                changed = True
            else:
                sense_id = int(sense_row["id"])
                if not sense_row["usage_note"] and sense.usage_note:
                    changed |= self._execute(
                        "UPDATE vocabulary_sense SET usage_note = %s WHERE id = %s",
                        (sense.usage_note, sense_id),
                    ) > 0

            for gloss in sense.glosses:
                if not self._fetchone(
                    "SELECT id FROM vocabulary_gloss WHERE sense_id = %s "
                    "AND language_code = %s AND gloss_text = %s LIMIT 1",
                    (sense_id, gloss.language_code, gloss.text),
                ):
                    self._insert(
                        "INSERT INTO vocabulary_gloss (sense_id, language_code, gloss_text) "
                        "VALUES (%s, %s, %s)",
                        (sense_id, gloss.language_code, gloss.text),
                    )
                    changed = True

            for value in sense.parts_of_speech:
                catalog_id, catalog_changed = self._catalog_id(
                    "dictionary_part_of_speech", self._pos_cache, value
                )
                changed |= catalog_changed
                changed |= self._execute(
                    "INSERT IGNORE INTO sense_part_of_speech (sense_id, part_of_speech_id) "
                    "VALUES (%s, %s)",
                    (sense_id, catalog_id),
                ) > 0

            for value in sense.domains:
                catalog_id, catalog_changed = self._catalog_id(
                    "dictionary_domain", self._domain_cache, value
                )
                changed |= catalog_changed
                changed |= self._execute(
                    "INSERT IGNORE INTO sense_domain (sense_id, domain_id) VALUES (%s, %s)",
                    (sense_id, catalog_id),
                ) > 0
                if catalog_changed and value.code not in warned_domains:
                    warned_domains.add(value.code)
                    outcome_warnings.append(
                        ImportWarning(
                            "DOMAIN_NAME_VI_FALLBACK",
                            f"Domain {value.code} uses its English name or code as name_vi",
                        )
                    )

        for character, position in kanji_positions(record.expression):
            kanji_id = self._find_kanji_by_character(character)
            if kanji_id is not None:
                changed |= self._execute(
                    "INSERT IGNORE INTO vocabulary_kanji "
                    "(vocabulary_id, kanji_id, character_position) VALUES (%s, %s, %s)",
                    (vocabulary_id, kanji_id, position),
                ) > 0

        action = "inserted" if inserted else "updated" if changed else "skipped"
        return ImportOutcome(action, tuple(outcome_warnings))

    def _catalog_id(
        self, table: str, cache: dict[str, int], value: CatalogValue
    ) -> tuple[int, bool]:
        if value.code in cache:
            return cache[value.code], False
        row = self._fetchone(f"SELECT id FROM {table} WHERE code = %s", (value.code,))
        changed = False
        if row is None:
            fallback_name = value.name_en or value.code
            if table == "dictionary_domain":
                catalog_id = self._insert(
                    "INSERT INTO dictionary_domain "
                    "(code, name_vi, name_en, is_active, display_order) "
                    "VALUES (%s, %s, %s, 1, 0)",
                    (value.code, fallback_name, value.name_en),
                )
            else:
                catalog_id = self._insert(
                    "INSERT INTO dictionary_part_of_speech (code, name_vi, name_en) "
                    "VALUES (%s, %s, %s)",
                    (value.code, fallback_name, value.name_en),
                )
            changed = True
        else:
            catalog_id = int(row["id"])
        cache[value.code] = catalog_id
        return catalog_id, changed

    def _upsert_kanji(self, record: KanjiRecord) -> ImportOutcome:
        radical_id = None
        changed = False
        if record.radical_number is not None:
            radical = radical_character(record.radical_number)
            changed |= self._execute(
                "INSERT IGNORE INTO radical (radical_number, `character`) VALUES (%s, %s)",
                (record.radical_number, radical),
            ) > 0
            radical_row = self._fetchone(
                "SELECT id FROM radical WHERE radical_number = %s",
                (record.radical_number,),
            )
            radical_id = int(radical_row["id"])

        row = self._fetchone(
            "SELECT id, `character`, unicode_code, radical_id, stroke_count "
            "FROM kanji WHERE unicode_code = %s OR `character` = %s LIMIT 1",
            (record.unicode_code, record.literal),
        )
        inserted = row is None
        if inserted:
            kanji_id = self._insert(
                "INSERT INTO kanji (`character`, unicode_code, radical_id, stroke_count) "
                "VALUES (%s, %s, %s, %s)",
                (
                    record.literal,
                    record.unicode_code,
                    radical_id,
                    record.stroke_count,
                ),
            )
            changed = True
        else:
            kanji_id = int(row["id"])
            desired = (
                record.literal,
                record.unicode_code,
                radical_id,
                record.stroke_count,
            )
            existing = (
                row["character"],
                row["unicode_code"],
                row["radical_id"],
                row["stroke_count"],
            )
            if existing != desired:
                changed |= self._execute(
                    "UPDATE kanji SET `character` = %s, unicode_code = %s, "
                    "radical_id = %s, stroke_count = %s WHERE id = %s",
                    (*desired, kanji_id),
                ) > 0

        self._kanji_character_cache[record.literal] = kanji_id
        for reading in record.readings:
            if reading.text:
                changed |= self._execute(
                    "INSERT IGNORE INTO kanji_reading "
                    "(kanji_id, reading_type, reading_text, is_common) "
                    "VALUES (%s, %s, %s, 0)",
                    (kanji_id, reading.kind, reading.text),
                ) > 0
        action = "inserted" if inserted else "updated" if changed else "skipped"
        return ImportOutcome(action)

    def _update_kanji_svg(self, record: KanjiSvgRecord) -> ImportOutcome:
        row = self._fetchone(
            "SELECT id, stroke_svg_uri FROM kanji WHERE unicode_code = %s",
            (record.unicode_code,),
        )
        if row is None:
            return ImportOutcome(
                "skipped",
                (
                    ImportWarning(
                        "KANJIVG_KANJI_NOT_FOUND",
                        f"No Kanji row matches Unicode {record.unicode_code}",
                    ),
                ),
            )
        if row["stroke_svg_uri"] == record.svg_uri:
            return ImportOutcome("skipped")
        self._execute(
            "UPDATE kanji SET stroke_svg_uri = %s WHERE id = %s",
            (record.svg_uri, int(row["id"])),
        )
        return ImportOutcome("updated")

    def _find_kanji_by_character(self, character: str) -> int | None:
        if character in self._kanji_character_cache:
            return self._kanji_character_cache[character]
        row = self._fetchone(
            "SELECT id FROM kanji WHERE `character` = %s", (character,)
        )
        kanji_id = int(row["id"]) if row else None
        self._kanji_character_cache[character] = kanji_id
        return kanji_id

    def _fetchone(self, sql: str, parameters: tuple[Any, ...] = ()):
        cursor = self.connection.cursor(dictionary=True)
        try:
            cursor.execute(sql, parameters)
            return cursor.fetchone()
        finally:
            cursor.close()

    def _execute(self, sql: str, parameters: tuple[Any, ...] = ()) -> int:
        cursor = self.connection.cursor()
        try:
            cursor.execute(sql, parameters)
            return cursor.rowcount
        finally:
            cursor.close()

    def _insert(self, sql: str, parameters: tuple[Any, ...]) -> int:
        cursor = self.connection.cursor()
        try:
            cursor.execute(sql, parameters)
            return int(cursor.lastrowid)
        finally:
            cursor.close()

    def _clear_record_caches(self) -> None:
        self._pos_cache.clear()
        self._domain_cache.clear()
        self._kanji_character_cache.clear()
