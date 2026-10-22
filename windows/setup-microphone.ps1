<#
.SYNOPSIS
Sets up this PC to receive Starwire's microphone, whatever streaming host it runs.

.DESCRIPTION
- Installs VB-Cable, the virtual microphone, from VB-Audio's site if it isn't there yet. Its setup
  window opens: click "Install Driver". Restart Windows afterwards when it's new.
- Builds Starwire's microphone receiver (StarwireMicReceiver.cs, next to this script) with the
  C# compiler that comes with Windows, and installs it in Program Files\Starwire.
- Lets the receiver through Windows Firewall for your home network and private VPNs only.
- Starts the receiver now and whenever you sign in (minimized), and checks the virtual
  microphone works.

Then choose "CABLE Output (VB-Audio Virtual Cable)" as the microphone in Discord or the game, and
in Starwire: Settings > Sound & vibration > Send my microphone > A VBAN receiver on the PC.

Run it from the folder you unzipped: right-click setup-microphone.ps1 > Run with PowerShell. It
asks for administrator rights once, for the driver and the firewall rule.

.PARAMETER StreamName
Must match Starwire's "VBAN stream name". Default Stream1.

.PARAMETER Port
Must match Starwire's "VBAN port". Default 6980.

.PARAMETER NoStartup
Don't start the receiver when you sign in.

.PARAMETER Uninstall
Removes the receiver, its firewall rule and its startup entry. VB-Cable stays; remove it with
its own setup ("Remove Driver").
#>
param(
    [string]$StreamName = 'Stream1',
    [ValidateRange(1, 65535)][int]$Port = 6980,
    [switch]$NoStartup,
    [switch]$Uninstall,
    # Filled in when the script restarts itself as administrator, so the startup entry goes to
    # the account that ran it rather than the administrator's
    [string]$StartupFolder = [Environment]::GetFolderPath('Startup')
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
$installDir = Join-Path $env:ProgramFiles 'Starwire'
$exe = Join-Path $installDir 'StarwireMicReceiver.exe'
$shortcut = Join-Path $installDir 'Starwire microphone receiver.lnk'
$startupShortcut = Join-Path $StartupFolder 'Starwire microphone receiver.lnk'
$ruleName = 'Starwire microphone receiver'
$vbCableUrl = 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip'

function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Done($text) { Write-Host "   $text" -ForegroundColor Green }
function Note($text) { Write-Host "   $text" -ForegroundColor Yellow }
function Finish($code) {
    Write-Host ''
    Read-Host 'Press Enter to close'
    exit $code
}

# --- Administrator rights (the driver and the firewall rule need them) ---
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
        '-StreamName', "`"$StreamName`"", '-Port', $Port, '-StartupFolder', "`"$StartupFolder`"")
    if ($NoStartup) { $arguments += '-NoStartup' }
    if ($Uninstall) { $arguments += '-Uninstall' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments
    exit
}

Write-Host 'Starwire microphone setup' -ForegroundColor White

Get-Process StarwireMicReceiver -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 300

if ($Uninstall) {
    Step 'Removing the Starwire microphone receiver'
    Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    Remove-Item $startupShortcut -ErrorAction SilentlyContinue
    Remove-Item $installDir -Recurse -Force -ErrorAction SilentlyContinue
    Done 'Removed. VB-Cable is still installed; remove it with its own setup if you want.'
    Finish 0
}

# --- Build and install the receiver ---
Step 'Installing the Starwire microphone receiver'
$source = Join-Path $here 'StarwireMicReceiver.cs'
if (-not (Test-Path $source)) {
    Note "StarwireMicReceiver.cs isn't next to this script. Unzip the whole download and run it from there."
    Finish 1
}
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) {
    Note 'The C# compiler from .NET Framework 4 is missing. Turn on ".NET Framework 4.8" in Windows Features and run this again.'
    Finish 1
}
New-Item -ItemType Directory -Force $installDir | Out-Null
& $csc /nologo /optimize /target:exe "/out:$exe" $source
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
    Note 'Building the receiver failed (see above).'
    Finish 1
}
Done "Installed $exe"

# --- VB-Cable, the virtual microphone ---
Step 'Checking for VB-Cable'
$restartNeeded = $false
$outputs = & $exe --list
if ($outputs -match 'CABLE Input') {
    Done 'VB-Cable is installed.'
}
else {
    Note 'Not installed. Downloading it from VB-Audio...'
    $work = Join-Path $env:TEMP ('Starwire-VBCable-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory $work | Out-Null
    $zip = Join-Path $work 'VBCABLE_Driver_Pack45.zip'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $vbCableUrl -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $work -Force
    $setup = Join-Path $work $(if ([Environment]::Is64BitOperatingSystem) { 'VBCABLE_Setup_x64.exe' } else { 'VBCABLE_Setup.exe' })

    # Only run it if it's VB-Audio's own signed setup
    $signature = Get-AuthenticodeSignature $setup
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'BUREL VINCENT') {
        Note "The VB-Cable download isn't signed by VB-Audio, so it wasn't run. Install VB-Cable from https://vb-audio.com/Cable/ yourself, restart, and run this again."
        Finish 1
    }
    Done 'Signed by VB-Audio (Vincent Burel).'
    Note 'The VB-Cable window is opening: click "Install Driver", wait for it to finish, then close it.'
    Start-Process -FilePath $setup -WorkingDirectory $work -Wait
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

    $outputs = & $exe --list
    if ($outputs -match 'CABLE Input') {
        Done 'VB-Cable is installed.'
    }
    else {
        Note "VB-Cable isn't showing up yet. Windows needs a restart to finish installing it."
    }
    $restartNeeded = $true
}

