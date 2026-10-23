<#
.SYNOPSIS
Sets up this PC to receive Starwire's microphone, whatever streaming host it runs.

.DESCRIPTION
- Installs VB-Cable, the virtual microphone, from VB-Audio's site if it isn't there yet. Its setup
  window opens: click "Install Driver". Restart Windows afterwards if it doesn't show up.
- Builds Starwire's microphone receiver (StarwireMicReceiver.cs and SecureMic.cs, next to this
  script) with the C# compiler that comes with Windows, and installs it in Program Files\Starwire.
- Lets the receiver through Windows Firewall for local-network and private VPN addresses only.
- Starts the receiver now and whenever you sign in (no window, just an icon by the clock), and
  checks the virtual microphone works.

Then choose "CABLE Output (VB-Audio Virtual Cable)" as the microphone in Discord or the game, and
pair Starwire with the PC: right-click the receiver's icon > Pair a device, and type the code it
shows in Starwire (Settings > Sound & vibration > Send my microphone > Pair with the PC). Only
paired devices can send audio to the receiver, and it's encrypted on the way.

Run it from the folder you unzipped: double-click setup-microphone.cmd. It asks for administrator
rights once, for the driver and the firewall rule.

.PARAMETER Port
Must match Starwire's microphone port. Default 6980.

.PARAMETER AllowUnencrypted
Also plays plain, unencrypted VBAN (from Starwire 0.2.0, Voicemeeter or another VBAN app) with
the stream name -StreamName. Not safe: anyone on the same network can then send sound into this
PC's microphone or listen to it. Leave it off unless you need it.

.PARAMETER StreamName
With -AllowUnencrypted, the VBAN stream name to accept. Default Stream1.

.PARAMETER NoStartup
Don't start the receiver when you sign in.

