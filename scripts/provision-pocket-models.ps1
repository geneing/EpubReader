param(
    [string]$SourceDir,
    [string]$DeviceSerial
)

$ErrorActionPreference = 'Stop'
$packageName = 'com.geneing.epubreader'
$remoteDirectory = "/sdcard/Android/data/$packageName/files"
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$hashManifest = Join-Path $PSScriptRoot '..\app\src\main\assets\pockettts\files-2026.09.sha256'

if ([string]::IsNullOrWhiteSpace($SourceDir)) {
    $SourceDir = Join-Path $PSScriptRoot '..\..\PocketTTS-LiteRT\scripts\out'
}
$SourceDir = (Resolve-Path $SourceDir).Path
if (-not (Test-Path $adb)) { throw "adb was not found at $adb. Install Android platform-tools first." }

$adbPrefix = @()
if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbPrefix += @('-s', $DeviceSerial)
}

function Invoke-Adb([string[]]$Arguments) {
    $output = & $adb @adbPrefix @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
    return $output
}

$files = @{}
foreach ($line in Get-Content $hashManifest) {
    if ($line -match '^([0-9a-fA-F]{64})\s+(\S+)$') {
        $files[$Matches[2]] = $Matches[1].ToLowerInvariant()
    }
}
if ($files.Count -eq 0) { throw "No model checksums were found in $hashManifest." }

$installedPackage = Invoke-Adb @('shell', 'pm', 'path', $packageName)
if (-not ($installedPackage -match [regex]::Escape($packageName))) {
    throw "Install and launch EpubReader on the target device before provisioning Pocket TTS files."
}

Invoke-Adb @('shell', 'mkdir', '-p', $remoteDirectory) | Out-Null
foreach ($entry in $files.GetEnumerator()) {
    $name = $entry.Key
    $path = Join-Path $SourceDir $name
    if (-not (Test-Path $path -PathType Leaf)) { throw "Required model file is missing: $path" }

    $sourceHash = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($sourceHash -ne $entry.Value) {
        throw "SHA-256 mismatch for $name. Expected pinned digest $($entry.Value); got $sourceHash."
    }

    $parent = Split-Path $name -Parent
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        Invoke-Adb @('shell', 'mkdir', '-p', "$remoteDirectory/$parent") | Out-Null
    }

    Write-Host "Pushing $name"
    Invoke-Adb @('push', $path, "$remoteDirectory/$name") | Out-Null
    $remoteHashLine = Invoke-Adb @('shell', 'sha256sum', "$remoteDirectory/$name")
    $remoteHash = [regex]::Match(($remoteHashLine -join "`n"), '^[0-9a-fA-F]{64}').Value.ToLowerInvariant()
    if ($remoteHash -ne $entry.Value) {
        throw "Device SHA-256 mismatch for $name. Expected $($entry.Value); got $remoteHash."
    }
}

Write-Host "Provisioned and SHA-256 verified $($files.Count) Pocket TTS files at $remoteDirectory."
