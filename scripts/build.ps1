param([string]$Toolchain = "")
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
$gradleCommand = Join-Path $projectRoot "gradlew.bat"
if ($Toolchain) {
    if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $Toolchain "jdk-17.0.20.1+1" }
    if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $Toolchain "android-sdk" }
    $localGradle = Join-Path $Toolchain "gradle-8.9\bin\gradle.bat"
    if (Test-Path -LiteralPath $localGradle) { $gradleCommand = $localGradle }
}
if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME "bin\java.exe"))) { throw "请先配置 JAVA_HOME（JDK 17）" }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Push-Location $projectRoot
try {
    & $gradleCommand --no-daemon assembleDebug lintDebug testDebugUnitTest
    if ($LASTEXITCODE -ne 0) { throw "手表应用构建或检查失败" }
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot "dist") | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot "wear\build\outputs\apk\debug\wear-debug.apk") -Destination (Join-Path $projectRoot "dist\watch-mcp.apk")
    Copy-Item -LiteralPath (Join-Path $projectRoot "phone\build\outputs\apk\debug\phone-debug.apk") -Destination (Join-Path $projectRoot "dist\phone-kline.apk")
    foreach ($design in @('fold', 'dune', 'ridge', 'salt')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot "watchface\build\outputs\apk\$design\debug\watchface-$design-debug.apk") -Destination (Join-Path $projectRoot "dist\dial-$design.apk")
    }
    Write-Host "可安装 APK：$projectRoot\dist\watch-mcp.apk"
    Write-Host "手机 APK：$projectRoot\dist\phone-kline.apk"
} finally { Pop-Location }
