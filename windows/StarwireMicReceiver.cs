// Starwire microphone receiver for Windows (GPL-3.0, part of Starwire).
//
// Receives the microphone Starwire sends as VBAN (VB-Audio's network audio format: plain PCM over
// UDP) and plays it into a virtual microphone, so voice chat and games on the PC can use it. By
// default it plays into VB-Cable's "CABLE Input", which apps then hear as the "CABLE Output"
// microphone. Works whatever streaming host the PC runs.
//
// It only accepts audio from home-network and private VPN addresses (VBAN isn't encrypted), and
// only the stream name it's told to listen for.
//
// It runs without a window: an icon in the notification area shows what it's doing and has a menu
// to check the virtual microphone, play a test tone or quit.
//
//   StarwireMicReceiver.exe [--device "CABLE Input"] [--name Stream1] [--port 6980]
//   StarwireMicReceiver.exe --list               the audio outputs it can play into
//   StarwireMicReceiver.exe --test-tone [secs]   sends a tone to a receiver running on this PC
//   StarwireMicReceiver.exe --check [--input "CABLE Output"]
//                                                plays a tone into the output and listens on the
//                                                matching microphone, to show the cable works
//   --console                                    runs in a console window instead (troubleshooting)
//   --loopback                                   listen on this PC only (for testing)
//
// From PowerShell, pipe the output of --list, --check and --test-tone (for example `| Out-Host`):
// a program without a window isn't waited for otherwise.
//
// setup-microphone.ps1 builds this with the C# compiler that comes with Windows, so it's written
// for C# 5.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class StarwireMicReceiver
{
    const int HeaderSize = 28;
    static readonly int[] VbanSampleRates = {
        6000, 12000, 24000, 48000, 96000, 192000, 384000,
        8000, 16000, 32000, 64000, 128000, 256000, 512000,
        11025, 22050, 44100, 88200, 176400, 352800, 705600
    };

    [DllImport("kernel32.dll")] static extern bool AttachConsole(int processId);
    [DllImport("kernel32.dll")] static extern bool AllocConsole();

    // The notification-area icon, or null with --console and the one-off tools (they print instead)
    static Tray tray;
    static int lastPacket = Environment.TickCount;

    // No packets for 3 seconds: the phone stopped sending (stream ended, or the mic was turned off)
    public static bool Idle { get { return Environment.TickCount - lastPacket > 3000; } }

    [STAThread]
    static int Main(string[] args)
    {
        string device = "CABLE Input";
        string streamName = "Stream1";
        int port = 6980;
        bool loopback = false;
        bool check = false;
        bool list = false;
        bool console = false;
        string input = null;
        int toneSeconds = 0;
        for (int i = 0; i < args.Length; i++) {
            string a = args[i].ToLowerInvariant();
            if (a == "--list") { list = true; continue; }
            if (a == "--console") { console = true; continue; }
            if (a == "--loopback") { loopback = true; continue; }
            if (a == "--check") { check = true; continue; }
            if (a == "--test-tone") {
                toneSeconds = 3;
                int seconds;
                if (i + 1 < args.Length && int.TryParse(args[i + 1], out seconds)) {
                    toneSeconds = seconds;
                    i++;
                }
                continue;
            }
            if (i + 1 >= args.Length) {
                break;
            }
            if (a == "--device") { device = args[++i]; }
            else if (a == "--input") { input = args[++i]; }
            else if (a == "--name") { streamName = args[++i]; }
            else if (a == "--port") { port = int.Parse(args[++i]); }
        }

        // Built without a window of its own: the tools print to the terminal they were run from
        if ((list || check || toneSeconds > 0) && !Console.IsOutputRedirected) {
            AttachConsole(-1);
        }
        if (list) {
            ListDevices();
            return 0;
        }
        if (toneSeconds > 0) {
            SendTestTone(port, streamName, toneSeconds);
            return 0;
        }

        int deviceId = WaveOut.FindDevice(device);
        if (deviceId < 0 && check) {
            Console.WriteLine("No audio output named like \"" + device + "\". Outputs on this PC:");
            ListDevices();
            Console.WriteLine("Install VB-Cable (setup-microphone.ps1 does it), or pick one with --device.");
            return 2;
        }

        if (check) {
            // VB-Cable's microphone side is "CABLE Output"; anything else needs --input
            if (input == null && device.IndexOf("CABLE Input", StringComparison.OrdinalIgnoreCase) >= 0) {
                input = device.Replace("Input", "Output").Replace("input", "output");
            }
            if (input == null) {
                Console.WriteLine("Say which microphone to listen on with --input.");
                return 2;
            }
            return CheckCable(device, input, Console.WriteLine) ? 0 : 3;
        }

        if (console) {
            AllocConsole();
            Console.Title = "Starwire microphone receiver";
        }
        // One receiver at a time (a second one would only find the port taken)
        bool first;
        Mutex single = new Mutex(true, "StarwireMicReceiver", out first);
        if (!first) {
            Fail(console, "Starwire's microphone receiver is already running. Its icon is in the notification area, by the clock.");
            return 0;
        }

        Socket socket;
        try {
            if (loopback) {
                socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
                socket.Bind(new IPEndPoint(IPAddress.Loopback, port));
            }
            else {
                socket = new Socket(AddressFamily.InterNetworkV6, SocketType.Dgram, ProtocolType.Udp);
                socket.DualMode = true;
                socket.Bind(new IPEndPoint(IPAddress.IPv6Any, port));
            }
        }
        catch (SocketException e) {
            Fail(console, e.SocketErrorCode == SocketError.AddressAlreadyInUse
                ? "UDP port " + port + " is already in use by another program, often Voicemeeter's VBAN or VBAN-Receptor. "
                    + "Close it or turn its VBAN off, or run the setup with -Port <another> and set the same VBAN port in Starwire."
                : "Couldn't listen on UDP port " + port + ": " + e.Message);
            return 4;
        }

        if (console) {
            Console.WriteLine("Starwire microphone receiver");
            Console.WriteLine("  Listening for \"" + streamName + "\" on UDP port " + port);
            Console.WriteLine("  Playing into " + (deviceId >= 0 ? WaveOut.DeviceName(deviceId) : device));
            // Kept short so the lines fit a small console window
            Console.WriteLine("  In Starwire: Settings > Sound & vibration > Send my microphone,");
            Console.WriteLine("  sent to \"A VBAN receiver on the PC\"");
            Console.WriteLine("Close this window to stop.");
            Receive(socket, device, streamName, loopback);
            return 0;
        }

        Application.EnableVisualStyles();
        tray = new Tray(device, streamName, port);
        Thread receiver = new Thread(() => Receive(socket, device, streamName, loopback));
        receiver.IsBackground = true;
        receiver.Start();
        Application.Run();
        GC.KeepAlive(single);
        return 0;
    }

    // A problem that stops the receiver: in its window with --console, otherwise in a message box
    static void Fail(bool console, string reason)
    {
        if (console) {
            Console.WriteLine(reason);
            Console.WriteLine("Press Enter to close.");
            Console.ReadLine();
        }
        else {
            MessageBox.Show(reason, "Starwire microphone", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    // What the receiver has to say: printed when there's no tray icon (--console and the tools),
    // otherwise shown on the icon, the important ones as a notification
    public static void Report(string text, bool important)
    {
        if (tray == null) {
            Console.WriteLine(text);
        }
        else {
            tray.Show(text, important);
        }
    }

    static void Receive(Socket socket, string device, string streamName, bool loopback)
    {
        byte[] wantedName = StreamNameBytes(streamName);
        Player player = new Player(device);
        byte[] buffer = new byte[2048];
        string currentSender = null;
        bool warnedFormat = false;
        HashSet<string> refused = new HashSet<string>();
        EndPoint from = new IPEndPoint(loopback ? IPAddress.Any : IPAddress.IPv6Any, 0);

        while (true) {
            int length;
            try {
                length = socket.ReceiveFrom(buffer, ref from);
            }
            catch (SocketException) {
                // A sender went away (Windows reports it on UDP sockets); keep listening
                continue;
            }
            if (length <= HeaderSize || buffer[0] != 'V' || buffer[1] != 'B' || buffer[2] != 'A' || buffer[3] != 'N') {
                continue;
            }

            IPAddress source = ((IPEndPoint) from).Address;
            if (source.IsIPv4MappedToIPv6) {
                source = source.MapToIPv4();
            }
            if (!IsHomeNetwork(source)) {
                if (refused.Add(source.ToString())) {
                    Report("Ignoring audio from " + source + ": not on the home network.", true);
                }
                continue;
            }
            if (!NameMatches(buffer, wantedName)) {
                continue;
            }

            int protocol = buffer[4] & 0xE0;
            int rateIndex = buffer[4] & 0x1F;
            int samples = buffer[5] + 1;
            int channels = buffer[6] + 1;
            int format = buffer[7] & 0x07;
            int codec = buffer[7] & 0xF0;
            if (protocol != 0 || codec != 0 || format != 1 || rateIndex >= VbanSampleRates.Length
                    || channels > 2 || length != HeaderSize + samples * channels * 2) {
                if (!warnedFormat) {
                    warnedFormat = true;
                    Report("Ignoring a VBAN stream that isn't 16-bit PCM in one or two channels.", true);
                }
                continue;
            }

            string sender = source.ToString();
            if (sender != currentSender || Idle) {
                currentSender = sender;
                Report("Receiving the microphone from " + sender, false);
            }
            lastPacket = Environment.TickCount;
            player.Play(buffer, HeaderSize, samples, channels, VbanSampleRates[rateIndex]);
        }
    }

    // Home-network and private VPN addresses (Tailscale uses 100.64.0.0/10 and fd7a::/16)
    static bool IsHomeNetwork(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) {
            return true;
        }
        byte[] b = address.GetAddressBytes();
        if (address.AddressFamily == AddressFamily.InterNetwork) {
            return b[0] == 10
                || (b[0] == 172 && (b[1] & 0xF0) == 16)
                || (b[0] == 192 && b[1] == 168)
                || (b[0] == 169 && b[1] == 254)
                || (b[0] == 100 && (b[1] & 0xC0) == 64);
        }
        return address.IsIPv6LinkLocal || address.IsIPv6SiteLocal || (b[0] & 0xFE) == 0xFC;
    }

    static byte[] StreamNameBytes(string name)
    {
        byte[] result = new byte[16];
        byte[] ascii = Encoding.ASCII.GetBytes(name.Trim());
        Array.Copy(ascii, result, Math.Min(ascii.Length, 16));
        return result;
    }

    // VBAN stream names are compared without case, as VB-Audio's own apps do
    static bool NameMatches(byte[] packet, byte[] wanted)
    {
        for (int i = 0; i < 16; i++) {
            if (char.ToLowerInvariant((char) packet[8 + i]) != char.ToLowerInvariant((char) wanted[i])) {
                return false;
            }
        }
        return true;
    }

    // Plays a tone into the output for a moment while recording from the microphone it should
    // come out of. A virtual cable that works passes it straight through.
    public static bool CheckCable(string outputName, string inputName, Action<string> say)
    {
        int inputId = WaveIn.FindDevice(inputName);
        int outputId = WaveOut.FindDevice(outputName);
        if (inputId < 0 || outputId < 0) {
            say("No " + (inputId < 0 ? "microphone named like \"" + inputName : "output named like \"" + outputName)
                + "\". If VB-Cable was just installed, restart Windows and check again.");
            return false;
        }
        say("Checking " + WaveOut.DeviceName(outputId) + " -> " + WaveIn.DeviceName(inputId) + "...");

        WaveIn recorder = new WaveIn(inputId, 48000, 48000 * 2);   // two seconds
        PlayTone(outputName, 150, 1000);                            // 1.5 seconds of tone
        int peak = recorder.Finish();
        // The tone is 8000; anything over a tenth of that means it came through
        if (peak > 800) {
            say("Works: the microphone heard the test tone.");
            return true;
        }
        say("No sound came through (loudest sample " + peak + "). If VB-Cable was just installed, restart Windows and check again.");
        return false;
    }

    // Plays a tone straight into an output, in 10 ms blocks
    public static void PlayTone(string outputName, int blocks, int frequency)
    {
        const int rate = 48000;
        Player player = new Player(outputName);
        byte[] packet = new byte[HeaderSize + 480 * 2];
        double phase = 0;
        System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
        for (int block = 0; block < blocks; block++) {
            while (clock.ElapsedMilliseconds < block * 10L) {
                Thread.Sleep(1);
            }
            for (int i = 0; i < 480; i++) {
                short value = (short) (Math.Sin(phase) * 8000);
                packet[HeaderSize + i * 2] = (byte) value;
                packet[HeaderSize + i * 2 + 1] = (byte) (value >> 8);
                phase += 2 * Math.PI * frequency / rate;
            }
            player.Play(packet, HeaderSize, 480, 1, rate);
        }
        // Let the last blocks play out before letting go of the output
        Thread.Sleep(200);
        player.Close();
    }

    // A 440 Hz tone in the same format Starwire sends, to check the receiver and microphone
    // without the phone
    static void SendTestTone(int port, string streamName, int seconds)
    {
        const int samples = 256, rate = 48000;
        byte[] packet = new byte[HeaderSize + samples * 2];
        packet[0] = (byte) 'V'; packet[1] = (byte) 'B'; packet[2] = (byte) 'A'; packet[3] = (byte) 'N';
        packet[4] = 3;                  // 48000 Hz
        packet[5] = samples - 1;
        packet[6] = 0;                  // mono
        packet[7] = 1;                  // 16-bit PCM
        Array.Copy(StreamNameBytes(streamName), 0, packet, 8, 16);

        Socket socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        IPEndPoint target = new IPEndPoint(IPAddress.Loopback, port);
        Console.WriteLine("Sending a " + seconds + " second test tone to the receiver on this PC...");
        double phase = 0;
        int total = seconds * rate / samples;
        System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
        for (int frame = 0; frame < total; frame++) {
            // Keep to real time
            while (clock.ElapsedMilliseconds < (long) frame * samples * 1000 / rate) {
                System.Threading.Thread.Sleep(1);
            }
            BitConverter.GetBytes(frame).CopyTo(packet, 24);
            for (int i = 0; i < samples; i++) {
                short value = (short) (Math.Sin(phase) * 8000);
                packet[HeaderSize + i * 2] = (byte) value;
                packet[HeaderSize + i * 2 + 1] = (byte) (value >> 8);
                phase += 2 * Math.PI * 440 / rate;
            }
            socket.SendTo(packet, target);
        }
        Console.WriteLine("Done. If the receiver is running, the microphone heard a beep.");
    }

    static void ListDevices()
    {
        int count = WaveOut.waveOutGetNumDevs();
        for (int i = 0; i < count; i++) {
            Console.WriteLine("  " + WaveOut.DeviceName(i));
        }
    }
}

// The notification-area icon: hover for what the receiver is doing, right-click for its menu
sealed class Tray
{
    const string Guide = "https://github.com/SicarioGaming/Starwire/tree/main/windows#readme";

    readonly NotifyIcon icon = new NotifyIcon();
    readonly ToolStripMenuItem status = new ToolStripMenuItem();
    readonly SynchronizationContext ui = new WindowsFormsSynchronizationContext();
    readonly string device;
    readonly string waiting;
    bool receiving;

    public Tray(string device, string streamName, int port)
    {
        this.device = device;
        waiting = "Waiting for Starwire (" + streamName + ", UDP " + port + ")";
        status.Enabled = false;
        ContextMenuStrip menu = new ContextMenuStrip();
        menu.Items.Add(status);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Check the virtual microphone", null, delegate { Check(); });
        menu.Items.Add("Play a test tone (for Discord's mic test)", null, delegate { Tone(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Setup guide", null, delegate { System.Diagnostics.Process.Start(Guide); });
        menu.Items.Add("Quit", null, delegate { icon.Visible = false; Environment.Exit(0); });
        icon.ContextMenuStrip = menu;
        icon.Icon = LoadIcon();
        SetStatus(waiting);
        icon.Visible = true;

        // Back to "waiting" a few seconds after the phone stops sending
        System.Windows.Forms.Timer idle = new System.Windows.Forms.Timer();
        idle.Interval = 1000;
        idle.Tick += delegate {
            if (receiving && StarwireMicReceiver.Idle) {
                receiving = false;
                SetStatus(waiting);
            }
        };
        idle.Start();
    }

    // Called from the receive thread
    public void Show(string text, bool important)
    {
        ui.Post(delegate {
            if (important) {
                icon.ShowBalloonTip(8000, "Starwire microphone", text, ToolTipIcon.Warning);
            }
            else {
                receiving = text.StartsWith("Receiving");
                SetStatus(text);
            }
        }, null);
    }

    void SetStatus(string text)
    {
        status.Text = text;
        // Windows cuts the hover text at 63 characters
        string tip = "Starwire microphone: " + text;
        icon.Text = tip.Length > 63 ? tip.Substring(0, 60) + "..." : tip;
    }

    void Check()
    {
        ThreadPool.QueueUserWorkItem(delegate {
            List<string> lines = new List<string>();
            bool works = StarwireMicReceiver.CheckCable(device, device.Replace("Input", "Output"), lines.Add);
            ui.Post(delegate {
                MessageBox.Show(string.Join(Environment.NewLine, lines.ToArray()), "Starwire microphone",
                    MessageBoxButtons.OK, works ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            }, null);
        });
    }

    void Tone()
    {
        icon.ShowBalloonTip(3000, "Starwire microphone", "Playing a 3 second tone into the virtual microphone.", ToolTipIcon.Info);
        ThreadPool.QueueUserWorkItem(delegate { StarwireMicReceiver.PlayTone(device, 300, 440); });
    }

    // The Starwire icon the setup puts next to the receiver, else the one built into it
    static Icon LoadIcon()
    {
        try {
            string ico = System.IO.Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "starwire-mic.ico");
            if (System.IO.File.Exists(ico)) {
                return new Icon(ico, SystemInformation.SmallIconSize);
            }
            return Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        }
        catch (Exception) {
            return SystemIcons.Application;
        }
    }
}

// Plays mono 16-bit PCM through Windows' waveOut API in 10 ms blocks. A short buffer rides out
// Wi-Fi jitter; when audio piles up past it (a burst, or clock drift), blocks are skipped so the
// delay stays low.
sealed class Player
{
    const int BlockMs = 10;
    const int StartBlocks = 4;   // 40 ms of audio before playback (re)starts
    const int MaxBlocks = 12;    // never more than 120 ms behind
    // The phone's audio clock never quite matches the PC's (60 ppm apart in testing, about 3.6 ms
    // a minute), so a phone running fast slowly fills the buffer. If it hasn't dipped below
    // TrimBlocks for TrimMs, one block is skipped so the delay stays near where it started.
    const int TrimBlocks = 6;    // 60 ms
    const int TrimMs = 2000;

    const int StallMs = 1000;    // queued audio that hasn't moved for this long means the output died
    const int RetryMs = 1000;    // how often to try opening the output again after a failure

    // Looked up by name each time it's opened: the device's number can change after the receiver
    // starts (headsets plugged in, VB-Cable installed or updated)
    readonly string deviceName;
    IntPtr handle = IntPtr.Zero;
    int sampleRate;
    short[] block;
    int blockFill;
    readonly List<IntPtr> queued = new List<IntPtr>();
    bool starting = true;
    readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
    long lastProgress;
    long lastBelowTrim;
    long nextOpenAttempt;
    bool openFailed;

    public Player(string deviceName)
    {
        this.deviceName = deviceName;
    }

    public void Play(byte[] packet, int offset, int samples, int channels, int rate)
    {
        if (handle == IntPtr.Zero || rate != sampleRate) {
            if (clock.ElapsedMilliseconds < nextOpenAttempt || !Open(rate)) {
                // Drop the audio until the output can be opened again
                return;
            }
        }
        for (int i = 0; i < samples; i++) {
            int at = offset + i * channels * 2;
            int value = (short) (packet[at] | packet[at + 1] << 8);
            if (channels == 2) {
                value = (value + (short) (packet[at + 2] | packet[at + 3] << 8)) / 2;
            }
            block[blockFill++] = (short) value;
            if (blockFill == block.Length) {
                blockFill = 0;
                if (!Submit()) {
                    return;
                }
            }
        }
    }

    bool Open(int rate)
    {
        Close();
        int deviceId = WaveOut.FindDevice(deviceName);
        int result = -1;
        if (deviceId >= 0) {
            WaveOut.WAVEFORMATEX format = new WaveOut.WAVEFORMATEX();
            format.wFormatTag = 1;
            format.nChannels = 1;
            format.nSamplesPerSec = rate;
            format.wBitsPerSample = 16;
            format.nBlockAlign = 2;
            format.nAvgBytesPerSec = rate * 2;
            result = WaveOut.waveOutOpen(out handle, deviceId, ref format, IntPtr.Zero, IntPtr.Zero, 0);
        }
        if (result != 0) {
            handle = IntPtr.Zero;
            nextOpenAttempt = clock.ElapsedMilliseconds + RetryMs;
            if (!openFailed) {
                openFailed = true;
                StarwireMicReceiver.Report(deviceId < 0
                        ? "Can't find \"" + deviceName + "\" right now; trying again every second. Is VB-Cable installed?"
                        : "Couldn't open " + WaveOut.DeviceName(deviceId) + " (waveOut error " + result + "); trying again every second.", true);
            }
            return false;
        }
        if (openFailed) {
            openFailed = false;
            StarwireMicReceiver.Report("Playing into " + WaveOut.DeviceName(deviceId) + " again.", true);
        }
        sampleRate = rate;
        block = new short[rate * BlockMs / 1000];
        blockFill = 0;
        starting = true;
        WaveOut.waveOutPause(handle);
        lastProgress = clock.ElapsedMilliseconds;
        return true;
    }

    public void Close()
    {
        if (handle == IntPtr.Zero) {
            return;
        }
        WaveOut.waveOutReset(handle);
        Reclaim(true);
        WaveOut.waveOutClose(handle);
        handle = IntPtr.Zero;
        sampleRate = 0;
    }

    // Queues the current block. Returns false if the output failed and was closed.
    bool Submit()
    {
        Reclaim(false);
        long now = clock.ElapsedMilliseconds;
        if (queued.Count > 0 && !starting && now - lastProgress > StallMs) {
            // Nothing has finished playing for a second: the device stopped (unplugged, driver
            // restarted). Open it again with the next packet.
            StarwireMicReceiver.Report("The audio output stopped responding; reopening it.", false);
            Close();
            return false;
        }
        if (queued.Count == 0 && !starting) {
            // Ran dry (the phone paused, or Wi-Fi hiccuped): build up a little audio again first
            starting = true;
            WaveOut.waveOutPause(handle);
        }
        if (queued.Count >= MaxBlocks) {
            return true;
        }
        if (starting || queued.Count < TrimBlocks) {
            lastBelowTrim = now;
        }
        else if (now - lastBelowTrim > TrimMs) {
            // Clock drift has built the buffer up: skip this block
            lastBelowTrim = now;
            return true;
        }

        int bytes = block.Length * 2;
        IntPtr data = Marshal.AllocHGlobal(bytes);
        Marshal.Copy(block, 0, data, block.Length);
        WaveOut.WAVEHDR header = new WaveOut.WAVEHDR();
        header.lpData = data;
        header.dwBufferLength = bytes;
        int size = Marshal.SizeOf(typeof(WaveOut.WAVEHDR));
        IntPtr headerPtr = Marshal.AllocHGlobal(size);
        Marshal.StructureToPtr(header, headerPtr, false);
        int result = WaveOut.waveOutPrepareHeader(handle, headerPtr, size);
        if (result == 0) {
            result = WaveOut.waveOutWrite(handle, headerPtr, size);
            if (result != 0) {
                WaveOut.waveOutUnprepareHeader(handle, headerPtr, size);
            }
        }
        if (result != 0) {
            Marshal.FreeHGlobal(data);
            Marshal.FreeHGlobal(headerPtr);
            StarwireMicReceiver.Report("The audio output refused audio (waveOut error " + result + "); reopening it.", false);
            Close();
            return false;
        }
        queued.Add(headerPtr);

        if (starting && queued.Count >= StartBlocks) {
            starting = false;
            WaveOut.waveOutRestart(handle);
            lastProgress = now;
        }
        return true;
    }

    // Frees blocks that have finished playing (or all of them when closing)
    void Reclaim(bool all)
    {
        int size = Marshal.SizeOf(typeof(WaveOut.WAVEHDR));
        for (int i = queued.Count - 1; i >= 0; i--) {
            IntPtr headerPtr = queued[i];
            WaveOut.WAVEHDR header = (WaveOut.WAVEHDR) Marshal.PtrToStructure(headerPtr, typeof(WaveOut.WAVEHDR));
            if (all || (header.dwFlags & WaveOut.WHDR_DONE) != 0) {
                WaveOut.waveOutUnprepareHeader(handle, headerPtr, size);
                Marshal.FreeHGlobal(header.lpData);
                Marshal.FreeHGlobal(headerPtr);
                queued.RemoveAt(i);
                if (!all) {
                    lastProgress = clock.ElapsedMilliseconds;
                }
            }
        }
    }
}

// Records one buffer from a microphone, for --check
sealed class WaveIn
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct WAVEINCAPS
    {
        public short wMid;
        public short wPid;
        public int vDriverVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string szPname;
        public int dwFormats;
        public short wChannels;
        public short wReserved1;
    }

    [DllImport("winmm.dll")] static extern int waveInGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode, EntryPoint = "waveInGetDevCapsW")] static extern int waveInGetDevCaps(IntPtr id, out WAVEINCAPS caps, int size);
    [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr handle, int deviceId, ref WaveOut.WAVEFORMATEX format, IntPtr callback, IntPtr instance, int flags);
    [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInStart(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInReset(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInClose(IntPtr handle);

    readonly IntPtr handle;
    readonly IntPtr data;
    readonly IntPtr headerPtr;
    readonly int headerSize = Marshal.SizeOf(typeof(WaveOut.WAVEHDR));

    public WaveIn(int deviceId, int rate, int samples)
    {
        WaveOut.WAVEFORMATEX format = new WaveOut.WAVEFORMATEX();
        format.wFormatTag = 1;
        format.nChannels = 1;
        format.nSamplesPerSec = rate;
        format.wBitsPerSample = 16;
        format.nBlockAlign = 2;
        format.nAvgBytesPerSec = rate * 2;
        int result = waveInOpen(out handle, deviceId, ref format, IntPtr.Zero, IntPtr.Zero, 0);
        if (result != 0) {
            throw new InvalidOperationException("Couldn't open the microphone (waveIn error " + result + ")");
        }
        data = Marshal.AllocHGlobal(samples * 2);
        WaveOut.WAVEHDR header = new WaveOut.WAVEHDR();
        header.lpData = data;
        header.dwBufferLength = samples * 2;
        headerPtr = Marshal.AllocHGlobal(headerSize);
        Marshal.StructureToPtr(header, headerPtr, false);
        waveInPrepareHeader(handle, headerPtr, headerSize);
        waveInAddBuffer(handle, headerPtr, headerSize);
        waveInStart(handle);
    }

    // Stops recording and returns the loudest sample heard
    public int Finish()
    {
        System.Threading.Thread.Sleep(300);
        waveInReset(handle);
        WaveOut.WAVEHDR header = (WaveOut.WAVEHDR) Marshal.PtrToStructure(headerPtr, typeof(WaveOut.WAVEHDR));
        short[] samples = new short[header.dwBytesRecorded / 2];
        Marshal.Copy(data, samples, 0, samples.Length);
        waveInUnprepareHeader(handle, headerPtr, headerSize);
        waveInClose(handle);
        Marshal.FreeHGlobal(headerPtr);
        Marshal.FreeHGlobal(data);
        int peak = 0;
        foreach (short s in samples) {
            peak = Math.Max(peak, Math.Abs((int) s));
        }
        return peak;
    }

    public static string DeviceName(int id)
    {
        WAVEINCAPS caps;
        waveInGetDevCaps(new IntPtr(id), out caps, Marshal.SizeOf(typeof(WAVEINCAPS)));
        return caps.szPname;
    }

    public static int FindDevice(string text)
    {
        int count = waveInGetNumDevs();
        for (int i = 0; i < count; i++) {
            if (DeviceName(i).StartsWith(text, StringComparison.OrdinalIgnoreCase)) {
                return i;
            }
        }
        return -1;
    }
}

static class WaveOut
{
    public const int WHDR_DONE = 1;

    [StructLayout(LayoutKind.Sequential)]
    public struct WAVEFORMATEX
    {
        public short wFormatTag;
        public short nChannels;
        public int nSamplesPerSec;
        public int nAvgBytesPerSec;
        public short nBlockAlign;
        public short wBitsPerSample;
        public short cbSize;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct WAVEHDR
    {
        public IntPtr lpData;
        public int dwBufferLength;
        public int dwBytesRecorded;
        public IntPtr dwUser;
        public int dwFlags;
        public int dwLoops;
        public IntPtr lpNext;
        public IntPtr reserved;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct WAVEOUTCAPS
    {
        public short wMid;
        public short wPid;
        public int vDriverVersion;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)]
        public string szPname;
        public int dwFormats;
        public short wChannels;
        public short wReserved1;
        public int dwSupport;
    }

    [DllImport("winmm.dll")] public static extern int waveOutGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode, EntryPoint = "waveOutGetDevCapsW")] static extern int waveOutGetDevCaps(IntPtr id, out WAVEOUTCAPS caps, int size);
    [DllImport("winmm.dll")] public static extern int waveOutOpen(out IntPtr handle, int deviceId, ref WAVEFORMATEX format, IntPtr callback, IntPtr instance, int flags);
    [DllImport("winmm.dll")] public static extern int waveOutPrepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] public static extern int waveOutUnprepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] public static extern int waveOutWrite(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] public static extern int waveOutPause(IntPtr handle);
    [DllImport("winmm.dll")] public static extern int waveOutRestart(IntPtr handle);
    [DllImport("winmm.dll")] public static extern int waveOutReset(IntPtr handle);
    [DllImport("winmm.dll")] public static extern int waveOutClose(IntPtr handle);

    public static string DeviceName(int id)
    {
        WAVEOUTCAPS caps;
        waveOutGetDevCaps(new IntPtr(id), out caps, Marshal.SizeOf(typeof(WAVEOUTCAPS)));
        return caps.szPname;
    }

    // The first output whose name starts with the text, as Windows shows it (cut to 31 characters).
    // Matching the start keeps "CABLE Input" from also matching VB-Audio's "Hi-Fi Cable Input".
    public static int FindDevice(string text)
    {
        int count = waveOutGetNumDevs();
        for (int i = 0; i < count; i++) {
            if (DeviceName(i).StartsWith(text, StringComparison.OrdinalIgnoreCase)) {
                return i;
            }
        }
        return -1;
    }
}
