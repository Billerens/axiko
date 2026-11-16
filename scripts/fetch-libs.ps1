# Downloads the binary libraries the build needs but git does not keep.
#   scripts\fetch-libs.ps1
$ErrorActionPreference = 'Stop'
$libs = Join-Path $PSScriptRoot '..\app\libs'
New-Item -ItemType Directory -Force $libs | Out-Null

# Speech recognition runtime (sherpa-onnx, Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
$sherpa = Join-Path $libs 'sherpa-onnx-2.0.1.aar'
if (-not (Test-Path $sherpa)) {
    Write-Host 'Downloading sherpa-onnx 2.0.1 (49 MB)...'
    curl.exe -sSL --fail -o $sherpa 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar'
}
Get-ChildItem $libs | Select-Object Name, Length
