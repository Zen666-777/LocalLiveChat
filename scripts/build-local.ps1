$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskTools = Join-Path $taskRoot '.tools'
$env:JAVA_HOME = (Get-ChildItem (Join-Path $taskTools 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $taskTools 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-home'
$taskFixedKeystore = Join-Path $taskTools 'signing/mel-chat-debug.keystore'
if (Test-Path -LiteralPath $taskFixedKeystore) {
    $env:MEL_SIGNING_KEYSTORE_FILE = $taskFixedKeystore
}
Set-Location $taskRoot
$taskHasher = [Security.Cryptography.SHA256]::Create()
try {
    $taskRootHash = [BitConverter]::ToString($taskHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($taskRoot))).Replace('-', '').Substring(0, 12).ToLower()
} finally { $taskHasher.Dispose() }
$taskLocalBuildDirectory = Join-Path ([IO.Path]::GetTempPath()) "mel-chat-build-$taskRootHash/app"
[IO.File]::WriteAllText((Join-Path $taskTools 'local-build-output.txt'), $taskLocalBuildDirectory)
# NDK/Clang cannot reliably build from Unicode Windows paths. Copy only public
# sources and the SDK into an ASCII temp workspace; never copy secrets into it.
$taskStage = Join-Path ([IO.Path]::GetTempPath()) "mel-chat-native-$taskRootHash"
$taskSdkStage = Join-Path $taskStage 'android-sdk'
New-Item -ItemType Directory -Force $taskSdkStage | Out-Null
& robocopy $env:ANDROID_HOME $taskSdkStage /E /NFL /NDL /NJH /NJS /NP /R:1 /W:1
if ($LASTEXITCODE -ge 8) { throw 'Could not stage Android SDK' }
$env:ANDROID_HOME = $taskSdkStage
$taskSourceStage = Join-Path $taskStage 'source'
New-Item -ItemType Directory -Force $taskSourceStage | Out-Null
foreach ($taskDirectory in @('app/src', 'app/schemas', 'gradle', 'config')) {
    $taskSource = Join-Path $taskRoot $taskDirectory
    $taskDestination = Join-Path $taskSourceStage $taskDirectory
    # /MIR removes stale staged source files only, never workspace files or SDKs.
    $taskDestination = [IO.Path]::GetFullPath($taskDestination)
    if (!$taskDestination.StartsWith($taskSourceStage + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Mirror destination outside the temporary source workspace' }
    if ((Test-Path -LiteralPath $taskDestination) -and ((Get-Item -LiteralPath $taskDestination -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Refusing to mirror into a junction' }
    & robocopy $taskSource $taskDestination /MIR /XJ /NFL /NDL /NJH /NJS /NP /R:1 /W:1 | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Could not stage $taskDirectory" }
}
foreach ($taskFile in @('settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew.bat', 'app/build.gradle.kts')) {
    Copy-Item -LiteralPath (Join-Path $taskRoot $taskFile) -Destination (Join-Path $taskSourceStage $taskFile)
}
Set-Location $taskSourceStage
# Java 17 reads @argfiles using the Windows ANSI code page. Match Gradle's
# writer to it so worker classpaths containing Japanese characters survive.
$taskTestRun = [Guid]::NewGuid().ToString('N')
& .\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=MS932' "-PmelLocalTestRun=$taskTestRun" "-PmelLocalBuildDirectory=$taskLocalBuildDirectory" testDebugUnitTest lintDebug assembleDebug @args
$taskBuildExitCode = $LASTEXITCODE
if ($taskBuildExitCode -eq 0 -and (Test-Path -LiteralPath $taskFixedKeystore)) {
    $taskCertificate = & (Join-Path $env:ANDROID_HOME 'build-tools/35.0.0/apksigner.bat') verify --print-certs (Join-Path $taskLocalBuildDirectory 'outputs/apk/debug/app-debug.apk')
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    $taskActualCertificate = ($taskCertificate | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest: ' }) -replace '^Signer #1 certificate SHA-256 digest: ', ''
    $taskExpectedCertificate = (Get-Content (Join-Path $taskRoot 'config/signing-certificate.sha256') -Raw).Trim()
    if ($taskActualCertificate -cne $taskExpectedCertificate) { throw 'APK signing certificate does not match the fixed key' }
    Write-Output 'Fixed APK signing certificate verified.'
}
Write-Output "Local build output: $taskLocalBuildDirectory"
exit $taskBuildExitCode
