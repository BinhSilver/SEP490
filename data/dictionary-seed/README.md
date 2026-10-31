# J-LAS Dictionary Test Snapshot

This directory contains a versioned test snapshot distributed through Git LFS.
It lets another developer restore the complete Japanese-Vietnamese
Dictionary/Kanji dataset without rerunning the imports in
`docs/dictionary-import.md` (Mazii vocabulary, KANJIDIC2, KanjiDictVN, KanjiVG).

Snapshot `JLAS2-34-ja-vi-2026-10-16`: 278,131 Japanese-Vietnamese words with Han
Viet, 13,033 Kanji (all KANJIDIC2 characters, Vietnamese readings/meanings),
40,397 Kanji readings, 505,412 word-Kanji links, 253 radical lookup components
with 54,321 Kanji-component links (RADKFILE). Migrations
`sql/migrations/059_vietnamese_dictionary.sql` and `060_kanji_component_lookup.sql`
are already part of the schema.

## Included artifacts

- `jlas-dictionary-test-seed-20261016.sql.gz`: all application table
  definitions plus data for only the 18 Dictionary/Kanji tables listed in
  `manifest.json`. It contains no account, profile, user progress, content, or
  other business-table rows.
- `kanjivg-r20250816.zip`: 11,661 KanjiVG SVG files under `kanji/`.
  Stroke-order files for 113 rarer Kanji that KanjiVG lacks are committed in
  `src/main/resources/static/assets/stroke-order-extra` (no extraction needed).
- `manifest.json`: artifact checksums, byte sizes, source versions, and exact
  expected database row counts.

## One-time setup

Install Git LFS before cloning, or fetch the artifacts after cloning:

```powershell
git lfs install
git lfs pull
```

Install the existing importer dependencies:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r scripts/dictionary_import/requirements.txt
```

Set local MySQL credentials and run the bootstrap command:

```powershell
$env:DB_HOST = 'localhost'
$env:DB_PORT = '3306'
$env:DB_USERNAME = 'root'
$env:DB_PASSWORD = '<local password>'

.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py
```

The default database is `jlas_dictionary_test`. The command verifies both LFS
artifacts, creates that database, restores the snapshot, verifies every table
count, and extracts SVG files to
`src/main/resources/static/assets/kanjivg/kanji`.

Run the application against the restored database:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:mysql://localhost:3306/jlas_dictionary_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh'
$env:SPRING_DATASOURCE_USERNAME = $env:DB_USERNAME
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
mvn spring-boot:run
```

If `mysql` is not on `PATH`, pass its full location:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py `
  --mysql-bin 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
```

The command refuses database names not ending in `_test`. It also refuses a
partially populated database. To deliberately rebuild only the selected test
database, use `--replace`; this drops and recreates that `_test` database.

### Linux/macOS (Bash)

```bash
git lfs install
git lfs pull
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/dictionary_import/requirements.txt

export DB_HOST=localhost
export DB_PORT=3306
export DB_USERNAME=root
export DB_PASSWORD='<local password>'
python scripts/dictionary_import/seed_bootstrap.py

export SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/jlas_dictionary_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Ho_Chi_Minh'
export SPRING_DATASOURCE_USERNAME="$DB_USERNAME"
export SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD"
./mvnw spring-boot:run
```

## Regenerating the snapshot

After changing the dictionary data in `jlas_dictionary_test`, export a new
snapshot (writes the dated `.sql.gz`, removes the previous one and rewrites
`manifest.json`), then verify it in a scratch database:

```powershell
.\.venv\Scripts\python.exe scripts/dictionary_import/export_seed.py --snapshot <label>
.\.venv\Scripts\python.exe scripts/dictionary_import/seed_bootstrap.py --database jlas_seedcheck_test
```

## Licensing and attribution

KANJIDIC2, RADKFILE and the JMdict part-of-speech tags are distributed by the Electronic
Dictionary Research and Development Group under the EDRDG General Dictionary
Licence / CC BY-SA 4.0. KanjiVG is copyright Ulrich Apel and distributed under
CC BY-SA 3.0. The Mazii-derived vocabulary
(<https://github.com/Buiduyanh20/yomitan-japanese-vietnamese-dictionary>) and
KanjiDictVN (<https://github.com/trungnt2910/KanjiDictVN>, readings/meanings
from hvdic.thivien.net) publish no explicit licence: confirm redistribution
rights before a public release.

- <https://www.edrdg.org/wiki/KANJIDIC_Project.html>
- <https://github.com/KanjiVG/kanjivg>
