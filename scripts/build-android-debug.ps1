[CmdletBinding()]
param(
    [string]$AndroidSdk = "",
    [int]$MinimumFreeSpaceGb = 4
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot "android"
$jniRoot = Join-Path $androidDir "app\src\main\jniLibs"
$apkPath = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
$distDir = Join-Path $repoRoot "dist\android"
$distApk = Join-Path $distDir "nexora-mesh-debug.apk"

function Require-Command {
    param([Parameter(Mandatory = $true)][string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found on PATH."
    }
}

if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    $candidates = @("C:\Android", (Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    $AndroidSdk = $candidates | Where-Object { Test-Path (Join-Path $_ "platform-tools\adb.exe") } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk) -or -not (Test-Path $AndroidSdk)) {
    throw "Android SDK not found. Set ANDROID_HOME or pass -AndroidSdk."
}

$driveName = ([System.IO.Path]::GetPathRoot($repoRoot)).Substring(0, 1)
$freeBytes = (Get-PSDrive -Name $driveName).Free
$requiredBytes = [int64]$MinimumFreeSpaceGb * 1GB
if ($freeBytes -lt $requiredBytes) {
    $freeGb = [math]::Round($freeBytes / 1GB, 2)
    throw ("Build stopped before writing outputs: only {0} GB is free on {1}:; at least {2} GB is required. Free disk space first; this script never installs toolchains automatically." -f $freeGb, $driveName, $MinimumFreeSpaceGb)
}

$cmakeExe = Join-Path $AndroidSdk "cmake\3.22.1\bin\cmake.exe"
$ndkDir = Join-Path $AndroidSdk "ndk\27.2.12479018"
$adbExe = Join-Path $AndroidSdk "platform-tools\adb.exe"
$licenseFile = Join-Path $AndroidSdk "licenses\android-sdk-license"

foreach ($path in @($cmakeExe, $ndkDir, $adbExe, $licenseFile)) {
    if (-not (Test-Path $path)) {
        throw "Required Android SDK component is missing: $path. Ask an authorized adult/repository owner to review SDK setup and license status."
    }
}

Require-Command "cargo"
Require-Command "rustup"
Require-Command "java"
Require-Command "link.exe"

if (-not (Get-Command "cargo-ndk" -ErrorAction SilentlyContinue)) {
    throw "cargo-ndk is not installed. Install it in an approved Rust build environment; this script does not install packages."
}

$installedTargets = (& rustup target list --installed) -join " "
foreach ($target in @("aarch64-linux-android", "x86_64-linux-android")) {
    if ($installedTargets -notmatch [regex]::Escape($target)) {
        throw "Rust target '$target' is missing. Add it in the approved build environment, then retry."
    }
}

$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:PATH = "$(Split-Path $cmakeExe);$(Join-Path $AndroidSdk 'platform-tools');$env:PATH"

Push-Location $repoRoot
try {
    Write-Host "Building mesh-core for arm64-v8a and x86_64..."
    & cargo ndk -t arm64-v8a -t x86_64 -o $jniRoot build --release -p mesh-core
    if ($LASTEXITCODE -ne 0) { throw "Android Rust native build failed with exit code $LASTEXITCODE." }

    foreach ($abi in @("arm64-v8a", "x86_64")) {
        $library = Join-Path $jniRoot "$abi\libmesh_core.so"
        if (-not (Test-Path $library)) { throw "Native library output is missing: $library" }
    }

    Write-Host "Running Android JVM tests and instrumentation-source compilation..."
    & (Join-Path $androidDir "gradlew.bat") -p $androidDir testDebugUnitTest compileDebugAndroidTestKotlin
    if ($LASTEXITCODE -ne 0) { throw "Android test tasks failed with exit code $LASTEXITCODE." }

    Write-Host "Building installable debug APK..."
    & (Join-Path $androidDir "gradlew.bat") -p $androidDir assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "APK build failed with exit code $LASTEXITCODE." }
    if (-not (Test-Path $apkPath)) { throw "Expected APK was not found: $apkPath" }

    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    Copy-Item -LiteralPath $apkPath -Destination $distApk -Force
    $hash = (Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash

    Write-Host ""
    Write-Host "BUILD SUCCEEDED"
    Write-Host "APK: $distApk"
    Write-Host "SHA256: $hash"
    Write-Host "Install the same APK on both Android test phones."
}
finally {
    Pop-Location
}
