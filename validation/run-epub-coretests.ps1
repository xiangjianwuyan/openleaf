# Isolated output paths for the EPUB hotfix; does not delete other sessions' build artifacts.
# DigestExportTest retains its existing prerequisite: org.json plus current core classes in core-tests/t02build.
$ErrorActionPreference = 'Continue'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspace = Split-Path $repo -Parent
. (Join-Path $workspace '.tools\env.ps1')
$out = Join-Path $repo 'build\epub-core-tests'
$tmp = Join-Path $repo 'build\epub-test-temp'
$logs = Join-Path $workspace 'deliverables\evidence-epub-0631\core'
New-Item -ItemType Directory -Force -Path $out,$tmp,$logs | Out-Null
$core = @(Get-ChildItem -LiteralPath (Join-Path $repo 'app\src\main\java\org\openleaf\reader\core') -Filter '*.java' -File | ForEach-Object FullName)
$helpers = @('LocalTextDocument.java','PluginTextReport.java','ReaderPaging.java') | ForEach-Object { Join-Path $repo ('app\src\main\java\org\openleaf\reader\' + $_) }
$tests = @(Get-ChildItem -LiteralPath (Join-Path $repo 'core-tests') -Filter '*Test.java' -File | Where-Object Name -ne 'CatalogProtocolTest.java')
$probes = @(Get-ChildItem -LiteralPath (Join-Path $repo 'core-tests') -Filter '*Probe.java' -File | Where-Object Name -ne 'StyleDraftProbe.java' | ForEach-Object FullName)
& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -encoding UTF-8 --release 17 -d $out @core @helpers @probes @($tests.FullName)
if ($LASTEXITCODE -ne 0) { throw 'Core compilation failed' }
$passed = 0
$failed = @()
Push-Location -LiteralPath $repo
try {
    foreach ($test in $tests) {
        $name = $test.BaseName
        $package = Select-String -LiteralPath $test.FullName -Pattern '^package\s+([\w.]+)\s*;' | Select-Object -First 1
        $main = if ($package) { $package.Matches[0].Groups[1].Value + '.' + $name } else { $name }
        & (Join-Path $env:JAVA_HOME 'bin\java.exe') '-Dfile.encoding=UTF-8' "-Djava.io.tmpdir=$tmp" -cp $out $main 2>&1 | Out-File -FilePath (Join-Path $logs ($name + '.log')) -Encoding utf8
        if ($LASTEXITCODE -eq 0) { $passed++; Write-Output ('PASS ' + $name) }
        else { $failed += $name; Write-Output ('FAIL ' + $name) }
    }
} finally { Pop-Location }
Write-Output ('RESULT PASS=' + $passed + ' FAIL=' + $failed.Count + ' TOTAL=' + $tests.Count)
Write-Output 'Excluded: CatalogProtocolTest (Android dependencies); StyleDraftProbe (Android probe)'
if ($failed.Count) { throw ('Failed: ' + ($failed -join ', ')) }
