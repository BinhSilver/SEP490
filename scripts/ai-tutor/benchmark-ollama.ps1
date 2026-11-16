<#
.SYNOPSIS
  Phase 1 J-LAS AI Tutor: đo model Ollama cục bộ trên máy chủ AI (Windows + GPU NVIDIA).

.DESCRIPTION
  Chỉ gọi Ollama trên máy này (mặc định http://127.0.0.1:11434). Không gọi API cloud, không cần API key.
  Đo cho từng model: thời gian nạp lần đầu, độ trễ từng câu, token/giây, VRAM đỉnh (nvidia-smi),
  RAM của tiến trình Ollama, JSON có cấu trúc qua endpoint /v1 (đúng đường Spring Boot dùng),
  và các mức gọi đồng thời. Kết quả thô + câu trả lời được ghi ra thư mục -OutDir để người đọc chấm.

  Chạy được trên Windows PowerShell 5.1 và PowerShell 7.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\benchmark-ollama.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\benchmark-ollama.ps1 -Models qwen3.5:4b,gemma3:4b -Concurrency 1,2,4
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = "http://127.0.0.1:11434",
    [string[]]$Models = @("qwen3.5:4b"),
    [string]$EmbeddingModel = "bge-m3",
    [string]$PromptFile = "",
    [string]$OutDir = "target\ai-tutor-benchmark",
    [int[]]$Concurrency = @(1, 2, 4),
    [int]$NumCtx = 4096,
    [int]$TimeoutSeconds = 300,
    [switch]$SkipPull,
    [switch]$SkipEmbedding
)

$ErrorActionPreference = "Stop"
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
Add-Type -AssemblyName System.Net.Http

if (-not $PromptFile) { $PromptFile = Join-Path $PSScriptRoot "phase1-prompts.json" }
$BaseUrl = $BaseUrl.TrimEnd("/")
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $OutDir $stamp
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$http = New-Object System.Net.Http.HttpClient
$http.Timeout = [TimeSpan]::FromSeconds($TimeoutSeconds)

function Write-Utf8([string]$Path, [string]$Text) { [System.IO.File]::WriteAllText($Path, $Text, $Utf8) }

function Send-Json([string]$Path, $Body) {
    $json = $Body | ConvertTo-Json -Depth 20 -Compress
    $content = New-Object System.Net.Http.StringContent($json, $Utf8, "application/json")
    return $http.PostAsync($BaseUrl + $Path, $content)
}

function Read-Response($Task) {
    $response = $Task.Result
    $text = $response.Content.ReadAsStringAsync().Result
    if (-not $response.IsSuccessStatusCode) {
        throw ("HTTP " + [int]$response.StatusCode + ": " + $text)
    }
    return ($text | ConvertFrom-Json)
}

function Invoke-OllamaJson([string]$Path, $Body) {
    return Read-Response (Send-Json $Path $Body)
}

function Get-OllamaRamMB {
    $procs = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -like "ollama*" }
    if (-not $procs) { return $null }
    return [math]::Round((($procs | Measure-Object WorkingSet64 -Sum).Sum) / 1MB, 0)
}

function Start-GpuSampler([string]$File) {
    if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) { return $null }
    return Start-Process -FilePath "nvidia-smi" -PassThru -WindowStyle Hidden -ArgumentList @(
        "--query-gpu=memory.used,utilization.gpu", "--format=csv,noheader,nounits", "-lms", "500", "-f", $File)
}

function Stop-GpuSampler($Process, [string]$File) {
    if ($null -eq $Process) { return $null }
    Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 300
    if (-not (Test-Path $File)) { return $null }
    $rows = Get-Content $File | Where-Object { $_ -match "^\s*\d+" } | ForEach-Object {
        $parts = $_.Split(","); [pscustomobject]@{ MemMB = [int]$parts[0].Trim(); Util = [int]$parts[1].Trim() }
    }
    if (-not $rows) { return $null }
    return [pscustomobject]@{
        peakVramMB = ($rows | Measure-Object MemMB -Maximum).Maximum
        peakGpuUtilPercent = ($rows | Measure-Object Util -Maximum).Maximum
        samples = @($rows).Count
    }
}

function Get-TokPerSec($r) {
    if ($r.eval_count -and $r.eval_duration -gt 0) { return [math]::Round($r.eval_count / ($r.eval_duration / 1e9), 1) }
    return $null
}

