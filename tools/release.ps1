<#
.SYNOPSIS
    TimestampRecorder - one-click build, sign and release script.
.DESCRIPTION
    1) Build the release APK with JDK 17 (version read from app\build.gradle)
    2) zipalign + sign with release.keystore via apksigner
    3) Verify the signature against the release keystore fingerprint
    4) git tag + push, then create a GitHub Release with the APK attached

    By DEFAULT the script PUBLISHES (tag + GitHub Release). Iterating the
    version number is therefore a single command. Pass -SkipRelease when you
    only want a signed APK and do not want to tag or publish.

    The GitHub Release body is read from docs\release-notes\v<version>.md
    (written in Chinese, per CONTRIBUTING.md). Publishing prefers the
    authenticated gh CLI; if gh is unavailable it falls back to the REST API
    using $env:GITHUB_TOKEN.

    Security: the keystore password is NOT hardcoded (this script is committed
    to the repo). It is read from $env:TSR_KEYSTORE_PASS, or prompted at runtime.

    Note: this file is intentionally ASCII-only so it needs no UTF-8 BOM.
.PARAMETER SkipRelease
    Only build and sign the APK. Do NOT create a git tag or a GitHub Release.
.PARAMETER CreateRelease
    Deprecated. Publishing is now the default, so this switch is ignored; it is
    kept only so older command lines (release.ps1 -CreateRelease) still run.
.EXAMPLE
    .\tools\release.ps1
    Build, sign, tag and publish vX.Y.Z (the normal one-command release).
.EXAMPLE
    .\tools\release.ps1 -SkipRelease
    Only produce the signed APK; do not tag or publish.
.NOTES
    If script execution is blocked, allow it for the current process only:
        Set-ExecutionPolicy -ExecutionPolicy Bypass -Scope Process -Force
    or launch it once with:
        powershell -ExecutionPolicy Bypass -File .\tools\release.ps1

    Publishing needs an authenticated gh CLI. If 'gh auth status' reports that
    you are not logged in, run:
        gh auth login
    (REST fallback: set $env:GITHUB_TOKEN to a token with 'repo' scope.)
#>
param(
    [switch]$SkipRelease,
    [switch]$CreateRelease
)

$ErrorActionPreference = "Stop"

# GitHub repository that receives tags and Releases (owner/name).
$repo = "baoru0908/timestamp-recorder"

# ---------- helpers ----------
# Locate the gh CLI: prefer one on PATH, then the usual Windows install dirs.
function Get-GhCli {
    $cmd = Get-Command gh -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @()
    if ($env:ProgramFiles) { $candidates += (Join-Path $env:ProgramFiles "GitHub CLI\gh.exe") }
    if (${env:ProgramFiles(x86)}) { $candidates += (Join-Path ${env:ProgramFiles(x86)} "GitHub CLI\gh.exe") }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA "Programs\GitHub CLI\gh.exe") }
    foreach ($c in $candidates) { if ($c -and (Test-Path $c)) { return $c } }
    return $null
}

# Run a native tool, capturing merged stdout/stderr and the exit code without
# letting PowerShell 5.1 turn a tool's stderr into a terminating error.
function Invoke-Native {
    param([string]$Exe, [string[]]$CliArgs)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = (& $Exe @CliArgs 2>&1 | Out-String)
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return [pscustomobject]@{ ExitCode = $code; Output = $out }
}

# Git with a proxy fallback: some machines have a global git http.proxy pointing
# at a local Clash port that may be down. Try normally first, then retry with the
# proxies disabled so publishing works whether or not the proxy is running.
function Invoke-Git {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs)
    $r = Invoke-Native "git" $GitArgs
    if ($r.ExitCode -ne 0) {
        Write-Host "  'git $($GitArgs -join ' ')' failed; retrying with proxies disabled ..." -ForegroundColor DarkYellow
        $r = Invoke-Native "git" (@("-c", "http.proxy=", "-c", "https.proxy=") + $GitArgs)
    }
    return $r
}

# ---------- paths and environment ----------
$root = Split-Path -Parent $PSScriptRoot
$jdk  = "C:\Users\Baoru Lee\AppData\Local\Programs\Microsoft\jdk-17.0.20.1+1"
$sdk  = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$bt   = Join-Path $sdk "build-tools\34.0.0"

# ANDROID_HOME is NOT persisted as a user env var on this machine, so it must be
# set here - otherwise Gradle fails with "SDK location not found".
$env:JAVA_HOME        = $jdk
$env:ANDROID_HOME     = $sdk
$env:ANDROID_SDK_ROOT = $sdk
$env:PATH = "$jdk\bin;$env:PATH"

