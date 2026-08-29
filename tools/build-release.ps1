<#
.SYNOPSIS
Builds and packages the current DJI Unchained VOC release.

.DESCRIPTION
Runs the release-sync guard, unit tests, debug/release lint and APK assembly.
It then verifies APK identity, privacy-sensitive permissions and expected
signature state before creating a clean release directory containing APKs,
an exact committed source snapshot ZIP, SHA-256 manifests and build metadata.

The script never overwrites an existing release directory. Choose a different
OutputRoot or remove the old generated directory deliberately before rerunning.

.PARAMETER OutputRoot
Parent directory for generated release folders. Defaults to a sibling
DJI-Unchained-VOC-release-output directory outside the Git repository.

.PARAMETER ProtocolFixture
Optional path to a pinned dji_protocol checkout. When supplied, the embedded
DJI control packets are compared with that checkout before building.

.PARAMETER ValidateOnly
Validates metadata, tools, snapshot parity and release-relevant Git state
without building or writing release artifacts.

.EXAMPLE
.\tools\build-release.ps1

.EXAMPLE
.\tools\build-release.ps1 -ProtocolFixture ..\..\tmp\dji_protocol-50c71b65

.EXAMPLE
.\tools\build-release.ps1 -ValidateOnly
#>

[CmdletBinding()]
param(
    [string]$OutputRoot,
    [string]$ProtocolFixture,
    [switch]$ValidateOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Stop-Release {
    param([Parameter(Mandatory = $true)][string]$Message)
    throw "Release build stopped: $Message"
}

function Get-NativeCommand {
    param([Parameter(Mandatory = $true)][string[]]$Names)

    foreach ($name in $Names) {
        $command = Get-Command $name -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($null -ne $command) {
            return $command.Source
        }
    }
    return $null
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Write-Host "`n==> $Description" -ForegroundColor Cyan
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
        Stop-Release "$Description failed with exit code $LASTEXITCODE"
    }
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines
    )

    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $Lines, $encoding)
}

function Get-AndroidSdk {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $candidates = New-Object System.Collections.Generic.List[string]
    foreach ($environmentName in @("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
        $value = [System.Environment]::GetEnvironmentVariable($environmentName)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $candidates.Add($value)
        }
    }

    $candidates.Add((Join-Path $RepositoryRoot ".android-sdk"))
    $candidates.Add((Join-Path (Split-Path $RepositoryRoot -Parent) ".android-sdk"))
    $candidates.Add((Join-Path (Split-Path (Split-Path $RepositoryRoot -Parent) -Parent) ".android-sdk"))
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $fullPath = [System.IO.Path]::GetFullPath($candidate)
        if ((Test-Path -LiteralPath (Join-Path $fullPath "platforms")) -and
            (Test-Path -LiteralPath (Join-Path $fullPath "build-tools"))) {
            return $fullPath
        }
    }

    Stop-Release "Android SDK not found. Set ANDROID_HOME or install it in .android-sdk."
}

function Get-LatestBuildToolsDirectory {
    param([Parameter(Mandatory = $true)][string]$AndroidSdk)

    $directories = Get-ChildItem -LiteralPath (Join-Path $AndroidSdk "build-tools") -Directory
    if (-not $directories) {
        Stop-Release "no Android build-tools installation exists under $AndroidSdk"
    }

    return $directories |
        Sort-Object -Property @{ Expression = {
            try { [version]$_.Name } catch { [version]"0.0" }
        } } -Descending |
        Select-Object -First 1
}

function Get-ApkMetadata {
    param(
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$ApkPath
    )

    $output = & $Aapt2 dump badging $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Stop-Release "aapt2 could not inspect $ApkPath"
    }
    $text = $output -join "`n"
    $match = [regex]::Match(
        $text,
        "package:\s+name='([^']+)'\s+versionCode='([^']+)'\s+versionName='([^']+)'"
    )
    if (-not $match.Success) {
        Stop-Release "could not parse package metadata from $ApkPath"
    }

    return [pscustomobject]@{
        PackageName = $match.Groups[1].Value
        VersionCode = $match.Groups[2].Value
        VersionName = $match.Groups[3].Value
        Badging = $text
    }
}

function Assert-ApkPermissions {
    param(
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$ApkPath
    )

    $permissionOutput = & $Aapt2 dump permissions $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Stop-Release "aapt2 could not inspect permissions in $ApkPath"
    }
    $permissionText = $permissionOutput -join "`n"
    $forbidden = @(
        "android.permission.INTERNET",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_CONTACTS",
        "android.permission.GET_ACCOUNTS",
        "android.permission.MANAGE_EXTERNAL_STORAGE"
    )
    $found = @($forbidden | Where-Object { $permissionText -match [regex]::Escape($_) })
    if ($found.Count -gt 0) {
        Stop-Release "privacy-sensitive permissions detected in $ApkPath`: $($found -join ', ')"
    }
}