# --- Firewall: home network and private VPNs only ---
Step 'Letting the receiver through Windows Firewall (home network only)'
Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName $ruleName `
    -Description 'Starwire sends your microphone to this PC as VBAN. Only devices on your home network or a private VPN can reach it.' `
    -Direction Inbound -Action Allow -Program $exe -Protocol UDP -LocalPort $Port `
    -RemoteAddress @('LocalSubnet', '100.64.0.0/10', 'fc00::/7') -Profile Any | Out-Null
Done "UDP port $Port, from this network and private VPNs (like Tailscale)."

# --- Start it now and when you sign in ---
Step 'Starting the receiver'
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.Arguments = "--name `"$StreamName`" --port $Port"
$link.WorkingDirectory = $installDir
$link.WindowStyle = 7   # minimized
$link.Description = "Receives Starwire's microphone"
$link.Save()
if ($NoStartup) {
    Remove-Item $startupShortcut -ErrorAction SilentlyContinue
}
else {
    Copy-Item $shortcut $startupShortcut -Force
    Done 'It starts by itself, minimized, when you sign in.'
}

if ($outputs -match 'CABLE Input') {
    # Started through Explorer so it runs as you, not as administrator
    Start-Process explorer.exe -ArgumentList "`"$shortcut`""
    Done 'Running (minimized in the taskbar).'

    Step 'Checking the virtual microphone'
    & $exe --check
}

# --- VB-Cable can take over the default speakers when it installs ---
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
[StructLayout(LayoutKind.Sequential)] public struct PropKey { public Guid fmtid; public int pid; }
[StructLayout(LayoutKind.Explicit)] public struct PropVariant { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public IntPtr p; [FieldOffset(16)] public IntPtr p2; }
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IStarwireDeviceEnumerator { int EnumAudioEndpoints(int flow, int mask, out IntPtr devices); int GetDefaultAudioEndpoint(int flow, int role, out IStarwireDevice device); }
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IStarwireDevice { int Activate(ref Guid iid, int ctx, IntPtr p, out IntPtr o); int OpenPropertyStore(int access, out IStarwirePropertyStore store); }
[Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
public interface IStarwirePropertyStore { int GetCount(out int c); int GetAt(int i, out PropKey k); int GetValue(ref PropKey k, out PropVariant v); }
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] public class StarwireDeviceEnumerator { }
public static class StarwireDefaults {
    public static string Name(int flow) {
        try {
            var e = (IStarwireDeviceEnumerator) new StarwireDeviceEnumerator();
            IStarwireDevice d; if (e.GetDefaultAudioEndpoint(flow, 0, out d) != 0) return "";
            IStarwirePropertyStore s; d.OpenPropertyStore(0, out s);
            var key = new PropKey { fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"), pid = 14 };
            PropVariant v; s.GetValue(ref key, out v);
            return Marshal.PtrToStringUni(v.p);
        } catch { return ""; }
    }
}
"@
$speakers = [StarwireDefaults]::Name(0)
$microphone = [StarwireDefaults]::Name(1)
if ($speakers -match 'CABLE' -or $microphone -match 'CABLE') {
    Step 'One thing to fix in Sound settings'
    if ($speakers -match 'CABLE') { Note "Windows made '$speakers' your default speakers, so the PC's sound goes nowhere. Pick your real speakers or headphones." }
    if ($microphone -match 'CABLE') { Note "Windows made '$microphone' your default microphone. That's fine for Starwire; pick your own microphone if you also use one at the PC." }
    Start-Process 'ms-settings:sound'
}

# --- What to do next ---
Step 'Next'
if ($restartNeeded) {
    Note 'Restart Windows to finish installing VB-Cable. The receiver starts by itself afterwards.'
}
Write-Host '   1. In Discord or the game, choose "CABLE Output (VB-Audio Virtual Cable)" as the microphone.'
Write-Host '   2. In Starwire: Settings > Sound & vibration > Send my microphone, then'
Write-Host '      "Send my microphone to" > "A VBAN receiver on the PC".'
Write-Host "   3. Starwire's VBAN stream name must be `"$StreamName`" and its port $Port (the defaults)."
$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { $_.IPAddress }
if ($addresses) { Write-Host "   This PC's addresses: $($addresses -join ', ')" }
Finish 0
