<#
.SYNOPSIS
End-to-end test of the microphone receiver on this PC's loopback: pairing, encrypted audio, and
the packets it must refuse.

.DESCRIPTION
Builds the receiver (StarwireMicReceiver.cs and SecureMic.cs) into a test folder and starts it
with --console --loopback on a test port and with its own paired devices file, so it runs next to
an installed receiver without touching it. The receiver prints a pairing code. SwmcTestSender.java,
built with the JDK's javac, pairs using that code, streams a quiet 1 kHz tone in real time, and
then sends a replayed, a late, two forged and one plain VBAN packet. Last, a second device uses
the same code, which must undo the pairing. When the receiver stops by itself, its summary must
match what the sender sent: every audio packet played, in order, the same PCM, and each bad packet
refused.

By default the receiver plays into an output that doesn't exist, so nothing is heard and its
HELLO_ACK says so (status 1). With -Audible it plays into VB-Cable's CABLE Input, quietly (-44 dBFS)
for about a second, and the test also checks the tone comes out of CABLE Output.

    powershell -NoProfile -ExecutionPolicy Bypass -File windows\tests\e2e-loopback.ps1 [-Audible]

Exits with 1 if anything fails.

.PARAMETER Port
The test receiver's UDP port. Default 6994, so it never meets the usual 6980.

.PARAMETER Jdk
The JDK's folder, if javac isn't on the PATH or in JAVA_HOME.

.PARAMETER OutDir
Where the builds and logs go. Default: a folder in TEMP.

.PARAMETER Packets
How many audio packets to send (187.5 make a second). Default 188.

.PARAMETER Audible
Play the tone into CABLE Input and check it comes out of CABLE Output.
#>
param(
    [ValidateRange(1024, 65535)][int]$Port = 6994,
    [string]$Jdk = '',
    [string]$OutDir = (Join-Path $env:TEMP 'starwire-mic-e2e'),
    [ValidateRange(10, 5000)][int]$Packets = 188,
    [switch]$Audible
)

$ErrorActionPreference = 'Stop'
$tests = Split-Path -Parent $PSCommandPath
$windows = Split-Path -Parent $tests
New-Item -ItemType Directory -Force $OutDir | Out-Null
$failed = @()
function Fail($what) { $script:failed += $what; Write-Host "FAIL $what" -ForegroundColor Red }

# --- Tools ---
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
$javac = $null
$jdks = @($Jdk, $env:JAVA_HOME) + @(Get-ChildItem (Join-Path $env:ProgramFiles 'Eclipse Adoptium\jdk-*') -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | ForEach-Object { $_.FullName })
foreach ($folder in $jdks) {
    if ($folder -and (Test-Path (Join-Path $folder 'bin\javac.exe'))) { $javac = Join-Path $folder 'bin\javac.exe'; break }
}
if (-not $javac) {
    $found = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($found) { $javac = $found.Source }
}
if (-not (Test-Path $csc) -or -not $javac) {
    Write-Host 'Needs the C# compiler from .NET Framework 4 and a JDK (use -Jdk).' -ForegroundColor Red
    exit 1
}
$java = Join-Path (Split-Path -Parent $javac) 'java.exe'

