param(
    [switch]$SkipTests,
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectRoot

if (-not $env:JAVA_HOME) {
    $jdk = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot"
    if (Test-Path $jdk) { $env:JAVA_HOME = $jdk }
}

if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $sdk) { $env:ANDROID_HOME = $sdk }
}

if (-not (Test-Path (Join-Path $ProjectRoot "local.properties")) -and $env:ANDROID_HOME) {
    $escaped = $env:ANDROID_HOME.Replace("\", "\\").Replace(":", "\:")
    [System.IO.File]::WriteAllText((Join-Path $ProjectRoot "local.properties"), "sdk.dir=$escaped`n", [System.Text.UTF8Encoding]::new($false))
}

$needAssets = -not (Test-Path (Join-Path $ProjectRoot "app\src\main\assets\mozc.data")) -or
              -not (Test-Path (Join-Path $ProjectRoot "app\src\main\jniLibs\arm64-v8a\libmozc.so")) -or
              -not (Test-Path (Join-Path $ProjectRoot "app\src\main\jniLibs\x86_64\libmozc.so"))
if ($needAssets) {
    Write-Host "Fetching Mozc native binaries and Meltype dictionaries..."
    python (Join-Path $ProjectRoot "tools\fetch_mozc_assets.py")
    if ($LASTEXITCODE -ne 0) { throw "Asset download/verification failed" }
}

if (-not $SkipTests) {
    Write-Host "Running unit tests..."
    & .\gradlew.bat testDebugUnitTest --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Unit tests failed" }
}

$task = if ($Release) { "assembleRelease" } else { "assembleDebug" }
Write-Host "Building $task..."
& .\gradlew.bat $task --no-daemon
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed" }
