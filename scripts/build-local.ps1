$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskTools = Join-Path $taskRoot '.tools'
$env:JAVA_HOME = (Get-ChildItem (Join-Path $taskTools 'jdk') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $taskTools 'android-sdk'
$env:GRADLE_USER_HOME = Join-Path $taskTools 'gradle-home'
Set-Location $taskRoot
# Java 17 reads @argfiles using the Windows ANSI code page. Match Gradle's
# writer to it so worker classpaths containing Japanese characters survive.
& .\gradlew.bat --no-daemon '-Dorg.gradle.jvmargs=-Xmx3g -Dfile.encoding=MS932' testDebugUnitTest lintDebug assembleDebug @args
exit $LASTEXITCODE
