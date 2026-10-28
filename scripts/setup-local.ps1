$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskTools = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force $taskTools | Out-Null
if (!(Test-Path (Join-Path $taskTools 'jdk-release.json'))) {
    Invoke-WebRequest -Uri 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse' -OutFile (Join-Path $taskTools 'jdk-release.json')
}
$taskRelease = Get-Content (Join-Path $taskTools 'jdk-release.json') -Raw | ConvertFrom-Json
$taskPackage = $taskRelease[0].binary.package
if (!(Test-Path (Join-Path $taskTools 'jdk'))) {
    Invoke-WebRequest -Uri $taskPackage.link -OutFile (Join-Path $taskTools 'jdk.zip')
    if ((Get-FileHash (Join-Path $taskTools 'jdk.zip') -Algorithm SHA256).Hash.ToLower() -ne $taskPackage.checksum) { throw 'JDK checksum mismatch' }
    Expand-Archive -LiteralPath (Join-Path $taskTools 'jdk.zip') -DestinationPath (Join-Path $taskTools 'jdk')
}
if (!(Test-Path (Join-Path $taskTools 'android-sdk/cmdline-tools/latest/bin/sdkmanager.bat'))) {
    Invoke-WebRequest -Uri 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -OutFile (Join-Path $taskTools 'android-cli.zip')
    Expand-Archive -LiteralPath (Join-Path $taskTools 'android-cli.zip') -DestinationPath (Join-Path $taskTools 'android-cli') -Force
    New-Item -ItemType Directory -Force (Join-Path $taskTools 'android-sdk/cmdline-tools') | Out-Null
    $taskMoveSource = [IO.Path]::GetFullPath((Join-Path $taskTools 'android-cli/cmdline-tools'))
    $taskMoveTarget = [IO.Path]::GetFullPath((Join-Path $taskTools 'android-sdk/cmdline-tools/latest'))
    foreach ($taskMovePath in @($taskMoveSource, $taskMoveTarget)) {
        if (!$taskMovePath.StartsWith($taskTools + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'SDK path outside workspace tools directory' }
    }
    Move-Item -LiteralPath $taskMoveSource -Destination $taskMoveTarget
}
$env:JAVA_HOME = (Get-ChildItem (Join-Path $taskTools 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $taskTools 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-home'
# Licenses are only accepted when this setup script is deliberately run.
1..100 | ForEach-Object { 'y' } | & (Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat') --licenses | Out-Null
& (Join-Path $env:ANDROID_HOME 'cmdline-tools/latest/bin/sdkmanager.bat') 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0) { throw 'SDK installation failed' }
Write-Output 'JDK and Android SDK ready in .tools'
