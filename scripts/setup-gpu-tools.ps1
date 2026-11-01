$ErrorActionPreference = 'Stop'
$taskGpuTools = Join-Path (Split-Path $PSScriptRoot -Parent) '.tools/gpu-deps'
$taskGpuCompiler = Join-Path $taskGpuTools 'llvm-mingw-20251007-ucrt-x86_64'
if (Test-Path (Join-Path $taskGpuCompiler 'bin/clang++.exe')) { return }
New-Item -ItemType Directory -Force $taskGpuTools | Out-Null
$taskGpuArchive = Join-Path $taskGpuTools 'llvm-mingw.zip'
$taskGpuHash = '9b98a1331b9787bbec5da2a2b78704809b91d807c26acc08b9d377292b92baf0'
if (!(Test-Path $taskGpuArchive) -or (Get-FileHash $taskGpuArchive -Algorithm SHA256).Hash.ToLower() -ne $taskGpuHash) {
    Invoke-WebRequest 'https://github.com/mstorsjo/llvm-mingw/releases/download/20251007/llvm-mingw-20251007-ucrt-x86_64.zip' -OutFile $taskGpuArchive
}
if ((Get-FileHash $taskGpuArchive -Algorithm SHA256).Hash.ToLower() -ne $taskGpuHash) { throw 'GPU host compiler checksum mismatch' }
Expand-Archive -LiteralPath $taskGpuArchive -DestinationPath $taskGpuTools -Force
Write-Output 'GPU shader host compiler ready (verified LLVM-MinGW 20251007).'
