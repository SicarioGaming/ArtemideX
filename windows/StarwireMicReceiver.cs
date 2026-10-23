// Starwire microphone receiver for Windows (GPL-3.0, part of Starwire).
//
// Receives the microphone Starwire sends (VBAN, VB-Audio's network audio format, inside Starwire's
// encrypted SWMC format) and plays it into a virtual microphone, so voice chat and games on the PC
// can use it. By default it plays into VB-Cable's "CABLE Input", which apps then hear as the
// "CABLE Output" microphone. Works whatever streaming host the PC runs.
//
// Only devices paired with this PC can send it audio, and the audio is encrypted on the way
// (SecureMic.cs): the icon's "Pair a device..." shows a one-time code to type into Starwire.
// Plain VBAN (from Starwire 0.2.0, Voicemeeter or VBAN-Receptor) is refused unless the receiver
// runs with --allow-unencrypted. As an extra layer it still only takes packets from home-network
// and private VPN addresses, the ones its firewall rule lets in.
//
// It plays through WASAPI at the shortest period the output allows, which VB-Cable passes on with
// less delay than Windows' older waveOut API. If WASAPI doesn't work, it uses waveOut instead.
//
// It runs without a window: an icon in the notification area shows what it's doing and has a menu
// to pair a device, unpair one, check the virtual microphone, play a test tone or quit.
//
//   StarwireMicReceiver.exe [--device "CABLE Input"] [--port 6980]
//   StarwireMicReceiver.exe --list               the audio outputs it can play into
//   StarwireMicReceiver.exe --test-tone [secs]   sends a tone as plain VBAN to a receiver on this
//                                                PC; it only plays with --allow-unencrypted
//   StarwireMicReceiver.exe --check [--input "CABLE Output"]
//                                                plays a tone into the output and listens on the
//                                                matching microphone, to show the cable works
//   --console                                    runs in a console window instead (troubleshooting)
//   --pair                                       shows a pairing code as it starts (printed with
//                                                --console)
//   --allow-unencrypted [--name Stream1]         also plays plain VBAN with that stream name from
//                                                home-network addresses. Not safe: anyone on the
//                                                network can then send sound into the microphone
//                                                or listen to it.
//   --stats                                      with --console, prints the audio buffer's state
//                                                every few seconds
//   --waveout                                    plays through waveOut, not WASAPI
//   --loopback                                   listen on this PC only (for testing)
//   --pairings-file <path>                       keeps the paired devices there instead of
//                                                %LOCALAPPDATA%\Starwire\mic-pairings.dat (for
//                                                testing: a receiver with its own file can run
//                                                next to the usual one)
//   --exit-after-idle <secs>                     with --console, once packets have come, stops
//                                                when none have for that long and prints what it
//                                                played and refused (for tests)
//
// From PowerShell, pipe the output of --list, --check and --test-tone (for example `| Out-Host`):
// a program without a window isn't waited for otherwise. --console with its output piped or
// redirected prints there instead of opening a window.
//
// setup-microphone.ps1 builds this and SecureMic.cs with the C# compiler that comes with Windows,
// so they're written for C# 5.

using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

static class StarwireMicReceiver
{
    const int HeaderSize = Vban.HeaderSize;

    static readonly RandomNumberGenerator rng = RandomNumberGenerator.Create();
    static readonly System.Diagnostics.Stopwatch uptime = System.Diagnostics.Stopwatch.StartNew();

    [DllImport("kernel32.dll")] static extern bool AttachConsole(int processId);
    [DllImport("kernel32.dll")] static extern bool AllocConsole();

    // The notification-area icon, or null with --console and the one-off tools (they print instead)
    static Tray tray;
    static int lastPacket = Environment.TickCount;
    static bool stats;
    const int StatsMs = 2000;

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
        bool allowUnencrypted = false;
        bool pairNow = false;
        string pairingsFile = null;
        int exitAfterIdle = 0;
        string input = null;
        int toneSeconds = 0;
        for (int i = 0; i < args.Length; i++) {
            string a = args[i].ToLowerInvariant();
            if (a == "--list") { list = true; continue; }
            if (a == "--console") { console = true; continue; }
            if (a == "--loopback") { loopback = true; continue; }
            if (a == "--check") { check = true; continue; }
            if (a == "--stats") { stats = true; continue; }
            if (a == "--waveout") { Player.ForceWaveOut = true; continue; }
            if (a == "--allow-unencrypted") { allowUnencrypted = true; continue; }
            if (a == "--pair") { pairNow = true; continue; }
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
            else if (a == "--pairings-file") { pairingsFile = args[++i]; }
            else if (a == "--exit-after-idle") { exitAfterIdle = int.Parse(args[++i]); }
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

        // Printing to a pipe or a file (tests): no window
        if (console && !Console.IsOutputRedirected) {
            AllocConsole();
            Console.Title = "Starwire microphone receiver";
        }
        // One receiver per pairings file at a time (a second one would only find the port taken,
        // and both would write the file)
        bool first;
        Mutex single = new Mutex(true, InstanceName(pairingsFile), out first);
        if (!first) {
            Fail(console, "Starwire's microphone receiver is already running. Its icon is in the notification area, by the clock.");
            return 0;
        }
        MicPairingStore store = new MicPairingStore(pairingsFile != null ? Path.GetFullPath(pairingsFile) : MicPairingStore.DefaultPath());
        store.Load(Random);

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

        SecureMicEndpoint endpoint = new SecureMicEndpoint(store, () => uptime.ElapsedMilliseconds, Random,
            (data, count, to) => SendQuietly(socket, data, count, to), EndpointSays);
        endpoint.AllowUnencrypted = allowUnencrypted;
        endpoint.StreamName = Vban.StreamNameBytes(streamName);
        // For HELLO_ACK's "no output" status; asked at most every 2 seconds
        endpoint.OutputReady = () => WaveOut.FindDevice(device) >= 0;

        if (console) {
            // Kept short so the lines fit a small console window
            Console.WriteLine("Starwire microphone receiver");
            Console.WriteLine("  Listening on UDP port " + port + " for paired devices (encrypted)");
            if (allowUnencrypted) {
                Console.WriteLine("  WARNING: --allow-unencrypted is on, so it also plays plain VBAN");
                Console.WriteLine("  named \"" + streamName + "\" from home-network addresses. Anyone on");
                Console.WriteLine("  the network can then send sound into this PC's microphone or");
                Console.WriteLine("  listen to it. Leave it off unless another VBAN app needs it.");
            }
            List<MicPairing> paired = endpoint.Devices();
            List<string> names = new List<string>();
            foreach (MicPairing p in paired) {
                names.Add(p.Name);
            }
            Console.WriteLine(paired.Count == 0 ? "  No devices paired yet" : "  Paired: " + string.Join(", ", names.ToArray()));
            foreach (string problem in store.Problems) {
                Console.WriteLine("  Paired devices file: " + problem);
            }
            Console.WriteLine("  Playing into " + (deviceId >= 0 ? WaveOut.DeviceName(deviceId) : device));
            if (pairNow) {
                Console.WriteLine("  Pairing code: " + endpoint.BeginPairing());
                Console.WriteLine("  In Starwire: Settings > Sound & vibration > Send my microphone >");
                Console.WriteLine("  Pair with the PC, and type it. It works once, for 5 minutes.");
            }
            else {
                Console.WriteLine("  To pair a device, start this with --pair");
            }
            Console.WriteLine("Close this window to stop.");
            Receive(socket, device, endpoint, loopback, exitAfterIdle * 1000);
            return 0;
        }

        Application.EnableVisualStyles();
        tray = new Tray(device, streamName, port, endpoint, allowUnencrypted);
        if (store.Problems.Count > 0) {
            Report("Some paired devices couldn't be read (" + store.Problems[0] + "). If one stops working, pair it again.", true);
        }
        if (pairNow) {
            tray.OpenPairing();
        }
        Thread receiver = new Thread(() => Receive(socket, device, endpoint, loopback, 0));
        receiver.IsBackground = true;
        receiver.Start();
        Application.Run();
        GC.KeepAlive(single);
        return 0;
    }

