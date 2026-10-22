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
//   StarwireMicReceiver.exe [--device "CABLE Input"] [--name Stream1] [--port 6980]
//   StarwireMicReceiver.exe --list               the audio outputs it can play into
//   StarwireMicReceiver.exe --test-tone [secs]   sends a tone to a receiver running on this PC
//   StarwireMicReceiver.exe --check [--input "CABLE Output"]
//                                                plays a tone into the output and listens on the
//                                                matching microphone, to show the cable works
//   --loopback                                   listen on this PC only (for testing)
//
// setup-microphone.ps1 builds this with the C# compiler that comes with Windows, so it's written
// for C# 5.

using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

static class StarwireMicReceiver
{
    const int HeaderSize = 28;
    static readonly int[] VbanSampleRates = {
        6000, 12000, 24000, 48000, 96000, 192000, 384000,
        8000, 16000, 32000, 64000, 128000, 256000, 512000,
        11025, 22050, 44100, 88200, 176400, 352800, 705600
    };

    static int Main(string[] args)
    {
        string device = "CABLE Input";
        string streamName = "Stream1";
        int port = 6980;
        bool loopback = false;
        bool check = false;
        string input = null;
        int toneSeconds = 0;
        for (int i = 0; i < args.Length; i++) {
            string a = args[i].ToLowerInvariant();
            if (a == "--list") {
                ListDevices();
                return 0;
            }
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

        if (toneSeconds > 0) {
            SendTestTone(port, streamName, toneSeconds);
            return 0;
        }

        int deviceId = WaveOut.FindDevice(device);
        if (deviceId < 0) {
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
            return CheckCable(device, input) ? 0 : 3;
        }

        byte[] wantedName = StreamNameBytes(streamName);
        Console.Title = "Starwire microphone receiver";
        Console.WriteLine("Starwire microphone receiver");

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
            if (e.SocketErrorCode == SocketError.AddressAlreadyInUse) {
                Console.WriteLine("UDP port " + port + " is already in use by another program, often Voicemeeter's VBAN or");
                Console.WriteLine("VBAN-Receptor. Close it or turn its VBAN off, or run setup-microphone.ps1 -Port <another>");
                Console.WriteLine("and set the same VBAN port in Starwire.");
            }
            else {
                Console.WriteLine("Couldn't listen on UDP port " + port + ": " + e.Message);
            }
            // Keep the (usually minimized) window open so the reason can be read
            Console.WriteLine("Press Enter to close.");
            Console.ReadLine();
            return 4;
        }
        Console.WriteLine("  Listening for \"" + streamName + "\" on UDP port " + port);
        Console.WriteLine("  Playing into " + WaveOut.DeviceName(deviceId));
        Console.WriteLine("  In Starwire: Settings > Sound & vibration > Send my microphone > A VBAN receiver on the PC");
        Console.WriteLine("Close this window to stop.");

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
                    Console.WriteLine("Ignoring audio from " + source + ": not on the home network.");
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
                    Console.WriteLine("Ignoring a VBAN stream that isn't 16-bit PCM in one or two channels.");
                }
                continue;
            }

            string sender = source.ToString();
            if (sender != currentSender) {
                currentSender = sender;
                Console.WriteLine("Receiving the microphone from " + sender);
            }
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
    static bool CheckCable(string outputName, string inputName)
    {
        int inputId = WaveIn.FindDevice(inputName);
        if (inputId < 0) {
            Console.WriteLine("No microphone named like \"" + inputName + "\".");
            return false;
        }
        Console.WriteLine("Checking " + WaveOut.DeviceName(WaveOut.FindDevice(outputName)) + " -> "
                + WaveIn.DeviceName(inputId) + "...");

        const int rate = 48000;
        WaveIn recorder = new WaveIn(inputId, rate, rate * 2);   // two seconds
        Player player = new Player(outputName);
        byte[] packet = new byte[HeaderSize + 480 * 2];
        double phase = 0;
        System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
        for (int block = 0; block < 150; block++) {              // 1.5 seconds of tone
            while (clock.ElapsedMilliseconds < block * 10L) {
                System.Threading.Thread.Sleep(1);
            }
            for (int i = 0; i < 480; i++) {
                short value = (short) (Math.Sin(phase) * 8000);
                packet[HeaderSize + i * 2] = (byte) value;
                packet[HeaderSize + i * 2 + 1] = (byte) (value >> 8);
                phase += 2 * Math.PI * 1000 / rate;
            }
            player.Play(packet, HeaderSize, 480, 1, rate);
        }
        int peak = recorder.Finish();
        // The tone is 8000; anything over a tenth of that means it came through
        if (peak > 800) {
            Console.WriteLine("Works: the microphone heard the test tone.");
            return true;
        }
        Console.WriteLine("No sound came through (loudest sample " + peak + "). If VB-Cable was just installed, restart Windows and check again.");
        return false;
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

// Plays mono 16-bit PCM through Windows' waveOut API in 10 ms blocks. A short buffer rides out
// Wi-Fi jitter; when audio piles up past it, new blocks are dropped so the delay stays low.
sealed class Player
{
    const int BlockMs = 10;
    const int StartBlocks = 4;   // 40 ms of audio before playback (re)starts
    const int MaxBlocks = 12;    // never more than 120 ms behind

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
                Console.WriteLine(deviceId < 0
                        ? "Can't find \"" + deviceName + "\" right now; trying again every second."
                        : "Couldn't open " + WaveOut.DeviceName(deviceId) + " (waveOut error " + result + "); trying again every second.");
            }
            return false;
        }
        if (openFailed) {
            openFailed = false;
            Console.WriteLine("Playing into " + WaveOut.DeviceName(deviceId) + " again.");
        }
        sampleRate = rate;
        block = new short[rate * BlockMs / 1000];
        blockFill = 0;
        starting = true;
        WaveOut.waveOutPause(handle);
        lastProgress = clock.ElapsedMilliseconds;
        return true;
    }

    void Close()
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
            Console.WriteLine("The audio output stopped responding; reopening it.");
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
            Console.WriteLine("The audio output refused audio (waveOut error " + result + "); reopening it.");
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
