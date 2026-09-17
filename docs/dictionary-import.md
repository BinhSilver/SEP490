# Dictionary and Kanji Initial Import

This command imports dictionary and Kanji base data into the existing J-LAS
MySQL schema. It is run explicitly by an operator. It is not registered as a
Spring `ApplicationRunner`, `CommandLineRunner`, request handler, or scheduled
job.

## Official data sources and attribution

Use only the official distributions:

- [JMdict project and download documentation](https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html),
  with the full multilingual file at
  [JMdict.gz](https://www.edrdg.org/pub/Nihongo/JMdict.gz). Do not use
  `JMdict_e.gz`, because that edition contains only English glosses.
- [KANJIDIC2 project](https://www.edrdg.org/wiki/KANJIDIC_Project.html) and
  [kanjidic2.xml.gz](https://www.edrdg.org/kanjidic/kanjidic2.xml.gz).
- [KanjiVG files and format](https://kanjivg.tagaini.net/files.html) and the
  [official release archives](https://github.com/KanjiVG/kanjivg/releases).

JMdict and KANJIDIC2 are distributed under the
[EDRDG General Dictionary Licence](https://www.edrdg.org/edrdg/licence.html),
which applies Creative Commons Attribution-ShareAlike 4.0 and requires source
acknowledgement. KanjiVG is copyright Ulrich Apel and distributed under
[Creative Commons Attribution-ShareAlike 3.0](https://kanjivg.tagaini.net/).

The product must retain the required acknowledgements in its documentation or
source/about surface. The EDRDG licence also requires a procedure for keeping
distributed dictionary data current. This task deliberately adds no scheduler;
an operator can download a current file and rerun the idempotent command as the
manual update procedure. For a published product using EDRDG data, schedule
that manual operational procedure at least monthly as required by the licence;
this remains an operator command rather than an application cron job.

Do not scrape Mazii, Jisho, or commercial dictionaries. Vietnamese glosses may
only come from a separate reviewed J-LAS dataset; this importer does not invent
or machine-translate them.

## Download and unpack

Example PowerShell commands:

```powershell
New-Item -ItemType Directory -Force data | Out-Null
Invoke-WebRequest https://www.edrdg.org/pub/Nihongo/JMdict.gz -OutFile data/JMdict.gz
Invoke-WebRequest https://www.edrdg.org/kanjidic/kanjidic2.xml.gz -OutFile data/kanjidic2.xml.gz
```

The parsers accept uncompressed XML. Decompress without committing the large
datasets:

```powershell
python -c "import gzip,shutil; i=gzip.open('data/JMdict.gz','rb'); o=open('data/JMdict.xml','wb'); shutil.copyfileobj(i,o); i.close(); o.close()"
python -c "import gzip,shutil; i=gzip.open('data/kanjidic2.xml.gz','rb'); o=open('data/kanjidic2.xml','wb'); shutil.copyfileobj(i,o); i.close(); o.close()"
```

Download and extract a KanjiVG release, then pass its `kanji` directory. The
importer stores the configured URI plus each SVG filename; it never stores SVG
bodies in MySQL. Production deployment must serve/copy those SVG files at the
same stable URI. Do not commit the complete upstream datasets to this repository.

## Python setup

Python 3.11 or newer is recommended.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r scripts/dictionary_import/requirements.txt
```

## MySQL configuration

Set connection values in environment variables. Do not create or commit a
`.env` file containing credentials.

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_NAME = 'jlas'
$env:DB_USERNAME = 'jlas_importer'
$env:DB_PASSWORD = '<local secret>'
```

The MySQL account needs `SELECT`, `INSERT`, and `UPDATE` only for the
tables involved in import. Schema application requires a separate account with
DDL permission.

For a new database, apply `jlas_schema.sql`. For an existing database that
already has `vocabulary_entry`, apply only the additive migration:

```text
mysql --default-character-set=utf8mb4 -u <schema-user> -p < jlas_schema.sql
mysql --default-character-set=utf8mb4 -u <schema-user> -p jlas < sql/migrations/004_dictionary_import_metadata.sql
```

The migration adds exactly three tables: `dictionary_source`,
`dictionary_import_batch`, and `vocabulary_external_reference`. It also inserts
active JMDICT, KANJIDIC2, and KANJIVG source rows if absent.

## Import commands

Import KANJIDIC2 first so vocabulary-to-Kanji links can be created, then JMdict,
then KanjiVG:

```powershell
python scripts/dictionary_import/import_data.py --source kanjidic2 --file data/kanjidic2.xml --source-version 2026-09 --chunk-size 500

python scripts/dictionary_import/import_data.py --source jmdict --file data/JMdict.xml --source-version 2026-09 --chunk-size 500

python scripts/dictionary_import/import_data.py --source kanjivg --file data/kanjivg-20250816/kanji --source-version r20250816 --svg-uri-prefix /assets/kanjivg/kanji --chunk-size 500
```

Use the actual dataset generation date/release tag as `--source-version`. When
omitted, the input filename or directory name is recorded.

KANJIDIC2 imports only Jōyō grade values 1–6 and 8. It ignores the legacy
KANJIDIC2 JLPT field. JMdict imports every available gloss language and every
POS/domain entity in the file. Missing Vietnamese domain names use the English
label or code and emit `DOMAIN_NAME_VI_FALLBACK`.

## Dry-run

Dry-run still connects to MySQL so it can validate the source and evaluate
deduplication, but it rolls the transaction back and creates no import batch:

```powershell
python scripts/dictionary_import/import_data.py --source jmdict --file data/JMdict.xml --source-version 2026-09 --dry-run --report-file dictionary-import-report-dry-run.jsonl
```

## Summary and statuses

The command prints one JSON summary:

```json
{"batch_id": 15, "status": "COMPLETED", "total_records": 6, "inserted_records": 5, "updated_records": 1, "skipped_records": 0, "failed_records": 0, "report_file": "dictionary-import-report.jsonl", "dry_run": false}
```

Counters are per source record, not per SQL row:

- `inserted_records`: a new primary vocabulary/Kanji row was created;
- `updated_records`: an existing row or one of its relationships changed;
- `skipped_records`: the record required no change or KanjiVG had no matching
  Kanji;
- `failed_records`: the record was rolled back to its savepoint.

Final statuses are `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, or `SKIPPED`.
`RUNNING` is visible while a batch is active. A byte-identical source/version/
checksum after a completed batch creates a new zero-counter `SKIPPED` audit row
and performs no dictionary mutation.

## Error handling and safe reruns

Warnings and record failures are written as UTF-8 JSON Lines. Each line has
`batch_id`, `source`, `record_id`, `severity`, `code`, and a sanitized message.
Passwords, tokens, and similar values are redacted. The database batch field
contains only a bounded fatal summary, never a stack trace.

Reports are append-only, so a rerun does not erase earlier evidence. Prefer a
different `--report-file` for each source/run for easier review. The CLI rejects
a report path equal to an input file or located inside a KanjiVG input directory,
because either choice could corrupt the source or change its checksum. A source
that yields zero importable records fails instead of producing a misleading
zero-record `COMPLETED` batch.

Fix the source record or normalization rule, choose an accurate version, and
rerun the command. Stable external IDs and existing unique keys prevent
duplicates. When a changed upstream file removes a sense/gloss, this importer
does not delete it automatically because the current child tables do not carry
per-source provenance and may contain reviewed J-LAS data.

JMdict reading/sense restrictions are retained in usage-note columns and emit
warnings because the current schema cannot model those restrictions as typed
relations without adding out-of-scope tables.

## Automated tests

Run all database-independent tests:

```powershell
python -m unittest discover -s scripts/dictionary_import/tests -v
```

The MySQL integration test is opt-in and refuses a database name that does not
end in `_test`:

```powershell
$env:JLAS_IMPORT_TEST_MYSQL = '1'
$env:JLAS_IMPORT_TEST_DB_HOST = 'localhost'
$env:JLAS_IMPORT_TEST_DB_PORT = '3306'
$env:JLAS_IMPORT_TEST_DB_NAME = 'jlas_import_test'
$env:JLAS_IMPORT_TEST_DB_USERNAME = 'jlas_test'
$env:JLAS_IMPORT_TEST_DB_PASSWORD = '<test secret>'
python -m unittest scripts.dictionary_import.tests.test_mysql_integration -v
```

Run the Java/schema tests and application build as well:

```powershell
mvn test
```

At normal Spring Boot startup, no importer runs. Dictionary runtime behavior
reads the imported MySQL tables and does not call JMdict, KANJIDIC2, KanjiVG,
or any other external dictionary service.

## Fast test bootstrap from Git LFS

Developers who only need a complete test database do not need to rerun the
full upstream import. The repository tracks a compressed, versioned snapshot
and KanjiVG archive through Git LFS:

```powershell
git lfs install
git lfs pull
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<local password>'
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py
```

Linux/macOS (Bash):

```bash
git lfs install
git lfs pull
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/dictionary_import/requirements.txt
export DB_USERNAME=root
export DB_PASSWORD='<local password>'
python scripts/dictionary_import/seed_bootstrap.py
```

The command only accepts a database ending in `_test` (default:
`jlas_dictionary_test`), verifies SHA-256 and byte sizes, restores all schema
definitions plus only Dictionary/Kanji data, verifies exact row counts, and
extracts 11,661 KanjiVG SVG files. It is idempotent when the installed counts
match. A partial database is rejected; `--replace` is an explicit destructive
rebuild of only the selected `_test` database. See
[`data/dictionary-seed/README.md`](../data/dictionary-seed/README.md).