function Assert-SignatureState {
    param(
        [Parameter(Mandatory = $true)][string]$ApkSigner,
        [Parameter(Mandatory = $true)][string]$ApkPath,
        [Parameter(Mandatory = $true)][bool]$ShouldBeSigned
    )

    & $ApkSigner verify --verbose $ApkPath *> $null
    $verified = $LASTEXITCODE -eq 0
    if ($ShouldBeSigned -and -not $verified) {
        Stop-Release "expected a signed APK but signature verification failed: $ApkPath"
    }
    if (-not $ShouldBeSigned -and $verified) {
        Stop-Release "release APK is signed although the current workflow expects an unsigned artifact: $ApkPath"
    }
}

function Get-TestSummary {
    param([Parameter(Mandatory = $true)][string]$RepositoryRoot)

    $files = Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot "app\build\test-results\testDebugUnitTest") -Filter "TEST-*.xml" -File -ErrorAction SilentlyContinue
    $summary = [ordered]@{ Tests = 0; Failures = 0; Errors = 0; Skipped = 0; Suites = 0 }
    foreach ($file in $files) {
        [xml]$document = Get-Content -LiteralPath $file.FullName -Raw
        $suite = $document.testsuite
        $summary.Tests += [int]$suite.tests
        $summary.Failures += [int]$suite.failures
        $summary.Errors += [int]$suite.errors
        $summary.Skipped += [int]$suite.skipped
        $summary.Suites += 1
    }
    return [pscustomobject]$summary
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path (Split-Path $repoRoot -Parent) "DJI-Unchained-VOC-release-output"
}
$outputRootFull = [System.IO.Path]::GetFullPath($OutputRoot)

$buildFile = Join-Path $repoRoot "app\build.gradle"
$buildText = Get-Content -LiteralPath $buildFile -Raw
$versionNameMatch = [regex]::Match($buildText, 'versionName\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($buildText, 'versionCode\s*=\s*(\d+)')
$applicationIdMatch = [regex]::Match($buildText, 'applicationId\s*=\s*"([^"]+)"')
if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success -or -not $applicationIdMatch.Success) {
    Stop-Release "could not read versionName, versionCode and applicationId from app/build.gradle"
}

$versionName = $versionNameMatch.Groups[1].Value
$versionCode = [int]$versionCodeMatch.Groups[1].Value
$applicationId = $applicationIdMatch.Groups[1].Value
if ($versionName -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') {
    Stop-Release "versionName '$versionName' is not a supported semantic version"
}

$snapshotRelative = "versions/v$versionName"
$snapshotPath = Join-Path $repoRoot $snapshotRelative
if (-not (Test-Path -LiteralPath $snapshotPath -PathType Container)) {
    Stop-Release "missing source snapshot $snapshotRelative"
}

$git = Get-NativeCommand -Names @("git")
if ($null -eq $git) {
    Stop-Release "Git is required to validate and archive the committed source snapshot"
}

$python = Get-NativeCommand -Names @("python", "python3")
$pythonPrefix = @()
if ($null -eq $python) {
    $python = Get-NativeCommand -Names @("py")
    $pythonPrefix = @("-3")
}
if ($null -eq $python) {
    Stop-Release "Python 3 is required for the release-sync and protocol checks"
}

$androidSdk = Get-AndroidSdk -RepositoryRoot $repoRoot
$buildTools = Get-LatestBuildToolsDirectory -AndroidSdk $androidSdk
$runningOnWindows = [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
$aapt2 = Join-Path $buildTools.FullName $(if ($runningOnWindows) { "aapt2.exe" } else { "aapt2" })
$apkSigner = Join-Path $buildTools.FullName $(if ($runningOnWindows) { "apksigner.bat" } else { "apksigner" })
if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
    Stop-Release "aapt2 was not found in $($buildTools.FullName)"
}
if (-not (Test-Path -LiteralPath $apkSigner -PathType Leaf)) {
    Stop-Release "apksigner was not found in $($buildTools.FullName)"
}