    static void Random(byte[] bytes)
    {
        lock (rng) {
            rng.GetBytes(bytes);
        }
    }

    // The usual pairings file keeps the name earlier versions used, so they still exclude each other
    static string InstanceName(string pairingsFile)
    {
        if (pairingsFile == null) {
            return "StarwireMicReceiver";
        }
        using (SHA256 sha = SHA256.Create()) {
            byte[] path = Encoding.UTF8.GetBytes(Path.GetFullPath(pairingsFile).ToLowerInvariant());
            return "StarwireMicReceiver." + Swmc.Hex(sha.ComputeHash(path), 0, 8);
        }
    }

    // Answers to devices (HELLO_ACK, REHELLO, pairing); a device that has gone away isn't a problem
    static void SendQuietly(Socket socket, byte[] data, int count, EndPoint to)
    {
        try {
            socket.SendTo(data, 0, count, SocketFlags.None, to);
        }
        catch (SocketException) {
        }
        catch (ObjectDisposedException) {
        }
    }

    // What the encrypted side has to say: the important things like Report's, the rest only with
    // --console
    static void EndpointSays(string text, bool important)
    {
        if (tray == null) {
            Console.WriteLine(text);
        }
        else if (important) {
            tray.Show(text, true);
        }
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

    // Which way the audio goes out (WASAPI, or waveOut and why): printed with --console, otherwise
    // shown in the icon's menu
    public static void ReportOutput(string text)
    {
        if (tray == null) {
            Console.WriteLine("  Output: " + text);
        }
        else {
            tray.ShowOutput(text);
        }
    }

    // The endpoint (SecureMic.cs) decides what plays: audio from a paired device, decrypted, or
    // with --allow-unencrypted plain VBAN too. exitAfterIdleMs is --exit-after-idle's, for tests.
    static void Receive(Socket socket, string device, SecureMicEndpoint endpoint, bool loopback, int exitAfterIdleMs)
    {
        Player player = new Player(device, ReportOutput);
        byte[] buffer = new byte[2048];
        byte[] vban = new byte[2048];
        string currentSender = null;
        bool warnedFormat = false;
        int lastStats = Environment.TickCount;
        int lastArrival = Environment.TickCount;
        bool arrived = false;
        // FNV-1a of the audio played, which --exit-after-idle prints so tests can compare it
        ulong pcmHash = 14695981039346656037UL;
        EndPoint from = new IPEndPoint(loopback ? IPAddress.Any : IPAddress.IPv6Any, 0);
        if (exitAfterIdleMs > 0) {
            socket.ReceiveTimeout = 250;
        }

        while (true) {
            int length;
            try {
                length = socket.ReceiveFrom(buffer, ref from);
            }
            catch (SocketException) {
                // A sender went away (Windows reports it on UDP sockets), or --exit-after-idle's
                // wait ran out; keep listening
                if (exitAfterIdleMs > 0 && arrived && Environment.TickCount - lastArrival > exitAfterIdleMs) {
                    Console.WriteLine("Summary: " + endpoint.Counts() + ", PCM fnv1a64 " + pcmHash.ToString("x16"));
                    player.Close();
                    return;
                }
                continue;
            }
            arrived = true;
            lastArrival = Environment.TickCount;

            int vbanLength;
            string sender;
            bool encrypted;
            if (!endpoint.Handle(buffer, length, from, vban, out vbanLength, out sender, out encrypted)) {
                continue;
            }
            int samples, channels, rate;
            if (!Vban.ParsePcm(vban, vbanLength, out samples, out channels, out rate)) {
                if (!warnedFormat) {
                    warnedFormat = true;
                    Report("Ignoring a VBAN stream that isn't 16-bit PCM in one or two channels.", true);
                }
                continue;
            }

            if (sender != currentSender || Idle) {
                currentSender = sender;
                Report("Receiving the microphone from " + sender + (encrypted ? " (encrypted)" : " (not encrypted)"), false);
            }
            lastPacket = Environment.TickCount;
            if (exitAfterIdleMs > 0) {
                for (int i = HeaderSize; i < vbanLength; i++) {
                    pcmHash = (pcmHash ^ vban[i]) * 1099511628211UL;
                }
            }
            player.Play(vban, HeaderSize, samples, channels, rate);
            if (stats && tray == null && lastPacket - lastStats >= StatsMs) {
                lastStats = lastPacket;
                Console.WriteLine("  " + DateTime.Now.ToString("HH:mm:ss") + " " + player.Stats());
            }
        }
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
        PlayTone(outputName, 150, 1000, how => say("Output: " + how));   // 1.5 seconds of tone
        int peak = recorder.Finish();
        // The tone is 8000; anything over a tenth of that means it came through
        if (peak > 800) {
            say("Works: the microphone heard the test tone.");
            return true;
        }
        say("No sound came through (loudest sample " + peak + "). If VB-Cable was just installed, restart Windows and check again.");
        return false;
    }

    // Plays a tone straight into an output, in 10 ms blocks, the same way the receiver plays.
    // sayOutput (if not null) is told which way it went out.
    public static void PlayTone(string outputName, int blocks, int frequency, Action<string> sayOutput)
    {
        const int rate = 48000;
        Player player = new Player(outputName, sayOutput);
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

    // A 440 Hz tone as plain VBAN, the way Starwire 0.2.0 sent the microphone. Starwire's receiver
    // refuses it now unless it runs with --allow-unencrypted; VBAN-Receptor and Voicemeeter take it.
    static void SendTestTone(int port, string streamName, int seconds)
    {
        const int samples = 256, rate = 48000;
        byte[] packet = new byte[HeaderSize + samples * 2];
        packet[0] = (byte) 'V'; packet[1] = (byte) 'B'; packet[2] = (byte) 'A'; packet[3] = (byte) 'N';
        packet[4] = 3;                  // 48000 Hz
        packet[5] = samples - 1;
        packet[6] = 0;                  // mono
        packet[7] = 1;                  // 16-bit PCM
        Array.Copy(Vban.StreamNameBytes(streamName), 0, packet, 8, 16);

        Socket socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        IPEndPoint target = new IPEndPoint(IPAddress.Loopback, port);
        Console.WriteLine("Sending a " + seconds + " second test tone, as plain VBAN, to UDP port " + port + " on this PC...");
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
        Console.WriteLine("Done. Starwire's receiver only plays it if it runs with --allow-unencrypted;");
        Console.WriteLine("otherwise it refuses it, as it should. The icon's \"Play a test tone\" checks");
        Console.WriteLine("the virtual microphone without the network.");
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
    readonly ToolStripMenuItem output = new ToolStripMenuItem();
    readonly ToolStripMenuItem paired = new ToolStripMenuItem("Paired devices");
    readonly SynchronizationContext ui = new WindowsFormsSynchronizationContext();
    readonly ReportQueue reports = new ReportQueue();
    readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
    readonly string device;
    readonly string streamName;
    readonly int port;
    readonly SecureMicEndpoint endpoint;
    readonly bool allowUnencrypted;
    PairingForm pairingForm;
    bool receiving;
    bool waiting;

    public Tray(string device, string streamName, int port, SecureMicEndpoint endpoint, bool allowUnencrypted)
    {
        this.device = device;
        this.streamName = streamName;
        this.port = port;
        this.endpoint = endpoint;
        this.allowUnencrypted = allowUnencrypted;
        status.Enabled = false;
        // Which way the audio goes out, once it has (the hover text has no room for it)
        output.Enabled = false;
        output.Visible = false;
        ContextMenuStrip menu = new ContextMenuStrip();
        menu.Items.Add(status);
        menu.Items.Add(output);
        if (allowUnencrypted) {
            ToolStripMenuItem warning = new ToolStripMenuItem("Not safe: unencrypted VBAN plays too (--allow-unencrypted)");
            warning.Enabled = false;
            menu.Items.Add(warning);
        }
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Pair a device...", null, delegate { OpenPairing(); });
        // Filled in each time it opens; it needs an item to show its arrow
        paired.DropDownItems.Add(new ToolStripMenuItem("No devices paired yet"));
        paired.DropDownOpening += delegate { FillPaired(); };
        menu.Items.Add(paired);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Check the virtual microphone", null, delegate { Check(); });
        menu.Items.Add("Play a test tone (for Discord's mic test)", null, delegate { Tone(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Setup guide", null, delegate { System.Diagnostics.Process.Start(Guide); });
        menu.Items.Add("Quit", null, delegate { icon.Visible = false; Environment.Exit(0); });
        icon.ContextMenuStrip = menu;
        icon.Icon = LoadIcon();
        ShowWaiting();
        icon.Visible = true;
        if (allowUnencrypted) {
            icon.ShowBalloonTip(8000, "Starwire microphone", "Unencrypted VBAN is allowed (--allow-unencrypted): anyone on this network "
                + "can send sound into this PC's microphone or listen to it.", ToolTipIcon.Warning);
        }

        // Back to "waiting" a few seconds after the device stops sending, and kept up to date
        // while waiting, since devices get paired and unpaired. Also shows the notifications that
        // waited their turn.
        System.Windows.Forms.Timer idle = new System.Windows.Forms.Timer();
        idle.Interval = 1000;
        idle.Tick += delegate {
            if (receiving && StarwireMicReceiver.Idle) {
                receiving = false;
                ShowWaiting();
            }
            else if (waiting) {
                ShowWaiting();
            }
            ShowNote();
        };
        idle.Start();
    }

    void ShowWaiting()
    {
        waiting = true;
        if (allowUnencrypted) {
            SetStatus("Waiting for Starwire (" + streamName + ", UDP " + port + ")");
        }
        else if (endpoint.PairedCount == 0) {
            SetStatus("No device paired yet: right-click to pair");
        }
        else {
            SetStatus("Waiting for a paired device (UDP " + port + ")");
        }
    }

    // Called from the receive thread. However many reports come, at most one call waits for the
    // UI thread, and notifications come a few seconds apart (ReportQueue).
    public void Show(string text, bool important)
    {
        if (reports.Add(text, important)) {
            ui.Post(delegate { ShowReports(); }, null);
        }
    }

    void ShowReports()
    {
        string text = reports.TakeStatus();
        if (text != null) {
            receiving = text.StartsWith("Receiving");
            waiting = false;
            SetStatus(text);
        }
        ShowNote();
    }

    void ShowNote()
    {
        string note = reports.TakeNote(clock.ElapsedMilliseconds);
        if (note != null) {
            icon.ShowBalloonTip(8000, "Starwire microphone", note, ToolTipIcon.Warning);
        }
    }

    public void OpenPairing()
    {
        if (pairingForm != null && !pairingForm.IsDisposed) {
            pairingForm.Activate();
            return;
        }
        pairingForm = new PairingForm(endpoint);
        pairingForm.Show();
        pairingForm.Activate();
    }

    void FillPaired()
    {
        paired.DropDownItems.Clear();
        List<MicPairing> devices = endpoint.Devices();
        if (devices.Count == 0) {
            ToolStripMenuItem none = new ToolStripMenuItem("No devices paired yet");
            none.Enabled = false;
            paired.DropDownItems.Add(none);
            return;
        }
        foreach (MicPairing d in devices) {
            MicPairing chosen = d;
            string when = d.LastSeen == DateTime.MinValue ? "paired " + Day(d.PairedAt) + ", not used yet" : "last used " + Day(d.LastSeen);
            ToolStripMenuItem item = new ToolStripMenuItem(MenuText(d.Name) + ", " + when);
            item.DropDownItems.Add("Unpair", null, delegate { Unpair(chosen); });
            paired.DropDownItems.Add(item);
        }
    }

    void Unpair(MicPairing d)
    {
        if (MessageBox.Show("Unpair " + d.Name + "? It can't send its microphone to this PC until you pair it again.",
                "Starwire microphone", MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) {
            return;
        }
        try {
            endpoint.Unpair(d.DeviceId);
        }
        catch (Exception e) {
            MessageBox.Show("Couldn't unpair " + d.Name + ": " + e.Message, "Starwire microphone", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    static string Day(DateTime utc)
    {
        DateTime day = utc.ToLocalTime().Date;
        if (day == DateTime.Today) {
            return "today";
        }
        return day == DateTime.Today.AddDays(-1) ? "yesterday" : day.ToShortDateString();
    }

    // A device's name in a menu: & would underline the next letter, and long ones are cut
    static string MenuText(string name)
    {
        if (name.Length > 40) {
            name = name.Substring(0, 39) + "...";
        }
        return name.Replace("&", "&&");
    }

    // Called from the receive thread
    public void ShowOutput(string text)
    {
        ui.Post(delegate {
            output.Text = "Output: " + text;
            output.Visible = true;
        }, null);
    }

    void SetStatus(string text)
    {
        if (status.Text == text) {
            return;
        }
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
        ThreadPool.QueueUserWorkItem(delegate { StarwireMicReceiver.PlayTone(device, 300, 440, null); });
    }

    // The Starwire icon the setup puts next to the receiver, else the one built into it
    public static Icon LoadIcon()
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

// "Pair a device...": shows a one-time code, once asked to, until a device pairs with it, it runs
// out, or the window is closed, which stops it working. Anyone who sees the code before the user
// has typed it can pair first, so the code only shows when asked, and the window then says where
// the pairing came from and can undo it. It keeps watching after that: if another device uses the
// code too, the receiver undoes the pairing (SecureMic.cs), and the window says so.
sealed class PairingForm : Form
{
    readonly SecureMicEndpoint endpoint;
    readonly Label intro = new Label();
    readonly Label caution = new Label();
    readonly Button showCode = new Button();
    readonly Label code = new Label();
    readonly Label time = new Label();
    readonly Label pc = new Label();
    readonly Label notMine = new Label();
    readonly Button unpair = new Button();
    readonly Button close = new Button();
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
    bool started;
    PairingState shown = PairingState.Open;
    byte[] pairedId;

    public PairingForm(SecureMicEndpoint endpoint)
    {
        this.endpoint = endpoint;
        Text = "Pair a device - Starwire microphone";
        Font = SystemFonts.MessageBoxFont;
        Icon = Tray.LoadIcon();
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        // Fits its contents, starting small, and doesn't shrink when the code gives way to a shorter
        // message
        Size = new Size(100, 100);
        AutoSize = true;
        AutoSizeMode = AutoSizeMode.GrowOnly;
        Padding = new Padding(14);

        TableLayoutPanel layout = new TableLayoutPanel();
        layout.ColumnCount = 1;
        layout.AutoSize = true;
        layout.AutoSizeMode = AutoSizeMode.GrowAndShrink;
        layout.Dock = DockStyle.Fill;

        // UseMnemonic off, or "Sound & vibration" would lose its & (and device names theirs)
        intro.AutoSize = true;
        intro.UseMnemonic = false;
        intro.MaximumSize = new Size(440, 0);
        intro.Margin = new Padding(0, 0, 0, 10);
        intro.Text = "In Starwire on your Android device: Settings > Sound & vibration > Send my microphone > "
            + "Pair with the PC, then type the code this window shows.";
        caution.AutoSize = true;
        caution.UseMnemonic = false;
        caution.MaximumSize = new Size(440, 0);
        caution.Margin = new Padding(0, 0, 0, 6);
        caution.Text = "Anyone who sees the code before you've typed it could pair instead of you, so don't show it "
            + "while your screen is shared.";
        showCode.Text = "Show the code";
        showCode.AutoSize = true;
        showCode.Anchor = AnchorStyles.None;
        showCode.Margin = new Padding(0, 6, 0, 12);
        showCode.Click += delegate { Begin(); };
        AcceptButton = showCode;
        // The code in big letters that can't be confused (no I, L, O or U), centred
        code.AutoSize = true;
        code.UseMnemonic = false;
        code.Anchor = AnchorStyles.None;
        code.Margin = new Padding(0, 6, 0, 12);
        code.Font = new Font("Consolas", 26f, FontStyle.Bold);
        code.Visible = false;
        time.AutoSize = true;
        time.UseMnemonic = false;
        time.MaximumSize = new Size(440, 0);
        pc.AutoSize = true;
        pc.UseMnemonic = false;
        pc.MaximumSize = new Size(440, 0);
        pc.ForeColor = SystemColors.GrayText;
        pc.Text = "This PC: " + Environment.MachineName + Addresses();
        notMine.AutoSize = true;
        notMine.UseMnemonic = false;
        notMine.MaximumSize = new Size(440, 0);
        notMine.Margin = new Padding(0, 12, 0, 0);
        notMine.Text = "If you haven't typed the code yet, or that isn't your device, someone else saw the code:";
        notMine.Visible = false;
        unpair.Text = "Unpair it";
        unpair.AutoSize = true;
        unpair.Anchor = AnchorStyles.Left;
        unpair.Margin = new Padding(0, 6, 0, 0);
        unpair.Visible = false;
        unpair.Click += delegate { UnpairIt(); };
        close.Text = "Cancel";
        close.AutoSize = true;
        close.Anchor = AnchorStyles.Right;
        close.Margin = new Padding(0, 14, 0, 0);
        close.Click += delegate { Close(); };
        CancelButton = close;
        layout.Controls.Add(intro);
        layout.Controls.Add(caution);
        layout.Controls.Add(showCode);
        layout.Controls.Add(code);
        layout.Controls.Add(time);
        layout.Controls.Add(pc);
        layout.Controls.Add(notMine);
        layout.Controls.Add(unpair);
        layout.Controls.Add(close);
        Controls.Add(layout);

        timer.Interval = 250;
        timer.Tick += delegate { ShowState(); };
        FormClosed += delegate {
            timer.Stop();
            if (started) {
                endpoint.CancelPairing();
            }
        };
    }

    // The code is made when it's shown, so its 5 minutes start then
    void Begin()
    {
        started = true;
        code.Text = endpoint.BeginPairing();
        code.Visible = true;
        showCode.Visible = false;
        caution.Visible = false;
        AcceptButton = null;
        ShowState();
        timer.Start();
    }

    void ShowState()
    {
        PairingStatus s = endpoint.Pairing;
        if (!started || s == null) {
            return;
        }
        if (s.State == PairingState.Open) {
            long seconds = (s.RemainingMs + 999) / 1000;
            time.Text = "It works once, for the next " + seconds / 60 + ":" + (seconds % 60).ToString("00") + ".";
            return;
        }
        if (s.State == shown) {
            return;
        }
        // The outcome in big letters where the instructions were, then what it means
        shown = s.State;
        code.Visible = false;
        pc.Visible = false;
        notMine.Visible = false;
        unpair.Visible = false;
        close.Text = "Close";
        intro.Font = new Font(Font.FontFamily, 14f, FontStyle.Bold);
        if (s.State == PairingState.Paired) {
            // Still watching, in case another device uses the code too
            pairedId = s.DeviceId;
            intro.Text = "Paired with " + s.DeviceName + ".";
            time.Text = "From " + s.Address + ". Its microphone comes to this PC encrypted from now on. To unpair it "
                + "later, right-click the Starwire microphone icon > Paired devices.";
            notMine.Visible = true;
            unpair.Visible = true;
            return;
        }
        timer.Stop();
        if (s.State == PairingState.UsedTwice) {
            intro.Text = "The code was used twice.";
            time.Text = "So someone else may have seen it. To be safe, no device is paired with it. Choose Pair a "
                + "device again for a new code.";
        }
        else if (s.State == PairingState.Expired) {
            intro.Text = "That code has expired.";
            time.Text = "Choose Pair a device again for a new code.";
        }
        else if (s.State == PairingState.TooManyTries) {
            intro.Text = "Too many wrong codes.";
            time.Text = "Pairing stopped after too many wrong codes. Choose Pair a device again for a new code.";
        }
        else {
            Close();
        }
    }

    // Someone else paired with the code: they go, like any unpaired device
    void UnpairIt()
    {
        try {
            endpoint.Unpair(pairedId);
        }
        catch (Exception e) {
            MessageBox.Show("Couldn't unpair it: " + e.Message, "Starwire microphone", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        timer.Stop();
        notMine.Visible = false;
        unpair.Visible = false;
        intro.Text = "Unpaired.";
        time.Text = "It can't send sound to this PC. Choose Pair a device again for a new code, and show it only where "
            + "nobody else can see it.";
    }

    // This PC's IPv4 addresses, to tell it apart in Starwire's list of PCs
    static string Addresses()
    {
        List<string> found = new List<string>();
        try {
            foreach (NetworkInterface n in NetworkInterface.GetAllNetworkInterfaces()) {
                if (n.OperationalStatus != OperationalStatus.Up || n.NetworkInterfaceType == NetworkInterfaceType.Loopback) {
                    continue;
                }
                foreach (UnicastIPAddressInformation a in n.GetIPProperties().UnicastAddresses) {
                    string text = a.Address.ToString();
                    if (a.Address.AddressFamily == AddressFamily.InterNetwork && !text.StartsWith("169.254.") && found.Count < 4) {
                        found.Add(text);
                    }
                }
            }
        }
        catch (NetworkInformationException) {
        }
        return found.Count == 0 ? "" : ", " + string.Join(", ", found.ToArray());
    }
}

// Plays into the output through WASAPI when it can and through waveOut when it can't. WASAPI runs
// at the shortest period the output allows (2.7 ms on VB-Cable, against waveOut's 10 ms blocks),
// which VB-Cable passes on with less delay. After falling back to waveOut it tries WASAPI again
// when the audio starts again after a pause, so a passing problem doesn't last all day.
sealed class Player
{
    const int RetryMs = 1000;    // how often to look for an output that isn't there
    const int PauseMs = 3000;    // no audio for this long: the next packet starts a new stream

    public static bool ForceWaveOut;   // --waveout

    readonly string deviceName;
    readonly Action<string> sayOutput;
    WasapiPlayer wasapi;
    WaveOutPlayer waveOut;
    bool missing;
    long nextOpenAttempt;
    long lastPlay;
    readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();

    // sayOutput (if not null) is told which way the audio goes out, each time the output opens
    public Player(string deviceName, Action<string> sayOutput)
    {
        this.deviceName = deviceName;
        this.sayOutput = sayOutput;
    }

    public void Play(byte[] packet, int offset, int samples, int channels, int rate)
    {
        long now = clock.ElapsedMilliseconds;
        bool resumed = now - lastPlay > PauseMs;
        lastPlay = now;
        if (wasapi != null && (wasapi.Failure != null || wasapi.Rate != rate)) {
            if (wasapi.Failure != null) {
                // Unplugged, the driver restarted, or its format changed in Sound settings
                StarwireMicReceiver.Report("The audio output stopped responding (" + wasapi.Failure + "); reopening it.", false);
            }
            wasapi.Close();
            wasapi = null;
        }
        if (waveOut != null && resumed && !ForceWaveOut) {
            waveOut.Close();
            waveOut = null;
        }
        if (wasapi == null && waveOut == null) {
            if (now < nextOpenAttempt) {
                // Drop the audio until the output can be opened again
                return;
            }
            if (ForceWaveOut) {
                waveOut = new WaveOutPlayer(deviceName);
                Say("waveOut (--waveout)");
            }
            else {
                string problem;
                bool notFound;
                wasapi = WasapiPlayer.Open(deviceName, rate, out problem, out notFound);
                if (wasapi == null && notFound) {
                    // Not there yet (VB-Cable just installed, or being updated): keep looking
                    nextOpenAttempt = now + RetryMs;
                    if (!missing) {
                        missing = true;
                        StarwireMicReceiver.Report("Can't find \"" + deviceName + "\" right now; trying again every second. Is VB-Cable installed?", true);
                    }
                    return;
                }
                if (missing) {
                    missing = false;
                    StarwireMicReceiver.Report("Playing into " + (wasapi != null ? wasapi.Name : deviceName) + " again.", true);
                }
                if (wasapi != null) {
                    Say(wasapi.Description);
                }
                else {
                    waveOut = new WaveOutPlayer(deviceName);
                    Say("waveOut (WASAPI didn't work: " + problem + ")");
                }
            }
        }
        if (wasapi != null) {
            wasapi.Write(packet, offset, samples, channels);
        }
        else {
            waveOut.Play(packet, offset, samples, channels, rate);
        }
    }

    void Say(string text)
    {
        if (sayOutput != null) {
            sayOutput(text);
        }
    }

    // For --stats
    public string Stats()
    {
        if (wasapi != null) {
            return wasapi.Stats();
        }
        return waveOut != null ? waveOut.Stats() : "no output open";
    }

    public void Close()
    {
        if (wasapi != null) {
            wasapi.Close();
            wasapi = null;
        }
        if (waveOut != null) {
            waveOut.Close();
            waveOut = null;
        }
    }
}

// Plays mono 16-bit PCM through WASAPI's shared mode, woken by the audio engine every period. The
// receive thread adds packets to a ring as they come, and the player's own thread hands the engine
// two periods at a time. The ring is the jitter buffer, measured in samples rather than 10 ms
// blocks: it builds up StartMs before playing (again after running dry) and never holds more than
// MaxMs.
//
// The phone's audio clock never quite matches the PC's (60 ppm apart in testing, about 3.6 ms a
// minute), so a phone running fast slowly fills the ring. The lowest the ring gets in the first
// TrimMs of playing is where it settled for this network; when it hasn't dipped within SkipMs of
// that for TrimMs, 10 ms is skipped, so the delay stays near where it started. (A fixed level
// let the delay creep up by 20 ms or more over a few minutes of real Wi-Fi timing, since jitter
// keeps the ring's lowest point well below its average.) Skipping in the ring drops 10 ms of
// audio without leaving a gap.
sealed class WasapiPlayer
{
    const int StartMs = 40;       // audio to build up before playback (re)starts
    const int MaxMs = 120;        // never more than this behind
    const int TrimMs = 2000;
    const int SkipMs = 10;
    const int TrimFloorMs = 20;   // never skip while less than this stays buffered
    const int IdleMs = 3000;      // nothing to play for this long: stop the stream until audio comes
    const int StallMs = 1000;     // no wake-up from the engine for this long: the output died
    const int OpenWaitMs = 3000;

    readonly string deviceName;
    public readonly int Rate;
    public string Name;           // the output's full name
    public string Description;    // which way the audio goes out, for --console and the icon's menu
    public volatile string Failure;

    // Ring of mono samples; the receive thread adds, the player's thread takes
    readonly object gate = new object();
    readonly short[] ring;
    int readAt, count;
    bool starting = true;
    int lowWater = int.MaxValue;  // the fewest samples left in the ring in this TrimMs window
    int settled = -1;             // the fewest in the first window after playback (re)started
    long windowStart, lastData;
    int ranDry, trims, dropped, starved;

    readonly Thread thread;
    readonly AutoResetEvent dataArrived = new AutoResetEvent(false);
    readonly ManualResetEvent opened = new ManualResetEvent(false);
    volatile bool stop;
    string openProblem;
    bool openNotFound;
    readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();

    WasapiPlayer(string deviceName, int rate)
    {
        this.deviceName = deviceName;
        Rate = rate;
        ring = new short[rate * (MaxMs + 20) / 1000];
        thread = new Thread(Run);
        thread.IsBackground = true;
    }

    // Opens the output, or returns null and why (notFound: there's no output by that name)
    public static WasapiPlayer Open(string deviceName, int rate, out string problem, out bool notFound)
    {
        WasapiPlayer player = new WasapiPlayer(deviceName, rate);
        player.thread.Start();
        if (!player.opened.WaitOne(OpenWaitMs)) {
            player.Close();
            problem = "no answer from Windows' audio service";
            notFound = false;
            return null;
        }
        problem = player.openProblem;
        notFound = player.openNotFound;
        if (problem != null) {
            player.Close();
            return null;
        }
        return player;
    }

    public void Write(byte[] packet, int offset, int samples, int channels)
    {
        lock (gate) {
            if (count + samples > Rate * MaxMs / 1000) {
                dropped++;
                return;
            }
            int at = (readAt + count) % ring.Length;
            for (int i = 0; i < samples; i++) {
                int p = offset + i * channels * 2;
                int value = (short) (packet[p] | packet[p + 1] << 8);
                if (channels == 2) {
                    value = (value + (short) (packet[p + 2] | packet[p + 3] << 8)) / 2;
                }
                ring[at] = (short) value;
                at = at + 1 == ring.Length ? 0 : at + 1;
            }
            count += samples;
            lastData = clock.ElapsedMilliseconds;
        }
        dataArrived.Set();
    }

    public string Stats()
    {
        lock (gate) {
            return "WASAPI, " + count * 1000 / Rate + " ms buffered (settled at " + Math.Max(settled, 0) * 1000 / Rate + " ms), ran dry "
                + ranDry + ", trims " + trims + ", dropped " + dropped + " packets, engine starved " + starved;
        }
    }

    public void Close()
    {
        stop = true;
        dataArrived.Set();
        thread.Join(2000);
    }

    // Up to `wanted` samples for the engine; fewer (or none) while building up the buffer
    int Take(short[] into, int wanted)
    {
        lock (gate) {
            long now = clock.ElapsedMilliseconds;
            if (starting) {
                if (count < Rate * StartMs / 1000) {
                    return 0;
                }
                starting = false;
                lowWater = int.MaxValue;
                settled = -1;
                windowStart = now;
            }
            int got = Math.Min(count, wanted);
            for (int i = 0; i < got; i++) {
                into[i] = ring[readAt];
                readAt = readAt + 1 == ring.Length ? 0 : readAt + 1;
            }
            count -= got;
            if (got < wanted) {
                // Ran dry (the phone paused, or Wi-Fi hiccuped): build up a little audio again first
                starting = true;
                ranDry++;
                return got;
            }
            lowWater = Math.Min(lowWater, count);
            if (now - windowStart >= TrimMs) {
                int skip = Rate * SkipMs / 1000;
                if (settled < 0) {
                    // No higher than the start level, so a backlog that came in one go at the
                    // start is trimmed away rather than kept
                    settled = Math.Min(lowWater, Rate * StartMs / 1000);
                }
                else if (lowWater >= Math.Max(settled + skip, Rate * TrimFloorMs / 1000)) {
                    // Clock drift has built the buffer up: skip 10 ms
                    readAt = (readAt + skip) % ring.Length;
                    count -= skip;
                    trims++;
                }
                lowWater = int.MaxValue;
                windowStart = now;
            }
            return got;
        }
    }

    // Nothing has come for IdleMs: forget what's left (too little to start playing) and say so
    bool Idle()
    {
        lock (gate) {
            if (clock.ElapsedMilliseconds - lastData <= IdleMs) {
                return false;
            }
            count = 0;
            starting = true;
            return true;
        }
    }

    // The player's own thread: every WASAPI object is made and used here only
    void Run()
    {
        Wasapi.IMMDevice device = null;
        Wasapi.IAudioClient client = null;
        Wasapi.IAudioRenderClient render = null;
        try {
            int task = 0;
            Wasapi.AvSetMmThreadCharacteristics("Pro Audio", ref task);
            device = Wasapi.FindDevice(Wasapi.Render, deviceName);
            if (device == null) {
                openNotFound = true;
                openProblem = "no output named like \"" + deviceName + "\"";
                opened.Set();
                return;
            }
            Name = Wasapi.Name(device);
            bool floatOut;
            int channels, period;
            client = Wasapi.Initialize(device, Rate, out floatOut, out channels, out period);
            Description = "WASAPI into " + Name + ", " + (period * 1000.0 / Rate).ToString("0.0") + " ms period";
            int bufferFrames;
            Wasapi.Check(client.GetBufferSize(out bufferFrames), "GetBufferSize");
            // Keep two periods with the engine: one it's playing, one ready
            int target = Math.Min(bufferFrames, 2 * period);
            AutoResetEvent ready = new AutoResetEvent(false);
            Wasapi.Check(client.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()), "SetEventHandle");
            object service;
            Guid renderId = Wasapi.IID_IAudioRenderClient;
            Wasapi.Check(client.GetService(ref renderId, out service), "GetService");
            render = (Wasapi.IAudioRenderClient) service;
            short[] mono = new short[bufferFrames];
            float[] floats = floatOut ? new float[bufferFrames * channels] : null;
            lock (gate) {
                lastData = clock.ElapsedMilliseconds;
            }
            bool running = false;
            bool wroteAudio = false;
            opened.Set();

            while (!stop) {
                if (!running) {
                    // Wait for audio, then start with a period of silence queued
                    dataArrived.WaitOne(500);
                    if (stop || Idle()) {
                        continue;
                    }
                    IntPtr silence;
                    Wasapi.Check(render.GetBuffer(period, out silence), "GetBuffer");
                    Wasapi.Check(render.ReleaseBuffer(period, Wasapi.BufferSilent), "ReleaseBuffer");
                    Wasapi.Check(client.Start(), "Start");
                    running = true;
                    wroteAudio = false;
                }
                if (!ready.WaitOne(StallMs)) {
                    Failure = "no audio from the engine for a second";
                    break;
                }
                if (Idle()) {
                    // The phone stopped sending: let the engine rest instead of waking it 375
                    // times a second for silence
                    client.Stop();
                    client.Reset();
                    running = false;
                    continue;
                }
                int padding;
                Wasapi.Check(client.GetCurrentPadding(out padding), "GetCurrentPadding");
                if (padding == 0 && wroteAudio) {
                    // The engine used up everything it had before this thread woke
                    starved++;
                }
                int wanted = target - padding;
                if (wanted <= 0) {
                    continue;
                }
                int got = Take(mono, wanted);
                wroteAudio = got > 0;
                IntPtr data;
                Wasapi.Check(render.GetBuffer(wanted, out data), "GetBuffer");
                if (got == 0) {
                    Wasapi.Check(render.ReleaseBuffer(wanted, Wasapi.BufferSilent), "ReleaseBuffer");
                    continue;
                }
                if (floatOut) {
                    // The engine's own format: every channel gets the microphone
                    int n = 0;
                    for (int i = 0; i < wanted; i++) {
                        float v = i < got ? mono[i] / 32768f : 0f;
                        for (int c = 0; c < channels; c++) {
                            floats[n++] = v;
                        }
                    }
                    Marshal.Copy(floats, 0, data, wanted * channels);
                }
                else {
                    Array.Clear(mono, got, wanted - got);
                    Marshal.Copy(mono, 0, data, wanted);
                }
                Wasapi.Check(render.ReleaseBuffer(wanted, 0), "ReleaseBuffer");
            }
            if (running) {
                client.Stop();
            }
        }
        catch (Exception e) {
            if (!opened.WaitOne(0)) {
                openProblem = e.Message;
                opened.Set();
            }
            else {
                Failure = e.Message;
            }
        }
        finally {
            if (render != null) {
                Marshal.ReleaseComObject(render);
            }
            if (client != null) {
                Marshal.ReleaseComObject(client);
            }
            if (device != null) {
                Marshal.ReleaseComObject(device);
            }
        }
    }
}

// Plays mono 16-bit PCM through Windows' waveOut API in 10 ms blocks. A short buffer rides out
// Wi-Fi jitter; when audio piles up past it (a burst, or clock drift), blocks are skipped so the
// delay stays low.
sealed class WaveOutPlayer
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
    int ranDry, trims, dropped;

    public WaveOutPlayer(string deviceName)
    {
        this.deviceName = deviceName;
    }

    public string Stats()
    {
        return "waveOut, " + queued.Count + " blocks queued, ran dry " + ranDry + ", trims " + trims + ", dropped " + dropped + " blocks";
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
            ranDry++;
            WaveOut.waveOutPause(handle);
        }
        if (queued.Count >= MaxBlocks) {
            dropped++;
            return true;
        }
        if (starting || queued.Count < TrimBlocks) {
            lastBelowTrim = now;
        }
        else if (now - lastBelowTrim > TrimMs) {
            // Clock drift has built the buffer up: skip this block
            lastBelowTrim = now;
            trims++;
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

// The parts of WASAPI (Windows' own audio API since Vista) the player uses, declared for COM
// interop since the C# compiler that comes with Windows has no audio library
static class Wasapi
{
    public const int Render = 0;
    public const int BufferSilent = 2;
    const int Shared = 0;
    const int ActiveOnly = 1;
    const int AllContexts = 23;
    const int EventCallback = 0x00040000;
    const int AutoConvertPcm = unchecked((int) 0x80000000);
    const int SrcDefaultQuality = 0x08000000;
    static readonly Guid IID_IAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    static readonly Guid IID_IAudioClient3 = new Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42");
    public static readonly Guid IID_IAudioRenderClient = new Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
    static readonly Guid FloatFormat = new Guid("00000003-0000-0010-8000-00aa00389b71");

    [DllImport("ole32.dll")] static extern void CoTaskMemFree(IntPtr p);
    [DllImport("ole32.dll")] static extern int PropVariantClear(ref PROPVARIANT p);
    [DllImport("avrt.dll", CharSet = CharSet.Unicode)] public static extern IntPtr AvSetMmThreadCharacteristics(string task, ref int index);

    // Throws with the error code, so the reason for falling back to waveOut can be looked up
    public static void Check(int hr, string what)
    {
        if (hr != 0) {
            throw new InvalidOperationException(what + " failed (0x" + hr.ToString("X8") + ")");
        }
    }

    // The first active output (or microphone) whose name starts with the text, as --device matches
    // waveOut's names
    public static IMMDevice FindDevice(int flow, string text)
    {
        IMMDeviceEnumerator enumerator = (IMMDeviceEnumerator) new MMDeviceEnumerator();
        IMMDeviceCollection devices;
        Check(enumerator.EnumAudioEndpoints(flow, ActiveOnly, out devices), "EnumAudioEndpoints");
        int count;
        Check(devices.GetCount(out count), "GetCount");
        for (int i = 0; i < count; i++) {
            IMMDevice device;
            if (devices.Item(i, out device) != 0) {
                continue;
            }
            if (Name(device).StartsWith(text, StringComparison.OrdinalIgnoreCase)) {
                return device;
            }
            Marshal.ReleaseComObject(device);
        }
        return null;
    }

    public static string Name(IMMDevice device)
    {
        IPropertyStore store;
        if (device.OpenPropertyStore(0, out store) != 0) {
            return "";
        }
        PROPERTYKEY key = new PROPERTYKEY();
        key.fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0");   // PKEY_Device_FriendlyName
        key.pid = 14;
        PROPVARIANT value;
        string name = "";
        if (store.GetValue(ref key, out value) == 0) {
            if (value.vt == 31) {   // VT_LPWSTR
                name = Marshal.PtrToStringUni(value.pointer);
            }
            PropVariantClear(ref value);
        }
        Marshal.ReleaseComObject(store);
        return name;
    }

    // A shared-mode, event-driven stream on the output. When the sample rate matches the engine's,
    // it takes the engine's own format (32-bit float) at the shortest period the output allows
    // (IAudioClient3, Windows 10 and later), else at the normal period. Otherwise it's mono 16-bit
    // at the normal period, with Windows converting.
    public static IAudioClient Initialize(IMMDevice device, int rate, out bool floatOut, out int channels, out int period)
    {
        IAudioClient client = (IAudioClient) Activate(device, IID_IAudioClient);
        IntPtr mix;
        Check(client.GetMixFormat(out mix), "GetMixFormat");
        try {
            int tag = (ushort) Marshal.ReadInt16(mix, 0);
            channels = Marshal.ReadInt16(mix, 2);
            int mixRate = Marshal.ReadInt32(mix, 4);
            int bits = Marshal.ReadInt16(mix, 14);
            bool isFloat = bits == 32 && (tag == 3 || (tag == 0xFFFE && Marshal.ReadInt16(mix, 16) >= 22 && ReadGuid(mix, 24) == FloatFormat));
            if (isFloat && mixRate == rate) {
                object instance;
                Guid iid = IID_IAudioClient3;
                IAudioClient3 low = device.Activate(ref iid, AllContexts, IntPtr.Zero, out instance) == 0 ? instance as IAudioClient3 : null;
                int defaultPeriod, fundamental, minPeriod, maxPeriod;
                if (low != null && low.GetSharedModeEnginePeriod(mix, out defaultPeriod, out fundamental, out minPeriod, out maxPeriod) == 0
                        && minPeriod < defaultPeriod) {
                    int current;
                    IntPtr currentFormat;
                    if (low.InitializeSharedAudioStream(EventCallback, minPeriod, mix, IntPtr.Zero) == 0
                            && low.GetCurrentSharedModeEnginePeriod(out currentFormat, out current) == 0) {
                        CoTaskMemFree(currentFormat);
                        Marshal.ReleaseComObject(client);
                        floatOut = true;
                        period = current;
                        return (IAudioClient) low;
                    }
                }
                if (low != null) {
                    Marshal.ReleaseComObject(low);
                }
                Check(client.Initialize(Shared, EventCallback, 0, 0, mix, IntPtr.Zero), "Initialize");
                floatOut = true;
            }
            else {
                // Windows converts the receiver's own format (another sample rate, say)
                IntPtr mono = Marshal.AllocCoTaskMem(18);
                Marshal.WriteInt16(mono, 0, 1);         // PCM
                Marshal.WriteInt16(mono, 2, 1);         // mono
                Marshal.WriteInt32(mono, 4, rate);
                Marshal.WriteInt32(mono, 8, rate * 2);
                Marshal.WriteInt16(mono, 12, 2);
                Marshal.WriteInt16(mono, 14, 16);
                Marshal.WriteInt16(mono, 16, 0);
                int hr = client.Initialize(Shared, EventCallback | AutoConvertPcm | SrcDefaultQuality, 0, 0, mono, IntPtr.Zero);
                Marshal.FreeCoTaskMem(mono);
                Check(hr, "Initialize");
                floatOut = false;
                channels = 1;
            }
            long defaultTime, minTime;
            Check(client.GetDevicePeriod(out defaultTime, out minTime), "GetDevicePeriod");
            period = (int) (defaultTime * rate / 10000000);
            return client;
        }
        catch (Exception) {
            Marshal.ReleaseComObject(client);
            throw;
        }
        finally {
            CoTaskMemFree(mix);
        }
    }

    static object Activate(IMMDevice device, Guid iid)
    {
        object instance;
        Check(device.Activate(ref iid, AllContexts, IntPtr.Zero, out instance), "Activate");
        return instance;
    }

    static Guid ReadGuid(IntPtr p, int offset)
    {
        byte[] bytes = new byte[16];
        Marshal.Copy(new IntPtr(p.ToInt64() + offset), bytes, 0, 16);
        return new Guid(bytes);
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct PROPERTYKEY { public Guid fmtid; public int pid; }

    [StructLayout(LayoutKind.Explicit, Size = 24)]
    public struct PROPVARIANT { [FieldOffset(0)] public short vt; [FieldOffset(8)] public IntPtr pointer; }

    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    class MMDeviceEnumerator { }

    // The interfaces below list their methods in the order Windows declares them, up to the last
    // one used: COM calls them by position
    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceCollection
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int context, IntPtr parameters, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore properties);
    }

    [ComImport, Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IPropertyStore
    {
        [PreserveSig] int GetCount(out int count);
        [PreserveSig] int GetAt(int index, out PROPERTYKEY key);
        [PreserveSig] int GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out int frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out int frames);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    // Windows 10 and later. It repeats IAudioClient's and IAudioClient2's methods first.
    [ComImport, Guid("7ED4EE07-8E67-4CD4-8C1A-2B7A5987AD42"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioClient3
    {
        [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out int frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out int frames);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr format);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr handle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
        [PreserveSig] int IsOffloadCapable(int category, [MarshalAs(UnmanagedType.Bool)] out bool capable);
        [PreserveSig] int SetClientProperties(IntPtr properties);
        [PreserveSig] int GetBufferSizeLimits(IntPtr format, [MarshalAs(UnmanagedType.Bool)] bool eventDriven, out long minDuration, out long maxDuration);
        [PreserveSig] int GetSharedModeEnginePeriod(IntPtr format, out int defaultPeriod, out int fundamentalPeriod, out int minPeriod, out int maxPeriod);
        [PreserveSig] int GetCurrentSharedModeEnginePeriod(out IntPtr format, out int period);
        [PreserveSig] int InitializeSharedAudioStream(int streamFlags, int periodInFrames, IntPtr format, IntPtr sessionGuid);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioRenderClient
    {
        [PreserveSig] int GetBuffer(int frames, out IntPtr data);
        [PreserveSig] int ReleaseBuffer(int frames, int flags);
    }
}
