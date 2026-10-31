$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$config = Get-Content -LiteralPath (Join-Path $root 'src\main\resources\application-local.yaml')
function ConfigValue([string]$key) {
    foreach ($line in $config) {
        $match = [regex]::Match($line, "^\s*spring\.datasource\.${key}:\s*(.+?)\s*$")
        if ($match.Success) { return $match.Groups[1].Value.Trim().Trim('"').Trim("'") }
    }
    throw "Missing local datasource property: $key"
}
$connection = ConfigValue 'url'
$match = [regex]::Match($connection, '^jdbc:mysql://([^:/]+):(\d+)/([^?]+)')
if (!$match.Success) { throw 'Expected an explicit local MySQL URL.' }
$dbHost = $match.Groups[1].Value
$port = $match.Groups[2].Value
if ($dbHost -notin @('localhost', '127.0.0.1')) { throw 'Scratch verification only supports local MySQL.' }
$user = ConfigValue 'username'
$env:MYSQL_PWD = ConfigValue 'password'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$scratch = 'jlas_minna_verify_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$created = $false
try {
    $exists = & $mysql -h $dbHost -P $port -u $user -N -e "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$scratch'"
    if ($LASTEXITCODE -ne 0 -or $exists) { throw 'Scratch database identity is unavailable.' }
    & $mysql -h $dbHost -P $port -u $user -e "CREATE DATABASE $scratch CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create scratch database.' }
    $created = $true
    $schema = Get-Content -LiteralPath (Join-Path $root 'jlas_schema.sql') -Raw -Encoding UTF8
    $schema = $schema -replace '(?m)^CREATE DATABASE IF NOT EXISTS jlas\b', "CREATE DATABASE IF NOT EXISTS $scratch"
    $schema = $schema -replace '(?m)^USE jlas;', "USE $scratch;"
    if ($schema -match '(?m)^USE jlas;') { throw 'Scratch schema rewrite failed.' }
    $schema | & $mysql -h $dbHost -P $port -u $user --default-character-set=utf8mb4
    if ($LASTEXITCODE -ne 0) { throw 'Canonical schema failed on scratch.' }
    # E5 (2026-10-28): the seed is split in two parts; the Minna blocks live in part 2.
    $bundle = (Get-Content -LiteralPath (Join-Path $root 'sql\seeds\learning_owned.sql') -Raw -Encoding UTF8) + "`n" +
        (Get-Content -LiteralPath (Join-Path $root 'sql\seeds\learning_owned_part2.sql') -Raw -Encoding UTF8)
    function BundleBlock([string]$marker) {
        $begin = '-- BEGIN ' + $marker + ' SHA256 '
        $end = '-- END ' + $marker
        $start = $bundle.IndexOf($begin, [StringComparison]::Ordinal)
        if ($start -lt 0) { throw "Canonical SQL block is absent: $marker" }
        $endAt = $bundle.IndexOf($end, $start, [StringComparison]::Ordinal)
        if ($endAt -lt 0) { throw "Canonical SQL block is incomplete: $marker" }
        return $bundle.Substring($start, $endAt - $start + $end.Length)
    }
    $n5ReadingBlock = BundleBlock 'data/learning-owned/learning-content.json#sections.sekai_n5_comprehension'
    $minnaBlock = BundleBlock 'data/learning-owned/learning-content.json#sections.jlpt_minna_n5_n4_listening'
    foreach ($run in 1..2) {
        $n5ReadingBlock | & $mysql -h $dbHost -P $port -u $user $scratch --default-character-set=utf8mb4
        if ($LASTEXITCODE -ne 0) { throw "Sekai N5 comprehension SQL failed on scratch run $run." }
        $minnaBlock | & $mysql -h $dbHost -P $port -u $user $scratch --default-character-set=utf8mb4
        if ($LASTEXITCODE -ne 0) { throw "Minna SQL failed on scratch run $run." }
        $result = & $mysql -h $dbHost -P $port -u $user $scratch --default-character-set=utf8mb4 -N -e "SELECT jlpt_level,COUNT(*),COUNT(DISTINCT SUBSTRING_INDEX(source_code,'-LISTEN-',1)) FROM assessment_question WHERE source_code LIKE 'LP-OWNED-MINNA-%' GROUP BY jlpt_level ORDER BY jlpt_level"
        if ($LASTEXITCODE -ne 0) { throw 'Scratch count query failed.' }
        Write-Output "RUN $run : $($result -join ' | ')"
        if (($result -join '|') -notmatch 'N4\s+641\s+25' -or ($result -join '|') -notmatch 'N5\s+641\s+25') {
            throw "Minna SQL count drift on run $run."
        }
        $reading = & $mysql -h $dbHost -P $port -u $user $scratch --default-character-set=utf8mb4 -N -e "SELECT COUNT(*) FROM assessment_question WHERE source_code LIKE 'LP-OWNED-SEKAI-N5-L%-READ-%'"
        if ($LASTEXITCODE -ne 0 -or [int]$reading -ne 50) { throw "N5 reading count drift on run $run." }
    }
} finally {
    if ($created -and $scratch -match '^jlas_minna_verify_[0-9a-f]{8}$') {
        & $mysql -h $dbHost -P $port -u $user -e "DROP DATABASE $scratch"
        if ($LASTEXITCODE -ne 0) { Write-Warning "Scratch database cleanup failed: $scratch" }
    }
    $env:MYSQL_PWD = $null
}
