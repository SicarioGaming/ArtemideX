<#
.SYNOPSIS
Builds and runs the tests of the microphone receiver's encryption (SecureMic.cs).

.DESCRIPTION
Uses the C# compiler that comes with Windows, like the setup, and runs the tests twice: as a
64-bit and as a 32-bit program, since the calls into Windows' AES-GCM have to work both ways.
Then checks the receiver itself builds the way setup-microphone.ps1 builds it. Nothing is
installed and nothing plays; the tests use no network either.

    powershell -NoProfile -ExecutionPolicy Bypass -File windows\tests\run-tests.ps1

Exits with 1 if anything fails.

.PARAMETER OutDir
Where the test programs go. Default: a folder in TEMP.
#>
param(
    [string]$OutDir = (Join-Path $env:TEMP 'starwire-mic-tests')
)

$ErrorActionPreference = 'Stop'
$tests = Split-Path -Parent $PSCommandPath
$windows = Split-Path -Parent $tests
$vectors = Join-Path $tests 'vectors\swmc-v1-vectors.txt'
New-Item -ItemType Directory -Force $OutDir | Out-Null

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) {
    Write-Host 'The C# compiler from .NET Framework 4 is missing.' -ForegroundColor Red
    exit 1
}

$failed = @()
foreach ($platform in 'x64', 'x86') {
    $exe = Join-Path $OutDir "SecureMicTests-$platform.exe"
    Write-Host "== Building the tests ($platform)" -ForegroundColor Cyan
    & $csc /nologo /optimize "/platform:$platform" /r:System.Security.dll "/out:$exe" `
        (Join-Path $tests 'SecureMicTests.cs') (Join-Path $windows 'SecureMic.cs')
    if ($LASTEXITCODE -ne 0) {
        $failed += "building the tests ($platform)"
        continue
    }
    Write-Host "== Running the tests ($platform)" -ForegroundColor Cyan
    # Only the failures and the total; every check's line is in the output file
    $log = Join-Path $OutDir "SecureMicTests-$platform.txt"
    & $exe $vectors > $log
    $code = $LASTEXITCODE
    Get-Content $log | Where-Object { $_ -notlike 'PASS *' }
    if ($code -ne 0) {
        $failed += "the tests ($platform)"
    }
}

Write-Host '== Building the receiver as the setup does' -ForegroundColor Cyan
& $csc /nologo /optimize /target:winexe "/win32icon:$(Join-Path $windows 'starwire-mic.ico')" `
    /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Security.dll "/out:$(Join-Path $OutDir 'StarwireMicReceiver.exe')" `
    (Join-Path $windows 'StarwireMicReceiver.cs') (Join-Path $windows 'SecureMic.cs')
if ($LASTEXITCODE -ne 0) {
    $failed += 'building the receiver'
}
else {
    Write-Host 'Built.'
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "Failed: $($failed -join ', ')" -ForegroundColor Red
    exit 1
}
Write-Host 'All passed.' -ForegroundColor Green
exit 0