$releasePaths = @(
    "app",
    "build.gradle",
    "gradle",
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "settings.gradle",
    "tools/verify_protocol.py",
    $snapshotRelative
)
$gitStatusArguments = @("-C", $repoRoot, "status", "--porcelain", "--untracked-files=all", "--") + $releasePaths
$releaseStatus = & $git @gitStatusArguments
if ($LASTEXITCODE -ne 0) {
    Stop-Release "Git could not inspect release-relevant paths"
}
if ($releaseStatus) {
    Stop-Release "release-relevant source has uncommitted changes:`n$($releaseStatus -join "`n")"
}

Invoke-Checked -FilePath $python -ArgumentList ($pythonPrefix + @((Join-Path $repoRoot "tools\verify_release_sync.py"))) -Description "Verify root/snapshot parity"

if (-not [string]::IsNullOrWhiteSpace($ProtocolFixture)) {
    $protocolPath = [System.IO.Path]::GetFullPath($ProtocolFixture)
    if (-not (Test-Path -LiteralPath (Join-Path $protocolPath "scripts\video_out_mobile.py") -PathType Leaf)) {
        Stop-Release "ProtocolFixture is not a pinned dji_protocol checkout: $protocolPath"
    }
    Invoke-Checked -FilePath $python -ArgumentList ($pythonPrefix + @((Join-Path $repoRoot "tools\verify_protocol.py"), $protocolPath)) -Description "Verify pinned DJI control packets"
}

$commit = (& $git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($commit)) {
    Stop-Release "could not resolve the current Git commit"
}
& $git -C $repoRoot cat-file -e "HEAD`:$snapshotRelative"
if ($LASTEXITCODE -ne 0) {
    Stop-Release "$snapshotRelative is not committed at HEAD"
}

Write-Host "`nValidated DJI Unchained VOC $versionName (versionCode $versionCode)" -ForegroundColor Green
Write-Host "Repository: $repoRoot"
Write-Host "Commit:     $commit"
Write-Host "SDK:        $androidSdk"
Write-Host "Build tools:$($buildTools.Name)"

if ($ValidateOnly) {
    Write-Host "`nValidation completed; no build or release artifacts were written." -ForegroundColor Green
    return
}

[System.Environment]::SetEnvironmentVariable("ANDROID_HOME", $androidSdk, "Process")
[System.Environment]::SetEnvironmentVariable("ANDROID_SDK_ROOT", $androidSdk, "Process")

$gradleWrapper = if ($runningOnWindows) {
    Join-Path $repoRoot "gradlew.bat"
} else {
    Join-Path $repoRoot "gradlew"
}
if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) {
    Stop-Release "Gradle wrapper not found: $gradleWrapper"
}

Push-Location $repoRoot
try {
    Invoke-Checked -FilePath $gradleWrapper -ArgumentList @(
        "clean",
        "testDebugUnitTest",
        "lintDebug",
        "lintRelease",
        "assembleDebug",
        "assembleRelease",
        "--no-daemon"
    ) -Description "Test, lint and assemble debug/release APKs"
} finally {
    Pop-Location
}

$debugSource = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$releaseSource = Join-Path $repoRoot "app\build\outputs\apk\release\app-release-unsigned.apk"
if (-not (Test-Path -LiteralPath $debugSource -PathType Leaf)) {
    Stop-Release "debug APK was not generated at $debugSource"
}
if (-not (Test-Path -LiteralPath $releaseSource -PathType Leaf)) {
    Stop-Release "unsigned release APK was not generated at $releaseSource"
}

$debugMetadata = Get-ApkMetadata -Aapt2 $aapt2 -ApkPath $debugSource
$releaseMetadata = Get-ApkMetadata -Aapt2 $aapt2 -ApkPath $releaseSource
$expectedDebugPackage = "$applicationId.debug"
$expectedDebugVersion = "$versionName-debug"
if ($debugMetadata.PackageName -ne $expectedDebugPackage -or
    $debugMetadata.VersionName -ne $expectedDebugVersion -or
    $debugMetadata.VersionCode -ne $versionCode.ToString()) {
    Stop-Release "debug APK identity mismatch: $($debugMetadata.PackageName) $($debugMetadata.VersionName) code $($debugMetadata.VersionCode)"
}
if ($releaseMetadata.PackageName -ne $applicationId -or
    $releaseMetadata.VersionName -ne $versionName -or
    $releaseMetadata.VersionCode -ne $versionCode.ToString()) {
    Stop-Release "release APK identity mismatch: $($releaseMetadata.PackageName) $($releaseMetadata.VersionName) code $($releaseMetadata.VersionCode)"
}

Assert-ApkPermissions -Aapt2 $aapt2 -ApkPath $debugSource
Assert-ApkPermissions -Aapt2 $aapt2 -ApkPath $releaseSource
Assert-SignatureState -ApkSigner $apkSigner -ApkPath $debugSource -ShouldBeSigned $true
Assert-SignatureState -ApkSigner $apkSigner -ApkPath $releaseSource -ShouldBeSigned $false

$finalDirectory = Join-Path $outputRootFull "v$versionName"
if (Test-Path -LiteralPath $finalDirectory) {
    Stop-Release "output already exists: $finalDirectory"
}
if (-not (Test-Path -LiteralPath $outputRootFull)) {
    New-Item -ItemType Directory -Path $outputRootFull -Force | Out-Null
}

