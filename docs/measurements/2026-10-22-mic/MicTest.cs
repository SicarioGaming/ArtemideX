// Measurement helper for the 0.2.0 microphone test. Not part of Starwire.
//   MicTest level <seconds> <out.csv> [device]       CABLE Output's level every 10 ms (no audio is kept)
//   MicTest proxy <seconds> <listen> <forward> <dir> logs VBAN arrivals and forwards them to 127.0.0.1:<forward>
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

static class MicTest
{
    static int Main(string[] args)
    {
        System.Threading.Thread.CurrentThread.CurrentCulture = CultureInfo.InvariantCulture;
        if (args.Length >= 3 && args[0] == "level") {
            return Level(int.Parse(args[1]), args[2], args.Length > 3 ? args[3] : "CABLE Output");
        }
        if (args.Length >= 5 && args[0] == "proxy") {
            return Proxy(int.Parse(args[1]), int.Parse(args[2]), int.Parse(args[3]), args[4]);
        }
        if (args.Length >= 5 && args[0] == "latency") {
            return Latency(int.Parse(args[1]), int.Parse(args[2]), int.Parse(args[3]), args[4]);
        }
        Console.WriteLine("usage: MicTest level <seconds> <out.csv> [device] | proxy <seconds> <listen> <forward> <dir>");
        return 2;
    }

    // --- level ---

    [StructLayout(LayoutKind.Sequential)]
    struct WAVEFORMATEX { public short wFormatTag, nChannels; public int nSamplesPerSec, nAvgBytesPerSec; public short nBlockAlign, wBitsPerSample, cbSize; }
    [StructLayout(LayoutKind.Sequential)]
    struct WAVEHDR { public IntPtr lpData; public int dwBufferLength, dwBytesRecorded; public IntPtr dwUser; public int dwFlags, dwLoops; public IntPtr lpNext, reserved; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct WAVEINCAPS { public short wMid, wPid; public int vDriverVersion; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szPname; public int dwFormats; public short wChannels, wReserved1; }