function Test-ContainsAny([string]$Text, $Needles) {
    if (-not $Needles) { return $null }
    foreach ($n in $Needles) { if ($Text -and $Text.Contains([string]$n)) { return $true } }
    return $false
}

function Unload-AllModels {
    try {
        $ps = Invoke-RestMethod -Uri ($BaseUrl + "/api/ps") -Method Get
        foreach ($m in $ps.models) {
            try { Invoke-OllamaJson "/api/generate" @{ model = $m.name; keep_alive = 0 } | Out-Null } catch { }
        }
    } catch { }
    Start-Sleep -Seconds 2
}

# ── 1. Môi trường ────────────────────────────────────────────────────────────
Write-Host "== J-LAS AI Tutor benchmark Phase 1 -> $runDir"
$env_ = [ordered]@{
    timestamp = (Get-Date).ToString("o")
    baseUrl = $BaseUrl
    os = [System.Environment]::OSVersion.VersionString
    psVersion = $PSVersionTable.PSVersion.ToString()
    cpu = $null; ramTotalGB = $null; gpu = $null; ollamaVersion = $null
    ollamaEnv = [ordered]@{
        OLLAMA_HOST = $env:OLLAMA_HOST; OLLAMA_NUM_PARALLEL = $env:OLLAMA_NUM_PARALLEL
        OLLAMA_MAX_LOADED_MODELS = $env:OLLAMA_MAX_LOADED_MODELS; OLLAMA_KEEP_ALIVE = $env:OLLAMA_KEEP_ALIVE
        OLLAMA_FLASH_ATTENTION = $env:OLLAMA_FLASH_ATTENTION; OLLAMA_KV_CACHE_TYPE = $env:OLLAMA_KV_CACHE_TYPE
    }
    numCtx = $NumCtx
}
try { $env_.cpu = (Get-CimInstance Win32_Processor | Select-Object -First 1).Name } catch { }
try { $env_.ramTotalGB = [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1) } catch { }
if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
    $env_.gpu = (& nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader) -join "; "
} else {
    Write-Warning "Không thấy nvidia-smi: sẽ không đo được VRAM."
}
try { $env_.ollamaVersion = (Invoke-RestMethod -Uri ($BaseUrl + "/api/version") -Method Get).version }
catch { throw "Không kết nối được Ollama tại $BaseUrl. Hãy chạy 'ollama serve' (hoặc mở ứng dụng Ollama) rồi thử lại." }
Write-Host ("Ollama " + $env_.ollamaVersion + " | GPU: " + $env_.gpu)

$promptSet = [System.IO.File]::ReadAllText((Resolve-Path $PromptFile), $Utf8) | ConvertFrom-Json
$system = $promptSet.systemPrompt
$results = [ordered]@{ environment = $env_; promptSet = $promptSet.version; models = @(); embedding = $null }

