<#
.SYNOPSIS
Builds the current development APK into output/v<version>/.

.DESCRIPTION
Runs unit tests, debug lint and debug assembly, then copies the signed debug APK
to the repository's single Git-ignored output tree. Rebuilding the same
development version replaces only that version's generated debug APK.

.EXAMPLE
.\tools\build-development.ps1
#>

[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$buildFile = Join-Path $repoRoot "app\build.gradle"
$buildText = Get-Content -LiteralPath $buildFile -Raw
$versionMatch = [regex]::Match($buildText, 'versionName\s*=\s*"([^"]+)"')
if (-not $versionMatch.Success) {
    throw "Could not read versionName from $buildFile"
}

$versionName = $versionMatch.Groups[1].Value
if (-not $versionName.EndsWith("-dev")) {
    throw "Development build stopped: version '$versionName' does not end in -dev"
}

$gradle = Join-Path $repoRoot "gradlew.bat"
& $gradle testDebugUnitTest lintDebug assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "Development build failed with exit code $LASTEXITCODE"
}

$sourceApk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path -LiteralPath $sourceApk)) {
    throw "Expected debug APK was not produced: $sourceApk"
}

$versionDirectory = Join-Path $repoRoot "output\v$versionName"
$destinationApk = Join-Path $versionDirectory "DJI-Unchained-VOC-$versionName-debug.apk"
New-Item -ItemType Directory -Path $versionDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceApk -Destination $destinationApk -Force

$hash = Get-FileHash -LiteralPath $destinationApk -Algorithm SHA256
Write-Host "`nDevelopment APK created:" -ForegroundColor Green
Write-Host $destinationApk
Write-Host "SHA-256: $($hash.Hash)"
