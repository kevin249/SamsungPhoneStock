param(
    [Parameter(Mandatory=$true)][string]$WatchIp,
    [Parameter(Mandatory=$true)][string]$ComputerIp
)
# Run as administrator. Allow only this watch to reach the bridge on this PC.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$runtimePath = Join-Path $projectRoot '.runtime'
try {
    $parsedWatchIp = [System.Net.IPAddress]::Parse($WatchIp)
    $parsedComputerIp = [System.Net.IPAddress]::Parse($ComputerIp)
    if ($parsedWatchIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork -or $parsedComputerIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) {
        throw 'IPv4 addresses are required'
    }
    $ruleName = 'SamsungWatchMCP-Bridge-8765'
    $existingRule = Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
    if ($existingRule) {
        $existingRule | Set-NetFirewallRule -Enabled True -Direction Inbound -Action Allow -Profile Any -Protocol TCP -LocalPort 8765 -RemoteAddress $WatchIp -LocalAddress $ComputerIp | Out-Null
    } else {
        New-NetFirewallRule -Name $ruleName -DisplayName 'Samsung Watch MCP bridge (paired watch only)' -Direction Inbound -Action Allow -Profile Any -Protocol TCP -LocalPort 8765 -RemoteAddress $WatchIp -LocalAddress $ComputerIp | Out-Null
    }
    $result = @{ ok = $true; watch = $WatchIp; computer = $ComputerIp; port = 8765 }
} catch {
    $result = @{ ok = $false; error = $_.Exception.Message }
}
New-Item -ItemType Directory -Force -Path $runtimePath | Out-Null
$jsonResult = $result | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText((Join-Path $runtimePath 'firewall-result.json'), $jsonResult, (New-Object System.Text.UTF8Encoding($false)))
if (-not $result.ok) { exit 1 }