.PARAMETER Uninstall
Removes the receiver, its firewall rule, its startup entry and the list of paired devices.
VB-Cable stays; remove it with its own setup ("Remove Driver").
#>
param(
    [string]$StreamName = 'Stream1',
    [ValidateRange(1, 65535)][int]$Port = 6980,
    [switch]$AllowUnencrypted,
    [switch]$NoStartup,
    [switch]$Uninstall,
    # Filled in when the script restarts itself as administrator, so the startup entry and the
    # paired devices are the account's that ran it rather than the administrator's
    [string]$StartupFolder = [Environment]::GetFolderPath('Startup'),
    [string]$PairingsFile = (Join-Path $env:LOCALAPPDATA 'Starwire\mic-pairings.dat')
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $PSCommandPath
$installDir = Join-Path $env:ProgramFiles 'Starwire'
$exe = Join-Path $installDir 'StarwireMicReceiver.exe'
$shortcut = Join-Path $installDir 'Starwire microphone receiver.lnk'
$startupShortcut = Join-Path $StartupFolder 'Starwire microphone receiver.lnk'
$ruleName = 'Starwire microphone receiver'
$vbCableUrl = 'https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip'

# Messages wrap at the window's width instead of running off the edge; later lines get $rest
function Say($text, $color, $first = '   ', $rest = '   ') {
    $width = 80
    try { $width = [Math]::Max(20, $Host.UI.RawUI.WindowSize.Width - 1) } catch { }
    $lines = @()
    $line = $first
    foreach ($word in ($text -split ' ')) {
        if ($line.Trim() -and $line.Length + $word.Length -gt $width) {
            $lines += $line.TrimEnd()
            $line = $rest
        }
        $line += $word + ' '
    }
    $lines += $line.TrimEnd()
    foreach ($l in $lines) {
        if ($color) { Write-Host $l -ForegroundColor $color } else { Write-Host $l }
    }
}
function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Done($text) { Say $text Green }
function Note($text) { Say $text Yellow }
function Finish($code) {
    Write-Host ''
    Read-Host 'Press Enter to close'
    exit $code
}

# Any error stops here and stays on screen, instead of the window closing before it can be read
trap {
    Write-Host ''
    Write-Host "Setup stopped: $($_.Exception.Message)" -ForegroundColor Red
    Note 'Fix that and run the setup again. Nothing after this step was changed.'
    Finish 1
}

# --- Administrator rights (the driver and the firewall rule need them) ---
$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$PSCommandPath`"",
        '-StreamName', "`"$StreamName`"", '-Port', $Port, '-StartupFolder', "`"$StartupFolder`"",
        '-PairingsFile', "`"$PairingsFile`"")
    if ($AllowUnencrypted) { $arguments += '-AllowUnencrypted' }
    if ($NoStartup) { $arguments += '-NoStartup' }
    if ($Uninstall) { $arguments += '-Uninstall' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $arguments
    exit
}

# Fit the window on the screen. Run as administrator, PowerShell opens in the classic console at
# 120x50 wherever Windows places it, which can run past the bottom of a 720p or streamed display,
# and the newest lines are at the bottom. So shrink the window to the work area (the screen minus
# the taskbar) if it's bigger and slide it up and left onto it; the console fits its rows and
# columns to the new size. Windows Terminal sizes its own window, so this leaves it alone.
try {
    Add-Type -Namespace Starwire -Name ConsoleWindow -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow();
[DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr window, System.Text.StringBuilder name, int size);
[DllImport("user32.dll")] public static extern bool IsZoomed(IntPtr window);
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr window);
[DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr window, [In, Out] int[] rect);
[DllImport("user32.dll")] public static extern IntPtr MonitorFromWindow(IntPtr window, int flags);
[DllImport("user32.dll")] public static extern bool GetMonitorInfo(IntPtr monitor, [In, Out] int[] info);
[DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr window, IntPtr after, int x, int y, int width, int height, int flags);
'@
    $window = [Starwire.ConsoleWindow]::GetConsoleWindow()
    $class = New-Object Text.StringBuilder 64
    [void][Starwire.ConsoleWindow]::GetClassName($window, $class, $class.Capacity)
    $rect = New-Object int[] 4
    $info = New-Object int[] 10
    $info[0] = 40 # MONITORINFO's size; the work area comes back in info[5..8]: left, top, right, bottom
    if ($class.ToString() -eq 'ConsoleWindowClass' -and
        -not [Starwire.ConsoleWindow]::IsZoomed($window) -and -not [Starwire.ConsoleWindow]::IsIconic($window) -and
        [Starwire.ConsoleWindow]::GetWindowRect($window, $rect) -and
        [Starwire.ConsoleWindow]::GetMonitorInfo([Starwire.ConsoleWindow]::MonitorFromWindow($window, 2), $info)) {
        $width = [Math]::Min($rect[2] - $rect[0], $info[7] - $info[5])
        $height = [Math]::Min($rect[3] - $rect[1], $info[8] - $info[6])
        $left = [Math]::Max($info[5], [Math]::Min($rect[0], $info[7] - $width))
        $top = [Math]::Max($info[6], [Math]::Min($rect[1], $info[8] - $height))
        if ($left -ne $rect[0] -or $top -ne $rect[1] -or $width -ne $rect[2] - $rect[0] -or $height -ne $rect[3] - $rect[1]) {
            # SWP_NOZORDER | SWP_NOACTIVATE
            [void][Starwire.ConsoleWindow]::SetWindowPos($window, [IntPtr]::Zero, $left, $top, $width, $height, 0x14)
        }
    }
}
catch { }
# A buffer wider than the window scrolls sideways on long lines, cutting others off
try {
    $raw = $Host.UI.RawUI
    if ($raw.BufferSize.Width -gt $raw.WindowSize.Width) {
        $raw.BufferSize = New-Object Management.Automation.Host.Size($raw.WindowSize.Width, $raw.BufferSize.Height)
    }
}
catch { }

Write-Host 'Starwire microphone setup' -ForegroundColor White

Get-Process StarwireMicReceiver -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 300

if ($Uninstall) {
    Step 'Removing the Starwire microphone receiver'
    Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
    Remove-Item $startupShortcut -ErrorAction SilentlyContinue
    Remove-Item $installDir -Recurse -Force -ErrorAction SilentlyContinue
    # The paired devices, and their folder if nothing else is in it
    Remove-Item $PairingsFile -ErrorAction SilentlyContinue
    $pairingsFolder = Split-Path -Parent $PairingsFile
    if ((Test-Path $pairingsFolder) -and -not (Get-ChildItem $pairingsFolder -Force)) {
        Remove-Item $pairingsFolder -ErrorAction SilentlyContinue
    }
    Done 'Removed, with its list of paired devices. VB-Cable is still installed; remove it with its own setup if you want.'
    Finish 0
}

# --- Smart App Control blocks programs built on this PC, like the receiver ---
$smartAppControl = (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy' -Name VerifiedAndReputablePolicyState -ErrorAction SilentlyContinue).VerifiedAndReputablePolicyState
if ($smartAppControl -eq 1) {
    Step 'Smart App Control is on'
    Note "Windows' Smart App Control only runs signed programs, and Starwire's receiver is built on this PC,"
    Note "so Windows would block it. Use VB-Audio's signed VBAN-Receptor instead: https://vb-audio.com/Voicemeeter/vban.htm"
    Note '(set its stream to Stream1, its output to CABLE Input), with VB-Cable from https://vb-audio.com/Cable/.'
    Note "VBAN-Receptor isn't encrypted: in Starwire, send the microphone to another VBAN receiver (not encrypted),"
    Note 'and only on a network you trust, since anyone on it could listen or send sound into the microphone.'
    Finish 1
}

# --- Build and install the receiver ---
Step 'Installing the Starwire microphone receiver'
$source = Join-Path $here 'StarwireMicReceiver.cs'
$secureSource = Join-Path $here 'SecureMic.cs'
if (-not (Test-Path $source) -or -not (Test-Path $secureSource)) {
    Note "StarwireMicReceiver.cs and SecureMic.cs must be next to this script. Unzip the whole download and run it from there."
    Finish 1
}
$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) {
    Note 'The C# compiler from .NET Framework 4 is missing. Turn on ".NET Framework 4.8" in Windows Features and run this again.'
    Finish 1
}
New-Item -ItemType Directory -Force $installDir | Out-Null
# A Windows app rather than a console one, so it runs as a notification-area icon with no window
$icon = Join-Path $here 'starwire-mic.ico'
$iconArgs = @()
if (Test-Path $icon) {
    Copy-Item $icon $installDir -Force
    $iconArgs = @("/win32icon:$icon")
}
& $csc /nologo /optimize /target:winexe $iconArgs /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Security.dll "/out:$exe" $source $secureSource
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $exe)) {
    Note 'Building the receiver failed (see above).'
    Finish 1
}
Done "Installed $exe"

# --- VB-Cable, the virtual microphone ---
Step 'Checking for VB-Cable'
$restartNeeded = $false
try {
    $outputs = & $exe --list | ForEach-Object { $_ }
}
catch {
    # Antivirus or an app-control policy refused the freshly built program
    Note "Windows wouldn't run the receiver: $($_.Exception.Message)"
    Note "Allow StarwireMicReceiver.exe in your antivirus, or use VB-Audio's signed VBAN-Receptor instead."
    Finish 1
}
if ($outputs -match 'CABLE Input') {
    Done 'VB-Cable is installed.'
}
else {
    Note 'Not installed. Downloading it from VB-Audio...'
    $work = Join-Path $env:TEMP ('Starwire-VBCable-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory $work | Out-Null
    $zip = Join-Path $work 'VBCABLE_Driver_Pack45.zip'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    # PowerShell 5.1's progress bar garbles the window and slows the download a lot
    $ProgressPreference = 'SilentlyContinue'
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

    $outputs = & $exe --list | ForEach-Object { $_ }
    if ($outputs -match 'CABLE Input') {
        Done 'VB-Cable is installed.'
    }
    else {
        Note "VB-Cable isn't showing up yet. Windows needs a restart to finish installing it."
        $restartNeeded = $true
    }
}

# --- Firewall: local-network and private VPN addresses only, the same ones the receiver accepts.
# Only paired devices can send it audio anyway; the address limit is an extra layer. ---
Step 'Letting the receiver through Windows Firewall (local network and private VPNs only)'
Remove-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName $ruleName `
    -Description 'Starwire sends your microphone to this PC, encrypted. Only paired devices can send audio; the limit to local-network and private VPN addresses is an extra layer.' `
    -Direction Inbound -Action Allow -Program $exe -Protocol UDP -LocalPort $Port `
    -RemoteAddress @('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '169.254.0.0/16', '100.64.0.0/10', 'fc00::/7', 'fe80::/10', 'fec0::/10') `
    -Profile Any | Out-Null
Done "UDP port $Port, from local-network and private VPN addresses (like Tailscale)."

# --- Start it now and when you sign in ---
Step 'Starting the receiver'
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut($shortcut)
$link.TargetPath = $exe
$link.Arguments = "--name `"$StreamName`" --port $Port"
if ($AllowUnencrypted) { $link.Arguments += ' --allow-unencrypted' }
$link.WorkingDirectory = $installDir
$link.Description = "Receives Starwire's microphone"
$link.Save()
if ($NoStartup) {
    Remove-Item $startupShortcut -ErrorAction SilentlyContinue
}
else {
    Copy-Item $shortcut $startupShortcut -Force
    Done 'It starts by itself when you sign in.'
}

if ($outputs -match 'CABLE Input') {
    # Started through Explorer so it runs as you, not as administrator
    Start-Process explorer.exe -ArgumentList "`"$shortcut`""
    Done "Running, with no window: its icon is in the notification area, by the clock. Right-click it to pair a device, for a test tone or to quit."

    Step 'Checking the virtual microphone'
    # The receiver has no window of its own, so its output has to be piped to be waited for.
    # Its lines go through Say so they wrap on whole words too.
    & $exe --check | ForEach-Object { Say $_ $null '' '   ' }
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
if ($speakers -match 'CABLE') {
    Step 'One thing to fix in Sound settings'
    Note "Windows made '$speakers' your default speakers, so the PC's sound (and the stream's) would go into the virtual microphone. Pick the output you used before: your speakers, the monitor's HDMI audio, or Steam Streaming Speakers."
    Start-Process 'ms-settings:sound'
}
if ($microphone -match 'CABLE') {
    Note "Windows also made '$microphone' your default microphone. That's fine for Starwire; pick your own microphone in Sound settings if you also use one at the PC."
}

# --- What to do next ---
Step 'Next'
if ($restartNeeded) {
    Note 'Restart Windows to finish installing VB-Cable. The receiver starts by itself afterwards.'
}
Say '1. In Discord or the game, choose "CABLE Output (VB-Audio Virtual Cable)" as the microphone.' $null '   ' '      '
Say "2. Update Starwire on your Android device if it's 0.2.0: this receiver only takes encrypted audio from paired devices, and 0.2.0 doesn't encrypt it." $null '   ' '      '
Say "3. Pair them, once: right-click the Starwire microphone icon by the clock > Pair a device. In Starwire: Settings > Sound & vibration > Send my microphone > Pair with the PC, and type the code the PC shows." $null '   ' '      '
if ($Port -ne 6980) { Say "4. Set Starwire's microphone port to $Port, like this PC's." $null '   ' '      ' }
if ($AllowUnencrypted) {
    Note "-AllowUnencrypted is on: the receiver also plays plain VBAN named `"$StreamName`", which anyone on this network can send or listen to. Run the setup again without it to turn that off."
}
$addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
    ForEach-Object { $_.IPAddress }
if ($addresses) { Say "This PC's addresses: $($addresses -join ', ')" $null }
Finish 0