# ── 2. Từng model chat ───────────────────────────────────────────────────────
foreach ($model in $Models) {
    Write-Host ""
    Write-Host "== Model $model"
    if (-not $SkipPull) { & ollama pull $model; if ($LASTEXITCODE -ne 0) { Write-Warning "Không tải được $model, bỏ qua."; continue } }
    Unload-AllModels
    $gpuFile = Join-Path $runDir ("gpu-" + ($model -replace "[^a-zA-Z0-9.-]", "_") + ".csv")
    $sampler = Start-GpuSampler $gpuFile
    $modelResult = [ordered]@{ model = $model; coldLoadMs = $null; prompts = @(); openAiJson = $null; concurrency = @();
        peakVramMB = $null; peakGpuUtilPercent = $null; peakOllamaRamMB = $null; errors = @() }
    $peakRam = 0
    try {
        # Nạp lần đầu (cold start)
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $warm = Invoke-OllamaJson "/api/chat" @{ model = $model; stream = $false; think = $false; options = @{ num_ctx = $NumCtx; temperature = 0 };
            messages = @(@{ role = "user"; content = "こんにちは" }) }
        $modelResult.coldLoadMs = $sw.ElapsedMilliseconds
        $modelResult.loadDurationMs = [math]::Round($warm.load_duration / 1e6, 0)

        foreach ($p in $promptSet.prompts) {
            $body = @{ model = $model; stream = $false; think = $false; options = @{ num_ctx = $NumCtx; temperature = 0 };
                messages = @(@{ role = "system"; content = $system }, @{ role = "user"; content = $p.user }) }
            if ($p.json) { $body.format = $promptSet.correctionSchema }
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            $entry = [ordered]@{ id = $p.id; category = $p.category; level = $p.level }
            try {
                $r = Invoke-OllamaJson "/api/chat" $body
                $entry.wallMs = $sw.ElapsedMilliseconds
                $entry.promptTokens = $r.prompt_eval_count
                $entry.outputTokens = $r.eval_count
                $entry.tokensPerSecond = Get-TokPerSec $r
                $entry.answer = $r.message.content
                $entry.containsExpected = Test-ContainsAny $r.message.content $p.mustContainAny
                if ($p.json) {
                    try {
                        $parsed = $r.message.content | ConvertFrom-Json
                        $entry.jsonValid = ($null -ne $parsed.hasError) -and ($null -ne $parsed.reasonVi)
                        if ($null -ne $p.expectHasError) { $entry.hasErrorAsExpected = ($parsed.hasError -eq $p.expectHasError) }
                    } catch { $entry.jsonValid = $false }
                }
            } catch {
                $entry.wallMs = $sw.ElapsedMilliseconds
                $entry.error = $_.Exception.Message
            }
            $ram = Get-OllamaRamMB; if ($ram -gt $peakRam) { $peakRam = $ram }
            $modelResult.prompts += [pscustomobject]$entry
            Write-Host ("  {0,-28} {1,6} ms  {2,6} tok/s" -f $p.id, $entry.wallMs, $entry.tokensPerSecond)
        }

        # Đường OpenAI-compatible mà Spring Boot dùng: /v1/chat/completions + response_format json_schema strict
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $schema = @{ type = "json_schema"; json_schema = @{ name = "turn"; strict = $true; schema = @{
                type = "object"; additionalProperties = $false; required = @("reply", "hasError", "corrected")
                properties = @{ reply = @{ type = "string" }; hasError = @{ type = "boolean" }; corrected = @{ type = @("string", "null") } } } } }
            $r = Invoke-OllamaJson "/v1/chat/completions" @{ model = $model; temperature = 0; max_tokens = 300; response_format = $schema;
                messages = @(@{ role = "system"; content = "Bạn là nhân viên cửa hàng tiện lợi ở Nhật. Trả lời JSON." },
                             @{ role = "user"; content = "すみません、おにぎりは どこですか。" }) }
            $content = $r.choices[0].message.content
            $ok = $false
            try { $j = $content | ConvertFrom-Json; $ok = ($null -ne $j.reply) -and ($null -ne $j.hasError) } catch { }
            $modelResult.openAiJson = [ordered]@{ ok = $ok; wallMs = $sw.ElapsedMilliseconds; content = $content }
        } catch {
            $modelResult.openAiJson = [ordered]@{ ok = $false; wallMs = $sw.ElapsedMilliseconds; error = $_.Exception.Message }
        }
        Write-Host ("  /v1 json_schema hợp lệ: " + $modelResult.openAiJson.ok)

        # Gọi đồng thời: N request cùng lúc, đo thời gian cả lô và token/s mỗi request
        $concPrompt = ($promptSet.prompts | Where-Object { $_.id -eq "vocab-benkyou" } | Select-Object -First 1).user
        foreach ($n in $Concurrency) {
            $tasks = @()
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            for ($i = 0; $i -lt $n; $i++) {
                $tasks += Send-Json "/api/chat" @{ model = $model; stream = $false; think = $false; options = @{ num_ctx = $NumCtx; temperature = 0 };
                    messages = @(@{ role = "system"; content = $system }, @{ role = "user"; content = $concPrompt }) }
            }
            $okCount = 0; $tps = @(); $serverMs = @(); $errs = @()
            foreach ($t in $tasks) {
                try {
                    $r = Read-Response $t
                    $okCount++
                    $tps += (Get-TokPerSec $r)
                    $serverMs += [math]::Round($r.total_duration / 1e6, 0)
                } catch { $errs += $_.Exception.Message }
            }
            $batchMs = $sw.ElapsedMilliseconds
            $ram = Get-OllamaRamMB; if ($ram -gt $peakRam) { $peakRam = $ram }
            $c = [ordered]@{ concurrent = $n; ok = $okCount; batchWallMs = $batchMs;
                maxRequestMs = ($serverMs | Measure-Object -Maximum).Maximum;
                avgTokensPerSecondPerRequest = [math]::Round((($tps | Measure-Object -Average).Average), 1);
                errors = $errs }
            $modelResult.concurrency += [pscustomobject]$c
            Write-Host ("  đồng thời {0}: {1}/{0} ok, cả lô {2} ms, chậm nhất {3} ms" -f $n, $okCount, $batchMs, $c.maxRequestMs)
        }
    } catch {
        $modelResult.errors += $_.Exception.Message
        Write-Warning ("Lỗi với ${model}: " + $_.Exception.Message)
    } finally {
        $gpu = Stop-GpuSampler $sampler $gpuFile
        if ($gpu) { $modelResult.peakVramMB = $gpu.peakVramMB; $modelResult.peakGpuUtilPercent = $gpu.peakGpuUtilPercent }
        if ($peakRam -gt 0) { $modelResult.peakOllamaRamMB = $peakRam }
        try { $modelResult.ollamaPs = (Invoke-RestMethod -Uri ($BaseUrl + "/api/ps") -Method Get).models | Select-Object name, size, size_vram } catch { }
    }
    $results.models += [pscustomobject]$modelResult
}

