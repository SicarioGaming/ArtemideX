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
 * https://vb-audio.com/Voicemeeter/VBANProtocol_Specifications.pdf. It isn't encrypted, so Starwire
 * only sends it to a PC on the home network (or over a private VPN like Tailscale, which is).
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
    private final byte[] streamName;
    private final byte[] buffer = new byte[HEADER_SIZE + SAMPLES_PER_PACKET * 2];
    private DatagramSocket socket;
    private DatagramPacket packet;
    private int samplesInPacket;
    private int frameNumber;
    private boolean sendFailureLogged;

    public VbanSender(InetAddress address, int port, String streamName) {
        this.address = address;
        this.port = port;
        this.streamName = streamNameBytes(streamName);
    }

    /**
     * Addresses the unencrypted audio may go to: private home-network ranges, link-local, this
     * device, and the ranges private VPNs like Tailscale use (100.64.0.0/10 and fc00::/7), whose
     * traffic is already encrypted.
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
        try {
            socket = new DatagramSocket();
        }
        catch (SocketException e) {
            LimeLog.warning("Couldn't open a socket for VBAN: " + e.getMessage());
            return R.string.microphone_vban_failed;
        }
        packet = new DatagramPacket(buffer, buffer.length, address, port);
        samplesInPacket = 0;
        frameNumber = 0;
        sendFailureLogged = false;
        LimeLog.info("Sending the microphone to VBAN receiver " + address.getHostAddress() + ":" + port);
        return 0;
    }

    @Override
    public void write(short[] pcm, int samples) {
        if (socket == null) {
            return;
        }
        // Fill 256-sample packets, little-endian, and send each one as it fills
        for (int i = 0; i < samples; i++) {
            int at = HEADER_SIZE + samplesInPacket * 2;
            buffer[at] = (byte) pcm[i];
            buffer[at + 1] = (byte) (pcm[i] >> 8);
            if (++samplesInPacket == SAMPLES_PER_PACKET) {
                send();
                samplesInPacket = 0;
            }
        }
    }

    private void send() {
        writeHeader(buffer, SAMPLES_PER_PACKET, frameNumber++, streamName);
        try {
            socket.send(packet);
        }
        catch (IOException e) {
            // Usually the network dropped for a moment; keep trying with the next packet
            if (!sendFailureLogged) {
                sendFailureLogged = true;
                LimeLog.warning("VBAN send failed: " + e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            socket.close();
            socket = null;
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
