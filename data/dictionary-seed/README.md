# J-LAS Dictionary Test Snapshot

This directory contains a versioned test snapshot distributed through Git LFS.
It lets another developer restore the complete Dictionary/Kanji dataset without
running the approximately 40-minute JMdict import.

## Included artifacts

- `jlas-dictionary-test-seed-20260916.sql.gz`: all application table
  definitions plus data for only the 16 Dictionary/Kanji tables listed in
  `manifest.json`. It contains no account, profile, user progress, content, or
  other business-table rows.
- `kanjivg-r20250816.zip`: 11,661 KanjiVG SVG files under `kanji/`.
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

## Licensing and attribution

JMdict and KANJIDIC2 are distributed by the Electronic Dictionary Research and
Development Group under the EDRDG General Dictionary Licence / CC BY-SA 4.0.
KanjiVG is copyright Ulrich Apel and distributed under CC BY-SA 3.0.

- <https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html>
- <https://www.edrdg.org/wiki/KANJIDIC_Project.html>
- <https://github.com/KanjiVG/kanjivg>