# --- Build ---
Write-Host '== Building the receiver, the tone meter and the test sender' -ForegroundColor Cyan
$exe = Join-Path $OutDir 'StarwireMicReceiver.exe'
& $csc /nologo /optimize /target:winexe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Security.dll "/out:$exe" `
    (Join-Path $windows 'StarwireMicReceiver.cs') (Join-Path $windows 'SecureMic.cs')
if ($LASTEXITCODE -ne 0) { Fail 'building the receiver'; exit 1 }
$meter = Join-Path $OutDir 'SecureMicTests.exe'
& $csc /nologo /optimize /r:System.Security.dll "/out:$meter" (Join-Path $tests 'SecureMicTests.cs') (Join-Path $windows 'SecureMic.cs')
if ($LASTEXITCODE -ne 0) { Fail 'building the tone meter'; exit 1 }
$classes = Join-Path $OutDir 'java'
New-Item -ItemType Directory -Force $classes | Out-Null
& $javac --release 8 -nowarn -d $classes (Join-Path $tests 'SwmcTestSender.java')
if ($LASTEXITCODE -ne 0) { Fail 'building the test sender'; exit 1 }

# --- The receiver, on its own port with its own paired devices file ---
$stamp = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$pairings = Join-Path $OutDir "pairings-$stamp.dat"
$log = Join-Path $OutDir "receiver-$stamp.txt"
$empty = Join-Path $OutDir 'empty.txt'
Set-Content -Path $empty -Value '' -Encoding Ascii
$device = if ($Audible) { 'CABLE Input' } else { 'Starwire e2e test (no such output)' }
$status = if ($Audible) { 0 } else { 1 }
$amplitude = 200
Write-Host "== Starting the test receiver on UDP $Port (loopback only)" -ForegroundColor Cyan
$receiver = Start-Process -FilePath $exe -PassThru -WindowStyle Hidden `
    -ArgumentList @('--console', '--loopback', '--port', $Port, '--pairings-file', "`"$pairings`"", '--pair', '--exit-after-idle', '5', '--device', "`"$device`"") `
    -RedirectStandardOutput $log -RedirectStandardError (Join-Path $OutDir "receiver-$stamp-errors.txt") -RedirectStandardInput $empty
$code = $null
for ($i = 0; $i -lt 150 -and -not $code; $i++) {
    Start-Sleep -Milliseconds 100
    if (Test-Path $log) {
        $line = Get-Content $log -ErrorAction SilentlyContinue | Where-Object { $_ -match 'Pairing code: ([0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4})' } | Select-Object -First 1
        if ($line) { $code = $Matches[1] }
    }
    if ($receiver.HasExited) { break }
}
if (-not $code) {
    Fail 'the receiver printed no pairing code'
    Get-Content $log -ErrorAction SilentlyContinue | ForEach-Object { "  receiver: $_" }
    if (-not $receiver.HasExited) { Stop-Process -Id $receiver.Id -Force }
    exit 1
}
Write-Host "Pairing code: $code"

# --- With -Audible, listen on CABLE Output while the sender streams ---
$tone = $null
if ($Audible) {
    $seconds = [Math]::Ceiling($Packets / 187.5) + 7
    $level = $amplitude / 32768 / 4
    $toneLog = Join-Path $OutDir "tone-$stamp.txt"
    $tone = Start-Process -FilePath $meter -PassThru -WindowStyle Hidden -RedirectStandardOutput $toneLog `
        -ArgumentList @('tone', $seconds, '"CABLE Output"', '1000', $level.ToString([Globalization.CultureInfo]::InvariantCulture))
}

# --- The sender ---
Write-Host '== Pairing and streaming from the test sender' -ForegroundColor Cyan
$sent = & $java -cp $classes SwmcTestSender $Port $code $Packets $amplitude $status
$senderCode = $LASTEXITCODE
$sent | ForEach-Object { "  sender: $_" }
if ($senderCode -ne 0) { Fail 'the test sender' }

# --- The receiver's summary ---
if (-not $receiver.WaitForExit(20000)) {
    Fail 'the receiver did not stop by itself'
    Stop-Process -Id $receiver.Id -Force
}
$lines = Get-Content $log
$lines | ForEach-Object { "  receiver: $_" }
$summary = $lines | Where-Object { $_ -like 'Summary: *' } | Select-Object -Last 1
$expect = $sent | Where-Object { $_ -like 'SENT *' } | Select-Object -Last 1
if (-not $summary -or -not $expect) {
    Fail 'no summary from the receiver or the sender'
}
else {
    $v = @{}
    foreach ($pair in ($expect -replace '^SENT ', '') -split ' ') { $k, $val = $pair -split '='; $v[$k] = $val }
    $want = "Summary: played $($v['played']), replayed or late $($v['replayed_or_late']), forged $($v['forged']), unencrypted $($v['unencrypted']), " +
        "unpaired $($v['unpaired']), unknown session $($v['unknown_session']), busy 0, not home network 0, malformed 0, " +
        "wrong codes $($v['wrong_codes']), errors 0, PCM fnv1a64 $($v['pcm'])"
    if ($summary -eq $want) {
        Write-Host 'PASS every audio packet played in order with the same PCM; the replayed, late, forged and plain VBAN packets were refused' -ForegroundColor Green
    }
    else {
        Fail "the receiver's summary doesn't match what was sent"
        Write-Host "  wanted: $want"
        Write-Host "  got:    $summary"
    }
}
if ($lines -match 'Paired with e2e test sender \(from 127\.0\.0\.1\)\.') { Write-Host 'PASS the receiver says which device paired, and from where' -ForegroundColor Green }
else { Fail "the receiver didn't say the device paired" }
if ($lines -match 'The pairing code was used twice, so someone else may have seen it\. To be safe, e2e test sender isn''t paired any more\.') {
    Write-Host 'PASS the second use of the code undid the pairing, and the receiver says so' -ForegroundColor Green
}
else { Fail "the receiver didn't say the code was used twice" }
if ($lines -match "Ignored unencrypted microphone audio from 127\.0\.0\.1\. If that's Starwire, update it and pair it") {
    Write-Host 'PASS the plain VBAN packet got the message to update Starwire and pair' -ForegroundColor Green
}
else { Fail 'no message about the plain VBAN packet' }
if ($lines -match 'Receiving the microphone from e2e test sender \(encrypted\)') {
    Write-Host 'PASS the receiver names the paired device that is talking' -ForegroundColor Green
}
else { Fail "the receiver didn't name the device that was talking" }

if ($tone) {
    if (-not $tone.WaitForExit(30000)) { Stop-Process -Id $tone.Id -Force }
    $result = Get-Content $toneLog | Where-Object { $_ -like 'TONE *' } | Select-Object -Last 1
    Write-Host "  CABLE Output: $result"
    $heard = 0
    if ($result -match 'heard=(\d+)') { $heard = [int]$Matches[1] }
    # The tone lasts Packets / 187.5 seconds: about 10 stretches of 100 ms for 188 packets
    if ($heard -ge [Math]::Max(3, [Math]::Floor($Packets / 187.5 * 10 / 2))) {
        Write-Host 'PASS the tone came out of CABLE Output' -ForegroundColor Green
    }
    else { Fail "the tone didn't come out of CABLE Output" }
}

Remove-Item $pairings -ErrorAction SilentlyContinue
Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "Failed: $($failed -join '; ')" -ForegroundColor Red
    exit 1
}
Write-Host 'All passed.' -ForegroundColor Green
exit 0