Set-Location $root
Write-Host "Project root: $root" -ForegroundColor Cyan

if ($CreateRelease) {
    Write-Host "Note: -CreateRelease is deprecated and ignored; publishing is now the default." -ForegroundColor DarkYellow
}

# ---------- version ----------
$gradle = Get-Content (Join-Path $root "app\build.gradle") -Raw
$versionName = ([regex]'versionName\s+"([^"]+)"').Match($gradle).Groups[1].Value
if ([string]::IsNullOrWhiteSpace($versionName)) { throw "Cannot parse versionName from app\build.gradle" }
Write-Host "Version: v$versionName" -ForegroundColor Cyan

# ---------- 1. build ----------
Write-Host ""
Write-Host "[1/3] Building assembleRelease ..." -ForegroundColor Yellow
& (Join-Path $root "gradlew.bat") assembleRelease
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

$unsigned = Join-Path $root "app\build\outputs\apk\release\app-release-unsigned.apk"
if (-not (Test-Path $unsigned)) { throw "Output not found: $unsigned" }

# ---------- 2. align + sign ----------
Write-Host ""
Write-Host "[2/3] Aligning and signing ..." -ForegroundColor Yellow
$aligned = Join-Path $env:TEMP "tsr-aligned.apk"
$signed  = Join-Path $root "TimestampRecorder_v$versionName.apk"

$ksPass = $env:TSR_KEYSTORE_PASS
if ([string]::IsNullOrWhiteSpace($ksPass)) {
    $ksPass = Read-Host "Keystore password (alias=timestamp)"
}

& (Join-Path $bt "zipalign.exe") -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

& (Join-Path $bt "apksigner.bat") sign --ks (Join-Path $root "release.keystore") `
    --ks-key-alias timestamp --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --out $signed $aligned
if ($LASTEXITCODE -ne 0) { throw "Signing failed" }

Write-Host "Built: $signed" -ForegroundColor Green

# ---------- 2b. verify signature (release red line) ----------
# Users upgrade by overwriting the installed app, so every published APK must
# carry the release keystore signature. If signing silently fell back to the
# debug key, Android would refuse the upgrade with a signature conflict.
# Expected = release.keystore cert SHA-256 (CN=TimestampRecorder).
$expectCert = "cca83079a87053a579262dfd8db5191af36349aacd2db26b5c5c67daf8f976ce"
$prevEAP = $ErrorActionPreference
$ErrorActionPreference = "Continue"
$certOut = (& (Join-Path $bt "apksigner.bat") verify --print-certs $signed 2>&1) | Out-String
$ErrorActionPreference = $prevEAP
if ($certOut -match "SHA-256 digest:\s*([0-9a-fA-F]+)") {
    $actualCert = $Matches[1].ToLower()
} else {
    throw "Cannot read the signature of $signed - refusing to publish an unverifiable APK."
}
if ($actualCert -ne $expectCert) {
    throw "Signature mismatch: got $actualCert, expected $expectCert. The APK was not signed with release.keystore, so users could not overwrite-install it."
}
Write-Host "Signature verified: CN=TimestampRecorder ($($actualCert.Substring(0,16))...)" -ForegroundColor Green

# ---------- 3. tag + GitHub Release (default) ----------
if ($SkipRelease) {
    Write-Host ""
    Write-Host "[3/3] Skipped (-SkipRelease). Tag and GitHub Release were NOT created." -ForegroundColor DarkGray
    Write-Host "      To publish later:  git tag v$versionName ; git push origin v$versionName" -ForegroundColor DarkGray
    exit 0
}

Write-Host ""
Write-Host "[3/3] Tagging and publishing GitHub Release ..." -ForegroundColor Yellow

$tag       = "v$versionName"
$notesFile = Join-Path $root "docs\release-notes\$tag.md"

# --- tag: idempotent ---- (creating an existing tag is not fatal) ---
$local = Invoke-Git tag --list $tag
if ($local.ExitCode -eq 0 -and $local.Output.Trim()) {
    Write-Host "  Local tag $tag already exists - skipping creation." -ForegroundColor DarkGray
} else {
    $r = Invoke-Git tag $tag
    if ($r.ExitCode -ne 0) { throw "Failed to create tag ${tag}:`n$($r.Output)" }
    Write-Host "  Created tag $tag" -ForegroundColor Green
}

# --- push tag: idempotent (skip if origin already has it) ---
$remote = Invoke-Git ls-remote --tags origin "refs/tags/$tag"
if ($remote.ExitCode -eq 0 -and $remote.Output -match [regex]::Escape("refs/tags/$tag")) {
    Write-Host "  Remote tag $tag already present - skipping push." -ForegroundColor DarkGray
} else {
    $r = Invoke-Git push origin $tag
    if ($r.ExitCode -ne 0) { throw "Failed to push tag ${tag}:`n$($r.Output)" }
    Write-Host "  Pushed tag $tag to origin" -ForegroundColor Green
}

