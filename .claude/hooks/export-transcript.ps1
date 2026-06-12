<#
.SYNOPSIS
  Export a Claude Code session transcript (JSONL) to per-day Markdown files.

.DESCRIPTION
  Two modes:
    Hook mode    - no -TranscriptPath; reads the SessionEnd hook JSON payload
                   from stdin and uses its .transcript_path.
    Manual mode  - pass -TranscriptPath (and optionally -OnlyDate) to convert
                   an arbitrary transcript, e.g. for backfilling history.

  Each user/assistant message is bucketed by its LOCAL calendar date, so a
  session that spans several days produces one file per day:
      t3/conversations/<yyyy-MM-dd>.<session-id-8>.md
  Files are overwritten (idempotent) and written BOM-free UTF-8.

  Output is faithful: assistant/user prose inline; thinking, tool calls, and
  tool results wrapped in collapsible <details> so the conversation reads
  cleanly while keeping the full record.
#>
[CmdletBinding()]
param(
    [string]$TranscriptPath,
    [string]$OnlyDate,
    [string]$OutDir
)

$ErrorActionPreference = 'Stop'

# Repo root = grandparent of this script (.claude/hooks -> .claude -> repo).
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
if (-not $OutDir) { $OutDir = Join-Path $repoRoot 't3\conversations' }

# Hook mode: pull transcript_path from the JSON payload Claude Code writes to
# stdin. This MUST NOT block. On Windows the hook is spawned via Git Bash and the
# child can inherit an interactive console, or a stdin pipe that is never closed
# (no EOF) -- a plain [Console]::In.ReadToEnd() then hangs forever. That is the
# bug we hit with the Stop hook (a Git terminal popup that had to be closed by
# hand) and again with PreCompact (the archive simply never got written). So:
# only read when stdin is redirected, and cap the read with a short timeout.
if (-not $TranscriptPath -and [Console]::IsInputRedirected) {
    try {
        $reader = [System.IO.StreamReader]::new([Console]::OpenStandardInput())
        $task = $reader.ReadToEndAsync()
        if ($task.Wait(3000) -and $task.Result) {
            $TranscriptPath = ($task.Result | ConvertFrom-Json).transcript_path
        }
    } catch { }
}

# Fallback: stdin gave us nothing (timed out, interactive console, or an empty /
# unparseable payload). Derive Claude Code's per-project transcript directory
# from the project cwd and take the newest .jsonl -- that is the session being
# compacted / ended. Keeps the hook working even when stdin is not delivered.
if (-not $TranscriptPath) {
    $cwd  = if ($env:CLAUDE_PROJECT_DIR) { $env:CLAUDE_PROJECT_DIR } else { (Get-Location).Path }
    $proj = Join-Path $env:USERPROFILE (".claude\projects\" + ($cwd -replace '[\\/:.]', '-'))
    if (Test-Path -LiteralPath $proj) {
        $newest = Get-ChildItem -LiteralPath $proj -Filter *.jsonl -ErrorAction SilentlyContinue |
                  Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($newest) { $TranscriptPath = $newest.FullName }
    }
}

if (-not $TranscriptPath -or -not (Test-Path -LiteralPath $TranscriptPath)) { exit 0 }
if (-not (Test-Path -LiteralPath $OutDir)) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
}

$sessionId = [System.IO.Path]::GetFileNameWithoutExtension($TranscriptPath)
$shortId   = $sessionId.Substring(0, [Math]::Min(8, $sessionId.Length))

# Pick a backtick fence longer than any run of backticks inside the body,
# so arbitrary content (tool I/O containing ``` ) can't break out.
function New-SafeFence([string]$body, [string]$lang) {
    $max = 0
    foreach ($m in [regex]::Matches($body, '`+')) { if ($m.Length -gt $max) { $max = $m.Length } }
    $fence = '`' * ([Math]::Max(3, $max + 1))
    return "$fence$lang`n$body`n$fence"
}

