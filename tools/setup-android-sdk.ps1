<#
.SYNOPSIS
  One-shot Android SDK setup for building GymCoach on this PC (no Android Studio needed).

.DESCRIPTION
  Installs the Android command-line tools into the standard user-local SDK folder
  (%LOCALAPPDATA%\Android\Sdk — the same place Android Studio would use), lets YOU accept the
  Android SDK licence (the script never accepts it for you), installs the platform + build-tools
  that app/build.gradle.kts needs, writes local.properties, and optionally runs the first build.

  Versions come from docs/research/R2-android-toolchain.md (verified 2026-09-04):
    cmdline-tools  commandlinetools-win-15859902_latest.zip
    platform       platforms;android-37.0      (compileSdk 37 — there is no bare "android-37")
    build-tools    build-tools;36.0.0          (AGP 9.4.0 default)
    JDK 17         C:\Users\User\.jdks\jdk-17.0.20.1+1 (already unpacked, no PATH changes made)

.PARAMETER SdkRoot   Where to install the SDK. Default: $env:LOCALAPPDATA\Android\Sdk
.PARAMETER Build     Also run "gradlew :app:assembleDebug" at the end.
.PARAMETER SkipHash  Skip the SHA-256 check of the cmdline-tools zip (the expected hash was copied
                     from developer.android.com, not recomputed; use this only if it is stale).

.EXAMPLE
  pwsh -ExecutionPolicy Bypass -File tools\setup-android-sdk.ps1 -Build
#>
[CmdletBinding()]
param(
  [string]$SdkRoot = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
  [switch]$Build,
  [switch]$SkipHash
)
$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $PSScriptRoot

$CltZipUrl   = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$CltSha256   = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$Packages    = @('platform-tools', 'platforms;android-37.0', 'build-tools;36.0.0')

# --- JDK 17 ---------------------------------------------------------------------------------
$jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
       Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdk) { throw "No JDK 17 found under $env:USERPROFILE\.jdks. Unpack a Temurin 17 zip there first (see docs/build/FIRST_BUILD_CHECKLIST.md)." }
$env:JAVA_HOME = $jdk.FullName
$env:PATH = "$($jdk.FullName)\bin;$env:PATH"
Write-Host "JAVA_HOME = $env:JAVA_HOME"
& java -version 2>&1 | Select-Object -First 1 | Write-Host

# --- cmdline-tools --------------------------------------------------------------------------
$sdkmanager = Join-Path $SdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
if (-not (Test-Path $sdkmanager)) {
  New-Item -ItemType Directory -Force (Join-Path $SdkRoot 'cmdline-tools') | Out-Null
  $zip = Join-Path $env:TEMP 'commandlinetools-win-15859902_latest.zip'
  if (-not (Test-Path $zip)) {
    Write-Host "Downloading $CltZipUrl (about 156 MB) ..."
    Invoke-WebRequest -Uri $CltZipUrl -OutFile $zip
  }
  if (-not $SkipHash) {
    $actual = (Get-FileHash -Algorithm SHA256 $zip).Hash.ToLowerInvariant()
    if ($actual -ne $CltSha256) {
      throw "SHA-256 mismatch for cmdline-tools zip.`n expected $CltSha256`n actual   $actual`nIf Google re-published the zip, re-check https://developer.android.com/studio#command-line-tools-only and re-run with -SkipHash."
    }
    Write-Host "cmdline-tools zip SHA-256 verified."
  }
  $tmp = Join-Path $env:TEMP 'clt-unpack'
  if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
  Expand-Archive -Path $zip -DestinationPath $tmp
  Move-Item (Join-Path $tmp 'cmdline-tools') (Join-Path $SdkRoot 'cmdline-tools\latest')
  Remove-Item -Recurse -Force $tmp
}
Write-Host "sdkmanager = $sdkmanager"

# --- licences: YOUR step ----------------------------------------------------------------------
Write-Host ''
Write-Host '=== Android SDK licence ==='
Write-Host 'sdkmanager will now show the Android SDK terms. Type y to accept each one. This script does not accept them for you.'
& $sdkmanager --sdk_root=$SdkRoot --licenses
if ($LASTEXITCODE -ne 0) { throw "sdkmanager --licenses exited with $LASTEXITCODE" }

# --- packages --------------------------------------------------------------------------------
Write-Host ''
Write-Host "Installing: $($Packages -join ', ')"
& $sdkmanager --sdk_root=$SdkRoot @Packages
if ($LASTEXITCODE -ne 0) { throw "sdkmanager install exited with $LASTEXITCODE" }

# --- local.properties (Gradle needs forward slashes or escaped backslashes) --------------------
$localProps = Join-Path $ProjectRoot 'local.properties'
$sdkDirValue = $SdkRoot -replace '\\', '/'
"sdk.dir=$sdkDirValue" | Set-Content -Path $localProps -Encoding ascii
Write-Host "Wrote $localProps (sdk.dir=$sdkDirValue). settings.gradle.kts now includes :app."

# --- optional first build ---------------------------------------------------------------------
if ($Build) {
  Push-Location $ProjectRoot
  try {
    Write-Host ''
    Write-Host '=== gradlew :app:assembleDebug (first run downloads dependencies; several minutes) ==='
    & .\gradlew.bat :app:assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE — see docs/build/FIRST_BUILD_CHECKLIST.md" }
    $apk = Get-ChildItem app\build\outputs\apk\debug -Filter '*.apk' -Recurse | Select-Object -First 1
    Write-Host "APK: $($apk.FullName)"
  } finally { Pop-Location }
}
Write-Host ''
Write-Host 'Done. Next: open the folder in Android Studio (it reads local.properties) or run  .\gradlew.bat :app:installDebug  with a phone in USB-debugging mode.'
