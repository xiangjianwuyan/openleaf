# OpenLeaf QA-1 —— 项目内一键 core 测试 runner
#
# 从 .tools/qa-coretests.ps1 移植；$src 改为当前仓库根（不再写死 0.10.2-fixed）。
# 约定：JDK17 / UTF-8 / --release 17 / ASCII java.io.tmpdir / 排除 CatalogProtocolTest /
#       逐类 java 启动 / 以进程 exit code 统计 PASS/FAIL。
# 用法：powershell -ExecutionPolicy Bypass -File validation/run-coretests.ps1

$ErrorActionPreference = "Continue"

$toolsRoot = Join-Path $env:USERPROFILE "Documents\deepseek-harness\default-workspace\.tools"
$src = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).ProviderPath
$out = Join-Path $toolsRoot "tmp-build\core-tests-qa-061"
$logs = Join-Path $toolsRoot "logs"

# 中文路径下 File.createTempFile 需要 ASCII 的 java.io.tmpdir；Public 目录为纯 ASCII。
$publicRoot = $env:PUBLIC
if (-not $publicRoot) { $publicRoot = "C:\Users\Public" }
$tmp = Join-Path $publicRoot "openleaf-coretests-tmp\core-tests-qa-061"

New-Item -ItemType Directory -Force -Path $tmp | Out-Null
New-Item -ItemType Directory -Force -Path $logs | Out-Null
if (Test-Path -LiteralPath $out) { Remove-Item -Recurse -Force -LiteralPath $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$jdk = (Get-ChildItem -LiteralPath (Join-Path $toolsRoot 'jdk') -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') } |
        Select-Object -First 1).FullName
if (-not $jdk) { Write-Output "=== NO JDK17 FOUND under $toolsRoot\jdk ==="; exit 2 }
$javac = Join-Path $jdk "bin\javac.exe"
$java = Join-Path $jdk "bin\java.exe"

# 受限环境会禁止子进程 JVM 在 Public 下建目录：用最小 Java 探针验证 tmpdir 真正可用。
$probeDir = Join-Path $toolsRoot "tmp-build\ol-tmp-probe"
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$probeSrc = Join-Path $probeDir "TmpProbe.java"
[System.IO.File]::WriteAllText($probeSrc, 'import java.nio.file.*; public class TmpProbe { public static void main(String[] a) throws Exception { Files.createTempDirectory("probe"); } }')
& $javac -encoding UTF-8 --release 17 -d $probeDir $probeSrc 2>$null
& $java "-Djava.io.tmpdir=$tmp" -cp $probeDir TmpProbe 2>$null
if ($LASTEXITCODE -ne 0) {
    $tmp = Join-Path $toolsRoot "tmp-build\core-tests-qa-061-tmp"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Write-Output "NOTE: ASCII tmpdir not writable by child JVM; falling back to workspace tmpdir"
}
Write-Output "java.io.tmpdir=$tmp"

Write-Output "=== REPO: $src ==="
$gitExe = Get-Command git -ErrorAction SilentlyContinue
if ($gitExe) {
    Push-Location -LiteralPath $src
    $head = (& git rev-parse --short HEAD 2>$null).ToString().Trim()
    Pop-Location
} else {
    $head = "n/a (git not on PATH)"
}
Write-Output "HEAD=$head"

Write-Output "=== VERSIONS ==="
& $javac -version 2>&1
& $java -version 2>&1

$coreDir = Join-Path $src "app\src\main\java\org\openleaf\reader\core"
$coreSrc = @(Get-ChildItem -LiteralPath $coreDir -Filter *.java | ForEach-Object { $_.FullName })
$coreTestsDir = Join-Path $src "core-tests"
$tests = @(Get-ChildItem -LiteralPath $coreTestsDir -Filter *Test.java -File)

# 三个纯 JVM app 层类被 core 测试直接引用（0.61 中它们不在 core/ 包内）。
$helpers = @((Join-Path $src "app\src\main\java\org\openleaf\reader\LocalTextDocument.java"), (Join-Path $src "app\src\main\java\org\openleaf\reader\PluginTextReport.java"), (Join-Path $src "app\src\main\java\org\openleaf\reader\ReaderPaging.java"))
# 两个 Probe 是测试辅助类；StyleDraftProbe 需 android.jar 且无测试引用，不进入纯 JVM 编译集。
$probes = @(Get-ChildItem -LiteralPath $coreTestsDir -Filter *Probe.java -File | Where-Object { $_.Name -ne "StyleDraftProbe.java" } | ForEach-Object { $_.FullName })
Write-Output ("core sources: " + $coreSrc.Count + " ; helpers: " + $helpers.Count + " ; probes: " + $probes.Count + " ; tests: " + $tests.Count)

$exclude = @("CatalogProtocolTest.java")
$testFiles = @()
foreach ($t in $tests) { if ($exclude -notcontains $t.Name) { $testFiles += $t.FullName } }

$argList = @("-encoding", "UTF-8", "--release", "17", "-d", $out) + $coreSrc + $helpers + $probes + $testFiles
Write-Output "=== COMPILE (excluding CatalogProtocolTest, StyleDraftProbe) ==="
& $javac $argList 2>&1 | Tee-Object -FilePath (Join-Path $logs "qa-coretest-compile-061.log")
$compileExit = $LASTEXITCODE
Write-Output ("COMPILE_EXIT=" + $compileExit)
if ($compileExit -ne 0) {
    Write-Output "=== RESULT: COMPILE FAILED, no tests executed ==="
    exit 2
}

Write-Output "=== RUN EACH TEST ==="
$pass = 0
$fail = 0
$failedNames = @()
Push-Location -LiteralPath $src
foreach ($t in $testFiles) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($t)
    $mainClass = $name
    $packageLine = Select-String -LiteralPath $t -Pattern '^package\s+([\w.]+)\s*;' | Select-Object -First 1
    if ($packageLine) { $mainClass = $packageLine.Matches[0].Groups[1].Value + "." + $name }
    $logFile = Join-Path $logs ("qa-test-061-" + $name + ".log")
    & $java "-Djava.io.tmpdir=$tmp" -cp $out $mainClass *>&1 | Tee-Object -FilePath $logFile | Out-Null
    $code = $LASTEXITCODE
    if ($code -eq 0) {
        $pass++
        Write-Output ("PASS  " + $name)
    } else {
        $fail++
        $failedNames += $name
        Write-Output ("FAIL  " + $name + "  (exit=" + $code + ")")
    }
}
Pop-Location
Write-Output ("=== RESULT: PASS=" + $pass + " FAIL=" + $fail + " TOTAL=" + $testFiles.Count + " ===")
if ($failedNames.Count -gt 0) { Write-Output ("FAILED: " + ($failedNames -join ", ")) }
Write-Output "=== EXCLUDED: CatalogProtocolTest (needs android.os/org.json); StyleDraftProbe (needs android.jar, standalone) ==="
if ($fail -gt 0) { exit 1 }
exit 0
