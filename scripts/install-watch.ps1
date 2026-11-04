param(
    [Parameter(Mandatory=$true)][string]$Device,
    [switch]$ConfigureMcp,
    [string]$McpUrl = '',
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if ($Device -notmatch '^[a-zA-Z0-9.:-]+$') { throw 'Invalid device address' }
if ($ConfigureMcp -and [string]::IsNullOrWhiteSpace($McpUrl)) { throw 'Provide your own HTTPS MCP URL with -McpUrl' }
$apk = Join-Path $projectRoot 'dist\watch-mcp.apk'
if (-not (Test-Path -LiteralPath $apk)) { throw 'Run scripts\build.ps1 first' }
& $Adb connect $Device
if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to watch' }
& $Adb -s $Device install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Cannot install watch app; check wireless debugging and pairing' }
if ([int](& $Adb -s $Device shell getprop ro.build.version.sdk) -ge 33) {
    foreach ($design in @('fold', 'dune', 'ridge', 'salt')) {
        & $Adb -s $Device install -r (Join-Path $projectRoot "dist\dial-$design.apk")
        if ($LASTEXITCODE -ne 0) { throw "Cannot install watch face $design" }
    }
}
& $Adb -s $Device shell dumpsys deviceidle whitelist +com.kevin.watchmcp
if ($LASTEXITCODE -ne 0) { throw 'Cannot allow screen-off background networking for watch app' }
if ($ConfigureMcp) {
    & (Join-Path $PSScriptRoot 'configure-watch-mcp.ps1') -Device $Device -McpUrl $McpUrl -Adb $Adb
} else {
    & $Adb -s $Device shell am start -n com.kevin.watchmcp/.MainActivity
    if ($LASTEXITCODE -ne 0) { throw 'Cannot start watch app' }
}
Write-Host 'Installed. The watch connects directly to market data and MCP. No PC bridge is required.'
