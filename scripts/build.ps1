<#
.SYNOPSIS
    一键构建 release APK：运行单元测试、编译、校验签名，并把 APK 和 mapping 归档到 dist/。

.PARAMETER SkipTests
    跳过单元测试。

.PARAMETER Clean
    构建前先执行 gradle clean。

.EXAMPLE
    .\scripts\build.ps1
    .\scripts\build.ps1 -SkipTests -Clean
#>
[CmdletBinding()]
param(
    [switch]$SkipTests,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 期望的 release 证书 SHA-256（signing/release.keystore，CN=vibbow）
$expectedCertSha256 = '57e6c2b5088509f6f7e8a57a4b11c97907f45a354ce84d05d9be23ec1027ec41'

function Write-Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "`n构建失败：$text" -ForegroundColor Red; exit 1 }

# ---------- 环境 ----------
Write-Step '检查构建环境'

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { Fail '找不到 JDK，请安装 JDK 17 以上并设置 JAVA_HOME' }
    $env:JAVA_HOME = $jdk.FullName
}
if (-not $env:ANDROID_HOME -or -not (Test-Path $env:ANDROID_HOME)) {
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
}
$buildTools = "$env:ANDROID_HOME\build-tools\36.0.0"
if (-not (Test-Path "$buildTools\aapt.exe")) { Fail "找不到 Android SDK Build-Tools 36.0.0（$buildTools）" }
if (-not (Test-Path 'signing\keystore.properties') -or -not (Test-Path 'signing\release.keystore')) {
    Fail '缺少签名文件 signing\release.keystore 或 signing\keystore.properties，请从备份中恢复'
}
if (-not (Test-Path 'local.properties')) {
    $sdkDir = $env:ANDROID_HOME -replace '\\', '\\' -replace ':', '\:'
    "sdk.dir=$sdkDir" | Out-File -Encoding ascii local.properties
}

Write-Host "JAVA_HOME    = $env:JAVA_HOME"
Write-Host "ANDROID_HOME = $env:ANDROID_HOME"

# ---------- 编译 ----------
$tasks = @()
if ($Clean) { $tasks += 'clean' }
if (-not $SkipTests) { $tasks += 'testDebugUnitTest' }
$tasks += 'assembleRelease'

Write-Step "Gradle: $($tasks -join ' ')"
$sw = [Diagnostics.Stopwatch]::StartNew()
& .\gradlew.bat @tasks --console=plain
if ($LASTEXITCODE -ne 0) { Fail "Gradle 返回 $LASTEXITCODE" }
$sw.Stop()

# ---------- 校验 ----------
Write-Step '校验 APK'
$apk = 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { Fail "找不到 $apk" }

$badging = & "$buildTools\aapt.exe" dump badging $apk | Out-String
$package = [regex]::Match($badging, "package: name='([^']+)'").Groups[1].Value
$versionCode = [regex]::Match($badging, " versionCode='(\d+)'").Groups[1].Value
$versionName = [regex]::Match($badging, " versionName='([^']+)'").Groups[1].Value

# apksigner.bat 需要经过 cmd 调用
$certs = cmd /c "`"$buildTools\apksigner.bat`" verify --print-certs `"$apk`" 2>nul" | Out-String
if ($LASTEXITCODE -ne 0) { Fail 'APK 签名校验失败' }
$certSha256 = [regex]::Match($certs, 'certificate SHA-256 digest: ([0-9a-f]+)').Groups[1].Value
if ($certSha256 -ne $expectedCertSha256) {
    Fail "签名证书不对！期望 $expectedCertSha256，实际 $certSha256。用错误的密钥发布会导致用户无法覆盖升级"
}

# ---------- 归档 ----------
Write-Step '归档到 dist\'
New-Item -ItemType Directory -Force 'dist\mapping' | Out-Null
$distApk = 'dist\GWM-DVR-Download.apk'
Copy-Item $apk $distApk -Force
$mapping = 'app\build\outputs\mapping\release\mapping.txt'
if (Test-Path $mapping) { Copy-Item $mapping "dist\mapping\mapping-$versionCode.txt" -Force }

$testSummary = '已跳过'
if (-not $SkipTests) {
    $total = 0; $failed = 0
    Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$x = Get-Content $_.FullName
        $total += [int]$x.testsuite.tests
        $failed += [int]$x.testsuite.failures + [int]$x.testsuite.errors
    }
    $testSummary = "$total 个，失败 $failed 个"
}

Write-Host ''
Write-Host '构建成功' -ForegroundColor Green
Write-Host "  包名      $package"
Write-Host "  版本      $versionName ($versionCode)"
Write-Host "  签名      OK ($($certSha256.Substring(0, 16))...)"
Write-Host "  单元测试  $testSummary"
Write-Host "  耗时      $([math]::Round($sw.Elapsed.TotalSeconds)) 秒"
Write-Host "  APK       $root\$distApk ($([math]::Round((Get-Item $distApk).Length / 1MB, 2)) MB)"
