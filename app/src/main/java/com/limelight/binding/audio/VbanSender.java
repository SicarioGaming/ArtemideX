package com.limelight.binding.audio;

import com.limelight.LimeLog;
import com.limelight.R;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

/**
 * Sends the microphone to a VBAN receiver on the PC (VB-Audio's VBAN-Receptor or Voicemeeter),
 * which plays it into a virtual microphone such as VB-Cable. Works whatever host the PC runs.
 *
 * VBAN is plain PCM over UDP: a 28-byte header ("VBAN", sample rate, samples per packet, channels,
 * sample format, stream name, frame counter) followed by up to 256 samples. See
 * https://vb-audio.com/Voicemeeter/VBANProtocol_Specifications.pdf. It isn't encrypted or
 * authenticated: anyone on the network can listen to it or send their own. So this is only the
 * "Not encrypted" option, for Voicemeeter and other VBAN apps, and Starwire only sends it to a PC
 * on the home network or a private VPN. Starwire's own receiver gets the same packets encrypted
 * (securemic.SecureVbanSender).
 */
public final class VbanSender implements MicrophoneSink {
    public static final int DEFAULT_PORT = 6980;
    public static final String DEFAULT_STREAM_NAME = "Stream1";

    static final int HEADER_SIZE = 28;
    static final int SAMPLES_PER_PACKET = 256;
    static final int STREAM_NAME_SIZE = 16;
    // Index of 48000 Hz in VBAN's sample rate list; the protocol bits (audio = 0) are zero
    static final int SAMPLE_RATE_48000 = 3;
    // 16-bit signed integer samples; the codec bits (PCM = 0) are zero
    static final int FORMAT_INT16 = 1;

    private final InetAddress address;
    private final int port;
    final VbanPacketizer packetizer;
    private volatile DatagramSocket socket;
    private volatile boolean closed;
    private DatagramPacket packet;
    private boolean sendFailureLogged;

    public VbanSender(InetAddress address, int port, String streamName) {
        this.address = address;
        this.port = port;
        this.packetizer = new VbanPacketizer(streamName, this::send);
    }

    /**
     * Addresses the microphone may go to, encrypted or not: private home-network ranges,
     * link-local, this device, and the ranges private VPNs like Tailscale use (100.64.0.0/10 and
     * fc00::/7). This is a filter, not a security boundary: everyone on the network has such an
     * address, a sender's address is easy to fake, and 100.64.0.0/10 is also carrier-grade NAT
     * space shared with other customers of a mobile or internet provider.
     */
    public static boolean isHomeNetworkAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
        }
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    @Override
    public int open() {
        closed = false;
        try {
            socket = new DatagramSocket();
        }
        catch (SocketException e) {
            LimeLog.warning("Couldn't open a socket for VBAN: " + e.getMessage());
            return R.string.microphone_vban_failed;
        }
        packet = new DatagramPacket(new byte[0], 0, address, port);
        packetizer.reset();
        sendFailureLogged = false;
        LimeLog.info("Sending the microphone to VBAN receiver " + address.getHostAddress() + ":" + port);
        return 0;
    }

    @Override
    public void write(short[] pcm, int samples) {
        if (socket == null || closed) {
            return;
        }
        packetizer.write(pcm, samples);
    }

    private void send(byte[] buffer, int length) {
        DatagramSocket target = socket;
        if (target == null || closed) {
            return;
        }
        packet.setData(buffer, 0, length);
        try {
            target.send(packet);
        }
        catch (IOException e) {
            // Usually the network dropped for a moment; keep trying with the next packet
            if (!sendFailureLogged) {
                sendFailureLogged = true;
                LimeLog.warning("VBAN send failed: " + e.getMessage());
            }
        }
    }

    // Can run while the capture thread is still sending (stop() gives up waiting for it after 2 s
    // on a stalled network), so the socket stays set: a send on a closed socket just fails quietly
    @Override
    public void close() {
        closed = true;
        DatagramSocket target = socket;
        if (target != null) {
            target.close();
        }
    }

    /** Writes a VBAN audio header for 48 kHz mono 16-bit PCM. */
    static void writeHeader(byte[] buffer, int samples, int frameNumber, byte[] streamName) {
        buffer[0] = 'V';
        buffer[1] = 'B';
        buffer[2] = 'A';
        buffer[3] = 'N';
        buffer[4] = SAMPLE_RATE_48000;
        buffer[5] = (byte) (samples - 1);
        buffer[6] = 0; // channels - 1
        buffer[7] = FORMAT_INT16;
        System.arraycopy(streamName, 0, buffer, 8, STREAM_NAME_SIZE);
        buffer[24] = (byte) frameNumber;
        buffer[25] = (byte) (frameNumber >> 8);
        buffer[26] = (byte) (frameNumber >> 16);
        buffer[27] = (byte) (frameNumber >> 24);
    }

    /** The stream name as VBAN sends it: ASCII, cut or padded with zeros to 16 bytes. */
    static byte[] streamNameBytes(String name) {
        byte[] out = new byte[STREAM_NAME_SIZE];
        if (name == null || name.trim().isEmpty()) {
            name = DEFAULT_STREAM_NAME;
        }
        byte[] ascii = name.trim().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, out, 0, Math.min(ascii.length, STREAM_NAME_SIZE));
        return out;
    }
}
