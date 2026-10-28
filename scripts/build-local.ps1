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