# --- release body source ---
if (Test-Path $notesFile) {
    $useNotesFile = $true
    Write-Host "  Release notes: docs/release-notes/$tag.md" -ForegroundColor Green
} else {
    $useNotesFile = $false
    $fallbackBody = "See the commit history and the assets below."
    Write-Host "  WARNING: docs/release-notes/$tag.md not found." -ForegroundColor DarkYellow
    Write-Host "           Using a short fallback body - please add a Chinese release note." -ForegroundColor DarkYellow
}

# --- preferred channel: authenticated gh CLI ---
$published = $false
$gh = Get-GhCli
if ($gh) {
    $auth = Invoke-Native $gh @("auth", "status")
    if ($auth.ExitCode -eq 0) {
        $view = Invoke-Native $gh @("release", "view", $tag, "--repo", $repo)
        if ($view.ExitCode -eq 0) {
            Write-Host "  GitHub Release $tag already exists - skipping (nothing to do)." -ForegroundColor DarkGray
            $published = $true
        } else {
            $ghArgs = @("release", "create", $tag, "--repo", $repo, "--title", $tag)
            if ($useNotesFile) { $ghArgs += @("--notes-file", $notesFile) }
            else               { $ghArgs += @("--notes", $fallbackBody) }
            $ghArgs += $signed
            $res = Invoke-Native $gh $ghArgs
            if ($res.Output.Trim()) { Write-Host $res.Output.Trim() }
            if ($res.ExitCode -ne 0) { throw "gh release create failed for $tag (see output above)." }
            Write-Host "  GitHub Release $tag created via gh CLI." -ForegroundColor Green
            $published = $true
        }
    } else {
        Write-Host "  gh CLI found but NOT authenticated." -ForegroundColor DarkYellow
        Write-Host "  Fix: run 'gh auth login', then re-run this script. Falling back to REST ..." -ForegroundColor DarkYellow
        if ($auth.Output.Trim()) { Write-Host "  ($($auth.Output.Trim()))" -ForegroundColor DarkGray }
    }
} else {
    Write-Host "  gh CLI not found. Falling back to the REST API ..." -ForegroundColor DarkYellow
}

# --- fallback channel: REST API with $env:GITHUB_TOKEN ---
if (-not $published) {
    $token = $env:GITHUB_TOKEN
    if ([string]::IsNullOrWhiteSpace($token)) {
        throw ("Publishing failed: neither an authenticated gh CLI nor `$env:GITHUB_TOKEN is available.`n" +
               "Fix: run 'gh auth login' (recommended), or set `$env:GITHUB_TOKEN to a token with 'repo' scope.")
    }

    $headers = @{
        "Authorization" = "Bearer $token"
        "Accept"        = "application/vnd.github+json"
    }

    # idempotent: skip if a Release already exists for this tag
    $existing = $null
    try {
        $rels = Invoke-RestMethod -Method Get -Headers $headers `
            -Uri "https://api.github.com/repos/$repo/releases?per_page=100"
        $existing = $rels | Where-Object { $_.tag_name -eq $tag } | Select-Object -First 1
    } catch {
        Write-Host "  (could not list existing releases: $($_.Exception.Message))" -ForegroundColor DarkGray
    }
    if ($existing) {
        Write-Host "  GitHub Release $tag already exists - skipping (nothing to do)." -ForegroundColor DarkGray
        Write-Host "  Existing: $($existing.html_url)" -ForegroundColor DarkGray
        exit 0
    }

    $body = if ($useNotesFile) { Get-Content $notesFile -Raw -Encoding UTF8 } else { $fallbackBody }
    $json = @{ tag_name = $tag; name = $tag; body = $body } | ConvertTo-Json -Depth 5
    # Send UTF-8 bytes explicitly so the Chinese release note is not mangled.
    $jsonBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $rel = Invoke-RestMethod -Method Post `
        -Uri "https://api.github.com/repos/$repo/releases" `
        -Headers $headers -Body $jsonBytes -ContentType "application/json; charset=utf-8"

    $uploadUri = $rel.upload_url -replace '\{.*\}', ''
    $assetName = Split-Path $signed -Leaf
    Invoke-RestMethod -Method Post -Uri "$($uploadUri)?name=$assetName" `
        -Headers $headers -InFile $signed `
        -ContentType "application/vnd.android.package-archive" | Out-Null

    Write-Host "  GitHub Release created via REST API: $($rel.html_url)" -ForegroundColor Green
}
