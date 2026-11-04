param(
    [Parameter(Mandatory=$true)][string]$Device,
    [Parameter(Mandatory=$true)][string]$McpUrl,
    [string]$Adb = 'adb'
)
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^[a-zA-Z0-9.:-]+$') { throw 'Invalid device address' }
$parsedUri = [Uri]$McpUrl
if ($parsedUri.Scheme -ne 'https' -or $parsedUri.UserInfo -or $parsedUri.Query -or $parsedUri.Fragment) { throw 'A plain HTTPS MCP URL is required' }
Write-Host 'Configure MCP directly on your watch. No PC bridge service is used.'
Write-Host "Watch: $Device"
$secureClientKey = Read-Host 'Paste the MCP client Key, then press Enter (input is hidden)' -AsSecureString
$keyPointer = [IntPtr]::Zero
try {
    $keyPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureClientKey)
    $clientKeyText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($keyPointer).Trim()
    if (-not $clientKeyText) { throw 'Client Key cannot be empty' }
    $addressB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($McpUrl.TrimEnd('/')))
    $clientKeyB64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($clientKeyText))
    & $Adb -s $Device shell am force-stop com.kevin.watchmcp
    if ($LASTEXITCODE -ne 0) { throw 'Cannot stop watch application' }
    & $Adb -s $Device shell am start -n com.kevin.watchmcp/.MainActivity --es mcp_url_b64 $addressB64 --es mcp_key_b64 $clientKeyB64
    if ($LASTEXITCODE -ne 0) { throw 'Cannot configure watch application' }
    Write-Host 'Saved on watch. Swipe left on the watch to check the MCP connection.'
} finally {
    if ($keyPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($keyPointer) }
    $secureClientKey.Dispose()
    Remove-Variable clientKeyText,clientKeyB64 -ErrorAction SilentlyContinue
}
