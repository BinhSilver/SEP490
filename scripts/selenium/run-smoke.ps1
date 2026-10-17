param([string]$BaseUrl = 'http://localhost:8080')

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runId = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$runDirectory = Join-Path $repoRoot "target/selenium-runs/preflight-$runId"
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$logPath = Join-Path $runDirectory 'preflight.tsv'
"timestamp_utc`tstep`tstatus`tobserved" | Set-Content -LiteralPath $logPath -Encoding utf8

function Write-RunStep([string]$Step, [string]$Status, [string]$Observed) {
    $line = [DateTime]::UtcNow.ToString('o') + "`t$Step`t$Status`t$Observed"
    Add-Content -LiteralPath $logPath -Value $line -Encoding utf8
    Write-Output $line
}

$uri = [Uri]$BaseUrl
if ($uri.Scheme -notin @('http', 'https') -or $uri.UserInfo -or $uri.Query -or $uri.Fragment) {
    Write-RunStep 'Validate URL' 'BLOCKED_CONFIG' 'Use an app base URL without credentials, query or fragment'
    exit 2
}
Write-RunStep 'Open login preflight' 'START' 'Check app reachability before browser startup'
try {
    $response = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + '/login') -TimeoutSec 10 -UseBasicParsing
    if ($response.StatusCode -ne 200 -or $response.Content -notmatch 'id="loginForm"') {
        Write-RunStep 'Open login preflight' 'BLOCKED_ENV' 'Response is not the J-LAS login page'
        exit 2
    }
} catch {
    Write-RunStep 'Open login preflight' 'BLOCKED_ENV' $_.Exception.GetType().Name
    Write-Output "Browser cases NOT_RUN. Evidence: $logPath"
    exit 2
}
Write-RunStep 'Open login preflight' 'PASS' 'J-LAS login form reachable'
$env:JLAS_BASE_URL = $BaseUrl.TrimEnd('/')
$env:JLAS_HEADLESS = 'true'
Push-Location $repoRoot
try {
    Write-RunStep 'Selenium smoke' 'START' 'AUTH-01 desktop/mobile, AUTH-02, AUTH-03'
    & ./mvnw.cmd test '-Dtest=FullSpecSmokeE2ETest' '-De2e.excludedGroups=' '-Dmaven.resources.skip=true' 2>&1 |
        Tee-Object -FilePath (Join-Path $runDirectory 'maven.log')
    $testExitCode = $LASTEXITCODE
    if ($testExitCode -eq 0) {
        Write-RunStep 'Selenium smoke' 'PASS' 'Read Surefire and per-case steps.tsv for assertions'
    } else {
        Write-RunStep 'Selenium smoke' 'FAIL' "Maven exit code $testExitCode"
    }
} finally {
    Pop-Location
}
exit $testExitCode
