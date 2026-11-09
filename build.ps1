# Builds the debug APK (and runs the unit tests) without Android Studio.
#
# Tools are expected in $ToolsDir (default D:\Tools\android-build):
#   jdk\<jdk-17 folder>   JDK 17
#   sdk\                  Android SDK with platforms;android-35 and build-tools;34.0.0
#   gradle-home\          Gradle cache (downloaded on the first build if empty)
# Override with:  .\build.ps1 -ToolsDir E:\my-tools   or the SMSTG_TOOLS environment variable.
#
# Usage:
#   .\build.ps1             unit tests + APK, copied to .\SmsToTelegram.apk
#   .\build.ps1 -SkipTests  APK only

param(
    [string]$ToolsDir = $(if ($env:SMSTG_TOOLS) { $env:SMSTG_TOOLS } else { "D:\Tools\android-build" }),
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$project = $PSScriptRoot

$jdk = Get-ChildItem "$ToolsDir\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) { throw "JDK 17 not found in $ToolsDir\jdk" }
if (-not (Test-Path "$ToolsDir\sdk\platforms")) { throw "Android SDK not found in $ToolsDir\sdk" }

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = "$ToolsDir\sdk"
$env:GRADLE_USER_HOME = "$ToolsDir\gradle-home"

$tasks = @("assembleDebug")
if (-not $SkipTests) { $tasks = @("testDebugUnitTest") + $tasks }

Push-Location $project
try {
    & .\gradlew.bat @tasks --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed (exit code $LASTEXITCODE)" }
    Copy-Item "app\build\outputs\apk\debug\app-debug.apk" "SmsToTelegram.apk" -Force
    Write-Host "APK: $project\SmsToTelegram.apk"
} finally {
    Pop-Location
}