# ── 3. Embedding ─────────────────────────────────────────────────────────────
if (-not $SkipEmbedding) {
    Write-Host ""
    Write-Host "== Embedding $EmbeddingModel"
    try {
        if (-not $SkipPull) { & ollama pull $EmbeddingModel }
        $texts = @("N1 は N2 です", "わたしは がっこうへ いきます。", "Tôi đi đến trường.", "食べる: ăn", "はじめまして。グエンです。")
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $first = Invoke-OllamaJson "/api/embed" @{ model = $EmbeddingModel; input = $texts[0] }
        $coldMs = $sw.ElapsedMilliseconds
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $batch = Invoke-OllamaJson "/api/embed" @{ model = $EmbeddingModel; input = $texts }
        $batchMs = $sw.ElapsedMilliseconds
        $results.embedding = [ordered]@{ model = $EmbeddingModel; dims = @($first.embeddings[0]).Count; coldMs = $coldMs;
            batchOf5Ms = $batchMs; vectors = @($batch.embeddings).Count }
        Write-Host ("  dims={0} lần đầu={1} ms, lô 5 câu={2} ms" -f $results.embedding.dims, $coldMs, $batchMs)
    } catch {
        $results.embedding = [ordered]@{ model = $EmbeddingModel; error = $_.Exception.Message }
        Write-Warning ("Embedding lỗi: " + $_.Exception.Message)
    }
}

# ── 4. Ghi kết quả ───────────────────────────────────────────────────────────
$jsonPath = Join-Path $runDir "results.json"
Write-Utf8 $jsonPath ($results | ConvertTo-Json -Depth 20)

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# Kết quả benchmark Ollama — $stamp")
[void]$md.AppendLine("")
[void]$md.AppendLine("Ollama $($env_.ollamaVersion); GPU: $($env_.gpu); CPU: $($env_.cpu); RAM: $($env_.ramTotalGB) GB; num_ctx=$NumCtx")
[void]$md.AppendLine("")
[void]$md.AppendLine("| Model | Nạp lần đầu (ms) | Trễ TB (ms) | Token/s TB | VRAM đỉnh (MB) | RAM Ollama đỉnh (MB) | JSON /v1 | Đạt kiểm tra thô |")
[void]$md.AppendLine("|---|---|---|---|---|---|---|---|")
foreach ($m in $results.models) {
    $ok = @($m.prompts | Where-Object { -not $_.error })
    $avgMs = if ($ok.Count) { [math]::Round(($ok | Measure-Object wallMs -Average).Average, 0) } else { "-" }
    $avgTps = if ($ok.Count) { [math]::Round(($ok | Measure-Object tokensPerSecond -Average).Average, 1) } else { "-" }
    $checks = @($m.prompts | Where-Object { $null -ne $_.containsExpected })
    $passed = @($checks | Where-Object { $_.containsExpected }).Count
    $jsonOk = if ($m.openAiJson) { $m.openAiJson.ok } else { "-" }
    [void]$md.AppendLine("| $($m.model) | $($m.coldLoadMs) | $avgMs | $avgTps | $($m.peakVramMB) | $($m.peakOllamaRamMB) | $jsonOk | $passed/$($checks.Count) |")
}
[void]$md.AppendLine("")
[void]$md.AppendLine("Câu trả lời đầy đủ nằm trong results.json; cần người đọc chấm tiếng Nhật/tiếng Việt và mức JLPT.")
Write-Utf8 (Join-Path $runDir "summary.md") $md.ToString()

Write-Host ""
Write-Host "Xong. Gửi lại 2 file: $jsonPath và $(Join-Path $runDir 'summary.md')"