function Format-Block($b) {
    switch ($b.type) {
        'text' { return ($b.text + "`n") }
        'thinking' {
            return "<details>`n<summary><em>thinking</em></summary>`n`n$($b.thinking)`n`n</details>`n"
        }
        'tool_use' {
            $json = if ($null -ne $b.input) { $b.input | ConvertTo-Json -Depth 30 } else { '{}' }
            $fenced = New-SafeFence $json 'json'
            return "<details>`n<summary>tool call: <code>$($b.name)</code></summary>`n`n$fenced`n`n</details>`n"
        }
        'tool_result' {
            $c = $b.content
            if ($c -is [string]) { $txt = $c }
            elseif ($c) { $txt = (($c | ForEach-Object { if ($_.type -eq 'text') { $_.text } else { "[$($_.type)]" } }) -join "`n") }
            else { $txt = '' }
            $fenced = New-SafeFence $txt ''
            return "<details>`n<summary>tool result</summary>`n`n$fenced`n`n</details>`n"
        }
        default { return '' }
    }
}

function Format-Message($o) {
    $role = $o.message.role
    $dateStr = $null
    $timeStr = ''
    if ($o.timestamp) {
        $local   = [datetimeoffset]::Parse($o.timestamp).LocalDateTime
        $dateStr = $local.ToString('yyyy-MM-dd')
        $timeStr = $local.ToString('HH:mm:ss')
    }

    $content = $o.message.content
    if ($content -is [string]) {
        $blocks = @([pscustomobject]@{ type = 'text'; text = $content })
    } else {
        $blocks = @($content)
    }

    # A user line carrying only tool_result blocks is the tool loop, not a turn.
    $onlyToolResults = $false
    if ($role -eq 'user' -and ($content -isnot [string])) {
        $types = @($blocks | ForEach-Object { $_.type })
        if ($types.Count -gt 0 -and (@($types | Where-Object { $_ -ne 'tool_result' }).Count -eq 0)) {
            $onlyToolResults = $true
        }
    }

    $sideTag = if ($o.isSidechain -eq $true) { ' (sub-agent)' } else { '' }

    $sb = New-Object System.Text.StringBuilder
    if (-not $onlyToolResults) {
        $who = if ($role -eq 'assistant') { 'Assistant' } else { 'User' }
        [void]$sb.AppendLine("### $who$sideTag - $timeStr")
        [void]$sb.AppendLine('')
    }
    foreach ($b in $blocks) { [void]$sb.AppendLine((Format-Block $b)) }
    [void]$sb.AppendLine('')

    return [pscustomobject]@{ Date = $dateStr; Text = $sb.ToString() }
}

$byDate = @{}
$counts = @{}
$sr = New-Object System.IO.StreamReader($TranscriptPath)
try {
    while ($null -ne ($line = $sr.ReadLine())) {
        if ($line.Length -eq 0) { continue }
        if ($line -notmatch '"type":"(user|assistant)"') { continue }   # cheap pre-filter
        try { $o = $line | ConvertFrom-Json } catch { continue }
        if (($o.type -ne 'user' -and $o.type -ne 'assistant') -or -not $o.message) { continue }

        $r = Format-Message $o
        if (-not $r.Date) { continue }
        if ($OnlyDate -and $r.Date -ne $OnlyDate) { continue }

        if (-not $byDate.ContainsKey($r.Date)) {
            $byDate[$r.Date] = New-Object System.Text.StringBuilder
            $counts[$r.Date] = 0
        }
        [void]$byDate[$r.Date].Append($r.Text)
        $counts[$r.Date]++
    }
} finally {
    $sr.Close()
}

$enc = [System.Text.UTF8Encoding]::new($false)
foreach ($d in $byDate.Keys) {
    $file = Join-Path $OutDir "$d.$shortId.md"
    $header = "# Conversation - $d`n`n" +
              "- Session: ``$sessionId```n" +
              "- Source: ``$TranscriptPath```n" +
              "- Messages: $($counts[$d])`n`n---`n`n"
    [System.IO.File]::WriteAllText($file, ($header + $byDate[$d].ToString()), $enc)
    Write-Output "wrote $file ($($counts[$d]) messages)"
}
