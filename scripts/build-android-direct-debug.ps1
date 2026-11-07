[CmdletBinding()]
param(
    [string]$AndroidSdk = "",
    [double]$MinimumFreeSpaceGb = 1.0
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot "android"
$apkPath = Join-Path $androidDir "app\build\outputs\apk\debug\app-debug.apk"
$distDir = Join-Path $repoRoot "dist\android"
$distApk = Join-Path $distDir "nexora-mesh-direct-debug.apk"
$desktopApk = Join-Path ([Environment]::GetFolderPath("Desktop")) "NexoraMesh-DirectChat-debug.apk"

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
$requiredBytes = [int64]($MinimumFreeSpaceGb * 1GB)
if ($freeBytes -lt $requiredBytes) {
    $freeGb = [math]::Round($freeBytes / 1GB, 2)
    throw ("Build stopped before writing outputs: only {0} GB is free on {1}:; at least {2} GB is required. Free disk space first. No packages are installed by this script." -f $freeGb, $driveName, $MinimumFreeSpaceGb)
}

$requiredPaths = @(
    (Join-Path $AndroidSdk "platforms\android-35\android.jar"),
    (Join-Path $AndroidSdk "build-tools\35.0.0\aapt.exe"),
    (Join-Path $AndroidSdk "ndk\27.2.12479018"),
    (Join-Path $AndroidSdk "cmake\3.22.1\bin\cmake.exe"),
    (Join-Path $AndroidSdk "licenses\android-sdk-license")
)
foreach ($path in $requiredPaths) {
    if (-not (Test-Path $path)) {
        throw "Required Android SDK component is missing: $path. Review SDK setup with an authorized adult/repository owner."
    }
}

if (-not (Get-Command "java" -ErrorAction SilentlyContinue)) {
    throw "JDK 17 is required. Install/configure it separately; this script does not install packages."
}

$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:PATH = "$(Join-Path $AndroidSdk 'cmake\3.22.1\bin');$(Join-Path $AndroidSdk 'platform-tools');$env:PATH"

Push-Location $repoRoot
try {
    Write-Host "Building the direct Bluetooth Classic test variant."
    Write-Host "BLE, Rust relay and multi-hop features are intentionally disabled in this APK."
    & (Join-Path $androidDir "gradlew.bat") -p $androidDir -PmeshDirectChatOnly=true testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Direct-chat Android build or test compilation failed with exit code $LASTEXITCODE."
    }
    if (-not (Test-Path $apkPath)) {
        throw "Gradle completed but the expected APK was not found: $apkPath"
    }

    New-Item -ItemType Directory -Force -Path $distDir | Out-Null
    Copy-Item -LiteralPath $apkPath -Destination $distApk -Force
    Copy-Item -LiteralPath $apkPath -Destination $desktopApk -Force
    $hash = (Get-FileHash -LiteralPath $distApk -Algorithm SHA256).Hash
    $size = (Get-Item -LiteralPath $distApk).Length

    Write-Host ""
    Write-Host "DIRECT-CHAT APK BUILD SUCCEEDED"
    Write-Host "APK: $distApk"
    Write-Host "Desktop copy: $desktopApk"
    Write-Host "Size (bytes): $size"
    Write-Host "SHA256: $hash"
    Write-Host "Install this exact APK on both Android test phones."
    Write-Host "Keep Wi-Fi and mobile data disabled during the Bluetooth test; leave Bluetooth enabled."
}
finally {
    Pop-Location
}