$stageDirectory = Join-Path $outputRootFull (".staging-v{0}-{1}" -f $versionName, [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $stageDirectory | Out-Null

try {
    $debugName = "DJI-Unchained-VOC-$versionName-debug.apk"
    $releaseName = "DJI-Unchained-VOC-$versionName-release-unsigned.apk"
    $sourceName = "DJI-Unchained-VOC-$versionName-source.zip"
    $apkManifestName = "DJI-Unchained-VOC-$versionName-SHA256.txt"
    $sourceManifestName = "DJI-Unchained-VOC-$versionName-source-SHA256.txt"
    $buildInfoName = "DJI-Unchained-VOC-$versionName-build-info.txt"

    $debugDestination = Join-Path $stageDirectory $debugName
    $releaseDestination = Join-Path $stageDirectory $releaseName
    $sourceDestination = Join-Path $stageDirectory $sourceName
    Copy-Item -LiteralPath $debugSource -Destination $debugDestination
    Copy-Item -LiteralPath $releaseSource -Destination $releaseDestination

    Invoke-Checked -FilePath $git -ArgumentList @(
        "-C", $repoRoot,
        "archive",
        "--format=zip",
        "--prefix=DJI-Unchained-VOC-$versionName-source/",
        "-o", $sourceDestination,
        "HEAD`:$snapshotRelative"
    ) -Description "Create exact committed source ZIP"

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($sourceDestination)
    try {
        $expectedPrefix = "DJI-Unchained-VOC-$versionName-source/"
        $forbiddenEntries = @($archive.Entries | Where-Object {
            -not $_.FullName.StartsWith($expectedPrefix, [System.StringComparison]::Ordinal) -or
            $_.FullName -match '(^|/)(build|\.gradle|output)/' -or
            $_.FullName -match '\.apk$'
        })
        if ($forbiddenEntries.Count -gt 0) {
            Stop-Release "source ZIP contains forbidden or incorrectly rooted entries: $($forbiddenEntries.FullName -join ', ')"
        }
    } finally {
        $archive.Dispose()
    }

    $debugHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $debugDestination).Hash
    $releaseHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $releaseDestination).Hash
    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceDestination).Hash
    Write-Utf8File -Path (Join-Path $stageDirectory $apkManifestName) -Lines @(
        "$debugHash  $debugName",
        "$releaseHash  $releaseName"
    )
    Write-Utf8File -Path (Join-Path $stageDirectory $sourceManifestName) -Lines @(
        "$sourceHash  $sourceName"
    )

    $tests = Get-TestSummary -RepositoryRoot $repoRoot
    if ($tests.Tests -le 0 -or $tests.Failures -ne 0 -or $tests.Errors -ne 0) {
        Stop-Release "unit-test result summary is missing or unsuccessful"
    }

    $protocolResult = if ([string]::IsNullOrWhiteSpace($ProtocolFixture)) {
        "Not requested"
    } else {
        "Passed against $([System.IO.Path]::GetFullPath($ProtocolFixture))"
    }
    Write-Utf8File -Path (Join-Path $stageDirectory $buildInfoName) -Lines @(
        "DJI Unchained VOC release build",
        "Version: $versionName",
        "Version code: $versionCode",
        "Application ID: $applicationId",
        "Debug application ID: $expectedDebugPackage",
        "Git commit: $commit",
        "Built UTC: $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "Android SDK: $androidSdk",
        "Android build tools: $($buildTools.Name)",
        "Unit tests: $($tests.Tests) tests, $($tests.Failures) failures, $($tests.Errors) errors, $($tests.Skipped) skipped, $($tests.Suites) suites",
        "Debug/release lint: Passed",
        "Debug/release assembly: Passed",
        "Release sync: Passed",
        "Protocol fixture: $protocolResult",
        "Debug APK signature: Verified",
        "Release APK signature: Intentionally unsigned",
        "Privacy-sensitive permission gate: Passed",
        "",
        "SHA-256:",
        "$debugHash  $debugName",
        "$releaseHash  $releaseName",
        "$sourceHash  $sourceName"
    )

    Move-Item -LiteralPath $stageDirectory -Destination $finalDirectory
} finally {
    if (Test-Path -LiteralPath $stageDirectory) {
        $safePrefix = $outputRootFull.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
        $stageFull = [System.IO.Path]::GetFullPath($stageDirectory)
        if ($stageFull.StartsWith($safePrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path $stageFull -Leaf).StartsWith(".staging-v", [System.StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $stageFull -Recurse -Force
        }
    }
}

Write-Host "`nRelease package created successfully:" -ForegroundColor Green
Write-Host $finalDirectory
Get-ChildItem -LiteralPath $finalDirectory -File |
    Sort-Object Name |
    ForEach-Object { Write-Host ("  {0,-58} {1,10:N0} bytes" -f $_.Name, $_.Length) }
