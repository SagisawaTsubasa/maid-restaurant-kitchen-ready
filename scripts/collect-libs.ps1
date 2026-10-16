# collect-libs.ps1 - Collect compile-time dependency jars from the client mods dir.
# Source of truth: docs/scan/ cooking TSV "source jar" column (unique values).
# Output: libs/ (gitignored) + docs/libs-manifest.md
# Discipline: 0 or >1 matches for a TSV entry = LOUD error (exit 1), never silent.
param(
    # ASCII-only default: auto-detect the version dir (ends with -O) under versions\
    [string]$ModsDir = "",
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ModsDir)) {
    $versionsRoot = "D:\MC\Client\.minecraft\versions"
    $verDir = Get-ChildItem -Path $versionsRoot -Directory | Where-Object { $_.Name -like "*-O" } | Select-Object -First 1
    if ($null -eq $verDir) { Write-Error "Version dir (*-O) not found under $versionsRoot"; exit 1 }
    $ModsDir = Join-Path $verDir.FullName "mods"
}
# auto-detect the scan TSV (filename is Chinese; avoid Chinese literals in this ASCII-only script)
$tsvPath = Get-ChildItem -Path (Join-Path $RepoRoot "docs\scan") -Filter *.tsv -File | Select-Object -First 1
if ($null -eq $tsvPath) { Write-Error "No .tsv found under docs\scan"; exit 1 }
$tsvPath = $tsvPath.FullName
$libsDir = Join-Path $RepoRoot "libs"
$manifestPath = Join-Path $RepoRoot "docs\libs-manifest.md"

if (-not (Test-Path $ModsDir)) { Write-Error "ModsDir not found: $ModsDir"; exit 1 }
if (-not (Test-Path $tsvPath)) { Write-Error "TSV not found: $tsvPath"; exit 1 }

New-Item -ItemType Directory -Force -Path $libsDir | Out-Null

# --- parse TSV source-jar column (first column; file has UTF-8 BOM) ---
$lines = [System.IO.File]::ReadAllLines($tsvPath)
$header = $lines[0] -replace "^\xEF\xBB\xBF", ""
$colCount = ($header -split "`t").Count
$sourceNames = New-Object System.Collections.Generic.List[string]
foreach ($line in $lines[1..($lines.Count - 1)]) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $firstCol = ($line -split "`t")[0]
    $sourceNames.Add($firstCol.Trim())
}
$uniqueSources = $sourceNames | Sort-Object -Unique
Write-Host ("TSV source-jar unique values: " + $uniqueSources.Count)

# --- index client jars ---
$modJars = Get-ChildItem -Path $ModsDir -Filter *.jar -File
Write-Host ("Client jars indexed: " + $modJars.Count)

function Get-ModId([string]$jarPath) {
    # read META-INF/neoforge.mods.toml (fallback mods.toml); take modId from the [[mods]] block
    foreach ($entry in @("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        try {
            $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
            try {
                $e = $zip.GetEntry($entry)
                if ($null -eq $e) { continue }
                $reader = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
                $toml = $reader.ReadToEnd()
                $reader.Close()
                # content after the [[mods]] marker (dependencies may appear before/after)
                $idx = $toml.IndexOf("[[mods]]")
                $scope = if ($idx -ge 0) { $toml.Substring($idx) } else { $toml }
                $m = [regex]::Match($scope, '(?m)^\s*modId\s*=\s*"([^"]+)"')
                if ($m.Success) { return $m.Groups[1].Value }
            } finally { $zip.Dispose() }
        } catch { continue }
    }
    return $null
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$rows = New-Object System.Collections.Generic.List[object]
$failCount = 0

foreach ($src in $uniqueSources) {
    if ($src -match "^VANILLA") {
        $rows.Add([pscustomobject]@{ Source = $src; Jar = "-"; ModId = "-"; Status = "SKIP vanilla (not a jar)" })
        continue
    }
    $matches = @($modJars | Where-Object { $_.Name.Contains($src) })
    if ($matches.Count -eq 1) {
        $jar = $matches[0]
        $mid = Get-ModId $jar.FullName
        # [System.IO.File]::Copy: literal paths. Copy-Item treats [ ] as wildcards,
        # which silently mangles jar names like "[烘焙坊] bakeries-...jar".
        [System.IO.File]::Copy($jar.FullName, (Join-Path $libsDir $jar.Name), $true)
        $rows.Add([pscustomobject]@{ Source = $src; Jar = $jar.Name; ModId = $mid; Status = "OK" })
        Write-Host ("OK  " + $src + "  =>  " + $jar.Name + "  (modId=" + $mid + ")")
    } elseif ($matches.Count -eq 0) {
        $failCount++
        $rows.Add([pscustomobject]@{ Source = $src; Jar = "-"; ModId = "-"; Status = "MISSING (0 matches)" })
        Write-Warning ("MISSING: " + $src)
    } else {
        $failCount++
        $names = ($matches | ForEach-Object { $_.Name }) -join " | "
        $rows.Add([pscustomobject]@{ Source = $src; Jar = $names; ModId = "-"; Status = ("AMBIGUOUS (" + $matches.Count + " matches)") })
        Write-Warning ("AMBIGUOUS (" + $matches.Count + "): " + $src + " => " + $names)
    }
}

# --- manifest ---
$ok = @($rows | Where-Object { $_.Status -eq "OK" })
$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# libs-manifest")
[void]$md.AppendLine()
[void]$md.AppendLine(("Generated: " + (Get-Date -Format "yyyy-MM-dd HH:mm") + "  |  TSV unique: " + $uniqueSources.Count + "  |  OK: " + $ok.Count + "  |  FAILED/SKIP: " + ($rows.Count - $ok.Count)))
[void]$md.AppendLine()
[void]$md.AppendLine("Compile-only (`compileOnly fileTree(libs)`), never packaged; runtime comes from the client mods dir.")
[void]$md.AppendLine()
[void]$md.AppendLine("| TSV source jar | actual jar (client mods) | modId (from neoforge.mods.toml) | status |")
[void]$md.AppendLine("|---|---|---|---|")
foreach ($r in $rows) {
    $s = $r.Source -replace "\|", "\|"
    $j = $r.Jar -replace "\|", "\|"
    [void]$md.AppendLine("| $s | $j | $($r.ModId) | $($r.Status) |")
}
[System.IO.File]::WriteAllText($manifestPath, $md.ToString(), (New-Object System.Text.UTF8Encoding($true)))

Write-Host ""
Write-Host ("=== DONE: " + $ok.Count + " collected, " + $failCount + " failed ===")
if ($failCount -gt 0) { Write-Error "$failCount TSV entries failed to resolve (see warnings above / manifest)"; exit 1 }
