param([switch]$Demo, [int]$Port = 8765)
$ErrorActionPreference = "Stop"
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not (Test-Path -LiteralPath ".venv\Scripts\python.exe")) {
        & uv venv .venv
        if ($LASTEXITCODE -ne 0) { throw "创建运行环境失败，请安装 uv" }
    }
    & uv pip install --python .venv\Scripts\python.exe -r bridge\requirements.txt
    if ($LASTEXITCODE -ne 0) { throw "安装依赖失败" }
    $bridgeArgs = @("-m", "bridge.server", "--port", "$Port")
    if ($Demo) { $bridgeArgs += "--demo" }
    & .\.venv\Scripts\python.exe @bridgeArgs
} finally { Pop-Location }
