<#
.SYNOPSIS
  Đánh giá RAG của gia sư AI qua API admin của J-LAS (Phase 2).

.DESCRIPTION
  1. (Tuỳ chọn) gọi /reindex để đồng bộ Qdrant theo MySQL.
  2. Với mỗi câu hỏi trong file: gọi /search với ngưỡng thấp (mặc định 0) để thấy điểm thật của bge-m3,
     ghi top đoạn + điểm + từ/kanji tra được.
  3. Với -Ask: gọi /ask (model chat thật) và ghi câu trả lời + thời gian.
  Kết quả: <OutDir>\<thời gian>\results.json và summary.md. Script không tự chấm đúng/sai.

.EXAMPLE
  $token = "<access token của tài khoản ADMIN>"
  powershell -ExecutionPolicy Bypass -File scripts\ai-tutor\rag-eval.ps1 -Token $token -Reindex -Ask
#>
param(
    [Parameter(Mandatory = $true)][string]$Token,
    [string]$BaseUrl = "http://localhost:8080",
    [string]$QuestionFile = (Join-Path $PSScriptRoot "rag-eval-questions.json"),
    [string]$OutDir = "target\ai-tutor-rag-eval",
    [int]$Limit = 5,
    [double]$MinScore = 0.0,
    [switch]$Reindex,
    [switch]$Ask
)

$ErrorActionPreference = "Stop"
$headers = @{ Authorization = "Bearer $Token" }
$api = $BaseUrl.TrimEnd('/') + "/api/admin/system/ai/rag"

function Invoke-Api([string]$Method, [string]$Url, $Body) {
    $params = @{ Method = $Method; Uri = $Url; Headers = $headers; TimeoutSec = 300 }
    if ($null -ne $Body) {
        $params.ContentType = "application/json; charset=utf-8"
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 5 -Compress))
    }
    return Invoke-RestMethod @params
}

$questions = (Get-Content -Raw -Encoding UTF8 $QuestionFile | ConvertFrom-Json).questions
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dir = Join-Path $OutDir $stamp
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$status = (Invoke-Api GET "$api/status" $null).data
Write-Host ("RAG enabled={0} qdrant={1} collection={2} points={3}" -f $status.enabled, $status.qdrantReachable, $status.collection, $status.points)

$reindexReport = $null
if ($Reindex) {
    Write-Host "Reindex..."
    $reindexReport = (Invoke-Api POST "$api/reindex" $null).data
    Write-Host ("  chunks={0} embedded={1} unchanged={2} deleted={3} {4} ms" -f $reindexReport.chunks, $reindexReport.embedded, $reindexReport.unchanged, $reindexReport.deleted, $reindexReport.durationMs)
}

$results = @()
foreach ($q in $questions) {
    Write-Host ("[{0}] {1}" -f $q.id, $q.question)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $query = "q=" + [uri]::EscapeDataString($q.question) + "&limit=$Limit&minScore=$MinScore"
    $search = (Invoke-Api GET "$api/search?$query" $null).data
    $searchMs = $sw.ElapsedMilliseconds
    $hits = @($search.passages | ForEach-Object {
        [pscustomobject]@{ score = [math]::Round($_.score, 3); type = $_.sourceType; lesson = $_.citation.lesson; title = $_.title }
    })
    $row = [ordered]@{
        id = $q.id; question = $q.question; expect = $q.expect; searchMs = $searchMs
        kanji = @($search.lexicon.kanji | ForEach-Object { "$($_.character) ($($_.hanViet))" })
        words = @($search.lexicon.words | ForEach-Object { "$($_.expression) ($($_.reading)): $($_.meaning)" })
        hits = $hits
    }
    if ($Ask) {
        $answer = (Invoke-Api POST "$api/ask" @{ question = $q.question }).data
        $row.answer = $answer.answer
        $row.retrievalMs = $answer.retrievalMs
        $row.generationMs = $answer.generationMs
        $row.sourcesInPrompt = @($answer.passages).Count + @($answer.lexicon.kanji).Count + @($answer.lexicon.words).Count
    }
    $results += [pscustomobject]$row
}

$results | ConvertTo-Json -Depth 6 | Out-File -Encoding UTF8 (Join-Path $dir "results.json")

$md = @("# RAG eval $stamp", "", "Collection: $($status.collection), model embedding: $($status.embeddingModel), ngưỡng tìm: $MinScore", "")
if ($reindexReport) { $md += "Reindex: $($reindexReport.chunks) đoạn, embed $($reindexReport.embedded), giữ $($reindexReport.unchanged), xoá $($reindexReport.deleted), $($reindexReport.durationMs) ms"; $md += "" }
foreach ($r in $results) {
    $md += "## $($r.id): $($r.question)"
    $md += "Cần thấy: $($r.expect)"
    $md += ""
    $md += "Từ điển: " + ((@($r.kanji) + @($r.words)) -join "; ")
    $md += ""
    $md += "| điểm | loại | bài | tiêu đề |"
    $md += "|---|---|---|---|"
    foreach ($h in $r.hits) { $md += "| $($h.score) | $($h.type) | $($h.lesson) | $($h.title) |" }
    if ($Ask) { $md += ""; $md += "Trả lời ($($r.retrievalMs) ms tìm, $($r.generationMs) ms sinh):"; $md += ""; $md += $r.answer }
    $md += ""
}
$md -join "`n" | Out-File -Encoding UTF8 (Join-Path $dir "summary.md")
Write-Host "Xong: $dir"