    [DllImport("winmm.dll")] static extern int waveInGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode, EntryPoint = "waveInGetDevCapsW")] static extern int waveInGetDevCaps(IntPtr id, out WAVEINCAPS caps, int size);
    [DllImport("winmm.dll")] static extern int waveInOpen(out IntPtr handle, int deviceId, ref WAVEFORMATEX format, IntPtr callback, IntPtr instance, int flags);
    [DllImport("winmm.dll")] static extern int waveInPrepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInUnprepareHeader(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInAddBuffer(IntPtr handle, IntPtr header, int size);
    [DllImport("winmm.dll")] static extern int waveInStart(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInReset(IntPtr handle);
    [DllImport("winmm.dll")] static extern int waveInClose(IntPtr handle);

    static int Level(int seconds, string outCsv, string deviceName)
    {
        int device = -1;
        for (int i = 0; i < waveInGetNumDevs(); i++) {
            WAVEINCAPS caps;
            waveInGetDevCaps(new IntPtr(i), out caps, Marshal.SizeOf(typeof(WAVEINCAPS)));
            if (caps.szPname.StartsWith(deviceName, StringComparison.OrdinalIgnoreCase)) { device = i; break; }
        }
        if (device < 0) { Console.WriteLine("no input named like " + deviceName); return 1; }

        const int rate = 48000;
        WAVEFORMATEX format = new WAVEFORMATEX();
        format.wFormatTag = 1; format.nChannels = 1; format.nSamplesPerSec = rate;
        format.wBitsPerSample = 16; format.nBlockAlign = 2; format.nAvgBytesPerSec = rate * 2;
        IntPtr handle;
        int result = waveInOpen(out handle, device, ref format, IntPtr.Zero, IntPtr.Zero, 0);
        if (result != 0) { Console.WriteLine("waveInOpen error " + result); return 1; }
        int bytes = seconds * rate * 2;
        IntPtr data = Marshal.AllocHGlobal(bytes);
        WAVEHDR header = new WAVEHDR();
        header.lpData = data; header.dwBufferLength = bytes;
        int headerSize = Marshal.SizeOf(typeof(WAVEHDR));
        IntPtr headerPtr = Marshal.AllocHGlobal(headerSize);
        Marshal.StructureToPtr(header, headerPtr, false);
        waveInPrepareHeader(handle, headerPtr, headerSize);
        waveInAddBuffer(handle, headerPtr, headerSize);
        waveInStart(handle);
        System.Threading.Thread.Sleep(seconds * 1000 + 300);
        waveInReset(handle);
        header = (WAVEHDR) Marshal.PtrToStructure(headerPtr, typeof(WAVEHDR));
        short[] samples = new short[header.dwBytesRecorded / 2];
        Marshal.Copy(data, samples, 0, samples.Length);
        waveInUnprepareHeader(handle, headerPtr, headerSize);
        waveInClose(handle);
        Marshal.FreeHGlobal(headerPtr);
        Marshal.FreeHGlobal(data);

        // Only levels leave this function; the samples are dropped
        StringBuilder csv = new StringBuilder("t_ms,rms,peak\n");
        int window = rate / 100, loud = 0, windows = 0, peakAll = 0, exactZero = 0;
        for (int start = 0; start + window <= samples.Length; start += window) {
            double sum = 0; int peak = 0; bool zero = true;
            for (int i = start; i < start + window; i++) {
                int s = samples[i];
                sum += (double) s * s;
                peak = Math.Max(peak, Math.Abs(s));
                if (s != 0) zero = false;
            }
            double rms = Math.Sqrt(sum / window);
            csv.AppendFormat("{0},{1:F1},{2}\n", windows * 10, rms, peak);
            if (rms > 32.8) loud++;   // above -60 dBFS
            if (zero) exactZero++;
            peakAll = Math.Max(peakAll, peak);
            windows++;
        }
        File.WriteAllText(outCsv, csv.ToString());
        Array.Clear(samples, 0, samples.Length);
        Console.WriteLine("level: {0} windows of 10 ms, peak {1}, {2} above -60 dBFS, {3} exactly silent", windows, peakAll, loud, exactZero);
        return 0;
    }

    // --- latency: send a click every 94 packets (~0.5 s) to a receiver on this PC, faster than real
    // time by <ppm>, record CABLE Output, and time each click from send to capture ---

    static int Latency(int seconds, int port, int ppm, string outCsv)
    {
        int device = -1;
        for (int i = 0; i < waveInGetNumDevs(); i++) {
            WAVEINCAPS caps;
            waveInGetDevCaps(new IntPtr(i), out caps, Marshal.SizeOf(typeof(WAVEINCAPS)));
            if (caps.szPname.StartsWith("CABLE Output", StringComparison.OrdinalIgnoreCase)) { device = i; break; }
        }
        if (device < 0) { Console.WriteLine("no CABLE Output"); return 1; }
        const int rate = 48000;
        WAVEFORMATEX format = new WAVEFORMATEX();
        format.wFormatTag = 1; format.nChannels = 1; format.nSamplesPerSec = rate;
        format.wBitsPerSample = 16; format.nBlockAlign = 2; format.nAvgBytesPerSec = rate * 2;
        IntPtr handle;
        if (waveInOpen(out handle, device, ref format, IntPtr.Zero, IntPtr.Zero, 0) != 0) { Console.WriteLine("waveInOpen failed"); return 1; }
        int bytes = (seconds + 2) * rate * 2;
        IntPtr data = Marshal.AllocHGlobal(bytes);
        WAVEHDR header = new WAVEHDR();
        header.lpData = data; header.dwBufferLength = bytes;
        int headerSize = Marshal.SizeOf(typeof(WAVEHDR));
        IntPtr headerPtr = Marshal.AllocHGlobal(headerSize);
        Marshal.StructureToPtr(header, headerPtr, false);
        waveInPrepareHeader(handle, headerPtr, headerSize);
        waveInAddBuffer(handle, headerPtr, headerSize);

        Socket socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        EndPoint target = new IPEndPoint(IPAddress.Loopback, port);
        byte[] packet = new byte[28 + 512];
        Encoding.ASCII.GetBytes("VBAN").CopyTo(packet, 0);
        packet[4] = 3; packet[5] = 255; packet[6] = 0; packet[7] = 1;
        Encoding.ASCII.GetBytes("Stream1").CopyTo(packet, 8);
        double interval = 256.0 / rate / (1 + ppm / 1e6) * 1000;   // ms per packet
        List<double> clickSent = new List<double>();

        Stopwatch clock = Stopwatch.StartNew();
        waveInStart(handle);
        double t0 = clock.Elapsed.TotalMilliseconds;
        double start = t0 + 300;
        for (int k = 0; ; k++) {
            double due = start + k * interval;
            if (due - t0 > seconds * 1000) break;
            while (clock.Elapsed.TotalMilliseconds < due) {
                if (due - clock.Elapsed.TotalMilliseconds > 2) System.Threading.Thread.Sleep(1);
            }
            bool click = k % 94 < 2;   // 512 samples, ~10.7 ms of 1 kHz
            for (int i = 0; i < 256; i++) {
                short s = click ? (short) (20000 * Math.Sin(2 * Math.PI * 1000 * (k % 94 * 256 + i) / rate)) : (short) 0;
                packet[28 + i * 2] = (byte) s; packet[29 + i * 2] = (byte) (s >> 8);
            }
            BitConverter.GetBytes((uint) k).CopyTo(packet, 24);
            double sent = clock.Elapsed.TotalMilliseconds;
            socket.SendTo(packet, target);
            if (k % 94 == 0) clickSent.Add(sent);
        }
        System.Threading.Thread.Sleep(600);
        waveInReset(handle);
        header = (WAVEHDR) Marshal.PtrToStructure(headerPtr, typeof(WAVEHDR));
        short[] rec = new short[header.dwBytesRecorded / 2];
        Marshal.Copy(data, rec, 0, rec.Length);
        waveInUnprepareHeader(handle, headerPtr, headerSize);
        waveInClose(handle);

        StringBuilder csv = new StringBuilder("click,sent_ms,delay_ms\n");
        List<double> delays = new List<double>();
        for (int c = 0; c < clickSent.Count; c++) {
            int from = Math.Max(0, (int) ((clickSent[c] - t0) * rate / 1000));
            int to = Math.Min(rec.Length, from + rate * 2 / 5);
            for (int i = from; i < to; i++) {
                if (Math.Abs((int) rec[i]) > 8000) {
                    double delay = t0 + i * 1000.0 / rate - clickSent[c];
                    delays.Add(delay);
                    csv.AppendFormat("{0},{1:F1},{2:F2}\n", c, clickSent[c] - start, delay);
                    break;
                }
            }
        }
        File.WriteAllText(outCsv, csv.ToString());
        if (delays.Count == 0) { Console.WriteLine("latency: no clicks heard"); return 1; }
        List<double> sorted = new List<double>(delays); sorted.Sort();
        Console.WriteLine("latency: {0}/{1} clicks heard, delay min {2:F1} median {3:F1} max {4:F1} ms; first five {5}; last five {6}",
            delays.Count, clickSent.Count, sorted[0], sorted[sorted.Count / 2], sorted[sorted.Count - 1],
            string.Join(" ", delays.GetRange(0, Math.Min(5, delays.Count)).ConvertAll(d => d.ToString("F0")).ToArray()),
            string.Join(" ", delays.GetRange(Math.Max(0, delays.Count - 5), Math.Min(5, delays.Count)).ConvertAll(d => d.ToString("F0")).ToArray()));
        return 0;
    }

    // --- proxy ---

    static int Proxy(int seconds, int listenPort, int forwardPort, string dir)
    {
        Socket socket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        socket.Bind(new IPEndPoint(IPAddress.Any, listenPort));
        socket.ReceiveTimeout = 500;
        Socket forward = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        EndPoint target = new IPEndPoint(IPAddress.Loopback, forwardPort);

        StringBuilder csv = new StringBuilder("t_ms,frame,length,peak\n");
        Queue<byte[]> recent = new Queue<byte[]>();
        Queue<int> recentPeaks = new Queue<int>();
        long recentSum = 0, bestSum = -1;
        byte[][] best = null;
        HashSet<string> sources = new HashSet<string>();
        byte[] buffer = new byte[2048];
        Stopwatch clock = Stopwatch.StartNew();
        int packets = 0;
        long first = -1, last = -1, outOfOrder = 0;
        HashSet<uint> seen = new HashSet<uint>();
        while (clock.Elapsed.TotalSeconds < seconds) {
            EndPoint from = new IPEndPoint(IPAddress.Any, 0);
            int length;
            try { length = socket.ReceiveFrom(buffer, ref from); }
            catch (SocketException) { continue; }
            double t = clock.Elapsed.TotalMilliseconds;
            forward.SendTo(buffer, length, SocketFlags.None, target);
            if (length < 28 || buffer[0] != 'V' || buffer[1] != 'B' || buffer[2] != 'A' || buffer[3] != 'N') continue;
            sources.Add(((IPEndPoint) from).Address.ToString());
            uint frame = BitConverter.ToUInt32(buffer, 24);
            int peak = 0;
            for (int i = 28; i + 1 < length; i += 2) peak = Math.Max(peak, Math.Abs((int) BitConverter.ToInt16(buffer, i)));
            csv.AppendFormat("{0:F3},{1},{2},{3}\n", t, frame, length, peak);
            if (first < 0) first = frame;
            if (last >= 0 && frame < last) outOfOrder++;
            last = Math.Max(last, frame);
            seen.Add(frame);
            packets++;

            // Keep the loudest run of 40 packets (0.2 s), for the hexdump
            byte[] copy = new byte[length];
            Buffer.BlockCopy(buffer, 0, copy, 0, length);
            recent.Enqueue(copy); recentPeaks.Enqueue(peak); recentSum += peak;
            if (recent.Count > 40) { recent.Dequeue(); recentSum -= recentPeaks.Dequeue(); }
            if (recent.Count == 40 && recentSum > bestSum) { bestSum = recentSum; best = recent.ToArray(); }
        }
        File.WriteAllText(Path.Combine(dir, "arrivals.csv"), csv.ToString());
        if (best != null) {
            using (FileStream f = File.Create(Path.Combine(dir, "packets.bin"))) {
                foreach (byte[] p in best) f.Write(p, 0, p.Length);
            }
        }
        long expected = last >= 0 ? last - first + 1 : 0;
        Console.WriteLine("proxy: {0} packets from {1} source(s), frames {2}-{3}, {4} missing, {5} out of order",
            packets, sources.Count, first, last, expected - seen.Count, outOfOrder);
        return 0;
    }
}
