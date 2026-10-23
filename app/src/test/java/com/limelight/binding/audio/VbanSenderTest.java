package com.limelight.binding.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The VBAN microphone sender: packets a VBAN receiver accepts (checked the way the open-source
 * vban receptor checks them), sent only to home-network addresses. Real microphone audio over
 * VBAN is checked on the device (MicrophoneOverVbanTest). Packets only go to a socket on this
 * computer's loopback address.
 */
public class VbanSenderTest {
    private static final int PACKET_SIZE = VbanSender.HEADER_SIZE + VbanSender.SAMPLES_PER_PACKET * 2;

    // The checks a VBAN receiver makes before playing a packet (quiniouben/vban packet_check)
    static void assertValidVbanAudio(byte[] packet, int length, String streamName) {
        assertTrue("packet too small", length > VbanSender.HEADER_SIZE);
        assertEquals("VBAN", new String(packet, 0, 4, StandardCharsets.US_ASCII));
        byte[] expectedName = VbanSender.streamNameBytes(streamName);
        for (int i = 0; i < VbanSender.STREAM_NAME_SIZE; i++) {
            assertEquals("stream name byte " + i, expectedName[i], packet[8 + i]);
        }
        assertEquals("reserved bit", 0, packet[7] & 0x08);
        assertEquals("audio protocol", 0, packet[4] & 0xE0);
        assertEquals("PCM codec", 0, packet[7] & 0xF0);
        assertEquals("48000 Hz", 3, packet[4] & 0x1F);
        assertEquals("16-bit samples", 1, packet[7] & 0x07);
        int samples = (packet[5] & 0xFF) + 1;
        int channels = (packet[6] & 0xFF) + 1;
        assertEquals("mono", 1, channels);
        assertEquals("payload size", samples * channels * 2, length - VbanSender.HEADER_SIZE);
    }

    static int frameNumber(byte[] packet) {
        return (packet[24] & 0xFF) | (packet[25] & 0xFF) << 8 | (packet[26] & 0xFF) << 16 | (packet[27] & 0xFF) << 24;
    }

    private static DatagramSocket receiver() throws Exception {
        DatagramSocket receiver = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        receiver.setSoTimeout(2000);
        return receiver;
    }

    private static VbanSender senderTo(DatagramSocket receiver, String name) {
        return new VbanSender(InetAddress.getLoopbackAddress(), receiver.getLocalPort(), name);
    }

    private static short[] ramp(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 37 - 16000);
        }
        return pcm;
    }

    // Nothing arrives within 300 ms
    private static void assertNothingMore(DatagramSocket receiver) throws Exception {
        receiver.setSoTimeout(300);
        byte[] buffer = new byte[2048];
        try {
            receiver.receive(new DatagramPacket(buffer, buffer.length));
            throw new AssertionError("a packet was sent: frame " + frameNumber(buffer));
        }
        catch (SocketTimeoutException expected) {
        }
    }

    @Test
    public void headerIsWhatReceiversExpect() {
        byte[] packet = new byte[PACKET_SIZE];
        VbanSender.writeHeader(packet, 256, 0x01020304, VbanSender.streamNameBytes("Stream1"));
        assertValidVbanAudio(packet, packet.length, "Stream1");
        assertEquals(255, packet[5] & 0xFF);
        assertEquals(0x01020304, frameNumber(packet));
        assertEquals(VbanSender.HEADER_SIZE, 4 + 4 + 16 + 4);
    }

    @Test
    public void headerBytesMatchTheVbanSpec() {
        byte[] buf = new byte[VbanSender.HEADER_SIZE];
        VbanSender.writeHeader(buf, 256, 0x01020304, VbanSender.streamNameBytes("Mic"));
        assertEquals("VBAN", new String(buf, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(3, buf[4]);              // 48 kHz index, audio protocol
        assertEquals((byte) 255, buf[5]);     // samples - 1
        assertEquals(0, buf[6]);              // mono
        assertEquals(1, buf[7]);              // INT16 PCM
        assertEquals('M', buf[8]);
        assertEquals(0, buf[11]);
        assertArrayEquals(new byte[] {4, 3, 2, 1}, new byte[] {buf[24], buf[25], buf[26], buf[27]});
    }

    @Test
    public void frameCounterIsLittleEndianAcrossItsWholeRange() {
        byte[] buf = new byte[VbanSender.HEADER_SIZE];
        byte[] name = VbanSender.streamNameBytes("Mic");
        VbanSender.writeHeader(buf, 256, 0xFFFFFFFF, name);
        assertArrayEquals(new byte[] {-1, -1, -1, -1}, new byte[] {buf[24], buf[25], buf[26], buf[27]});
        VbanSender.writeHeader(buf, 256, 0x80000000, name);
        assertArrayEquals(new byte[] {0, 0, 0, (byte) 0x80}, new byte[] {buf[24], buf[25], buf[26], buf[27]});
        VbanSender.writeHeader(buf, 1, 0, name);
        assertEquals(0, frameNumber(buf));
        assertEquals(0, buf[5]);
    }

    // After 2^32 packets (about 266 days of audio) the counter wraps to 0, as receivers expect
    @Test
    public void frameCounterWrapsAfter0xFFFFFFFF() throws Exception {
        try (DatagramSocket receiver = receiver()) {
            VbanSender sender = senderTo(receiver, "Mic");
            assertEquals(0, sender.open());
            Field counter = VbanSender.class.getDeclaredField("frameNumber");
            counter.setAccessible(true);
            counter.setInt(sender, 0xFFFFFFFF);

            sender.write(ramp(512), 512);
            byte[] buffer = new byte[2048];
            int[] expected = {0xFFFFFFFF, 0};
            for (int frame : expected) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.receive(packet);
                assertValidVbanAudio(buffer, packet.getLength(), "Mic");
                assertEquals(frame, frameNumber(buffer));
            }
            sender.close();
        }
    }

    @Test
    public void streamNamesArePaddedOrCutToSixteenBytes() {
        byte[] padded = VbanSender.streamNameBytes("Stream1");
        assertEquals(16, padded.length);
        assertEquals("Stream1", new String(padded, 0, 7, StandardCharsets.US_ASCII));
        for (int i = 7; i < 16; i++) {
            assertEquals(0, padded[i]);
        }
        assertEquals("ABCDEFGHIJKLMNOP", new String(VbanSender.streamNameBytes("ABCDEFGHIJKLMNOPQRST"), StandardCharsets.US_ASCII));
        // Empty falls back to VBAN-Receptor's default, and spaces around a name are dropped
        assertArrayEquals(padded, VbanSender.streamNameBytes(""));
        assertArrayEquals(padded, VbanSender.streamNameBytes("   "));
        assertArrayEquals(padded, VbanSender.streamNameBytes(null));
        assertArrayEquals(padded, VbanSender.streamNameBytes("  Stream1 "));
        // Letters outside ASCII become '?', one byte each, and the name is still 16 bytes
        byte[] accented = VbanSender.streamNameBytes("Ünïcødé-Ünïcødé-Ünïc");
        assertEquals(16, accented.length);
        assertEquals("?n?c?d?-?n?c?d?-", new String(accented, StandardCharsets.US_ASCII));
    }

    @Test
    public void onlySendsToHomeNetworksAndPrivateVpns() throws Exception {
        for (String home : new String[] {"192.168.0.20", "10.0.0.5", "172.16.4.2", "172.31.255.254", "127.0.0.1",
                "169.254.10.1", "100.101.102.103", "fe80::1", "fd7a:115c:a1e0::1", "fc00::1", "fec0::1", "::1"}) {
            assertTrue(home, VbanSender.isHomeNetworkAddress(InetAddress.getByName(home)));
        }
        for (String internet : new String[] {"8.8.8.8", "100.128.0.1", "100.63.255.255", "172.32.0.1", "172.15.0.1",
                "11.0.0.1", "192.169.0.1", "2001:4860:4860::8888", "0.0.0.0", "255.255.255.255", "224.0.0.1",
                "ff02::1", "::"}) {
            assertFalse(internet, VbanSender.isHomeNetworkAddress(InetAddress.getByName(internet)));
        }
        assertFalse(VbanSender.isHomeNetworkAddress(null));
    }

    // Pins today's policy so a change to it is deliberate: 100.64.0.0/10 is accepted for
    // Tailscale, but the same range is also carrier-grade NAT space shared with other customers
    // of a mobile or internet provider. Flip this when the mic security work changes the policy.
    @Test
    public void carrierGradeNatRangeIsAcceptedToday() throws Exception {
        assertTrue(VbanSender.isHomeNetworkAddress(InetAddress.getByName("100.64.0.1")));
        assertTrue(VbanSender.isHomeNetworkAddress(InetAddress.getByName("100.127.255.254")));
    }

    @Test
    public void audioArrivesInOrderedPacketsOf256Samples() throws Exception {
        try (DatagramSocket receiver = receiver()) {
            VbanSender sender = senderTo(receiver, "Mic");
            assertEquals(0, sender.open());

            // A ramp in uneven chunks, as the microphone delivers it: 1000 samples make 3 full packets
            short[] ramp = ramp(1000);
            short[] chunk = new short[300];
            for (int done = 0; done < ramp.length; done += chunk.length) {
                int n = Math.min(chunk.length, ramp.length - done);
                System.arraycopy(ramp, done, chunk, 0, n);
                sender.write(chunk, n);
            }

            byte[] buffer = new byte[2048];
            for (int p = 0; p < 3; p++) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.receive(packet);
                assertValidVbanAudio(buffer, packet.getLength(), "Mic");
                assertEquals(PACKET_SIZE, packet.getLength());
                assertEquals(p, frameNumber(buffer));
                for (int s = 0; s < 256; s++) {
                    int at = VbanSender.HEADER_SIZE + s * 2;
                    short sample = (short) ((buffer[at] & 0xFF) | (buffer[at + 1] << 8));
                    assertEquals("sample " + (p * 256 + s), ramp[p * 256 + s], sample);
                }
            }

            // The last 232 samples wait for the next packet
            assertNothingMore(receiver);
            sender.close();
            sender.close();
        }
    }

    @Test
    public void nothingIsSentBeforeOpenOrAfterClose() throws Exception {
        try (DatagramSocket receiver = receiver()) {
            VbanSender sender = senderTo(receiver, "Mic");
            sender.write(ramp(512), 512);
            assertNothingMore(receiver);

            assertEquals(0, sender.open());
            sender.close();
            sender.write(ramp(512), 512);
            assertNothingMore(receiver);
        }
    }

    // Opening again after a close (the next stream) starts a fresh count
    @Test
    public void reopeningStartsAgainFromFrameZero() throws Exception {
        try (DatagramSocket receiver = receiver()) {
            VbanSender sender = senderTo(receiver, "Mic");
            byte[] buffer = new byte[2048];
            for (int round = 0; round < 2; round++) {
                assertEquals(0, sender.open());
                sender.write(ramp(300), 300);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                receiver.receive(packet);
                assertEquals(0, frameNumber(buffer));
                sender.close();
            }
            assertNothingMore(receiver);
        }
    }

    // Regression for a4580fa6: stop() can close the sender while the capture thread is still in
    // write(), on a network that stalls. close() used to clear the socket that write() was about
    // to send on, which crashed the app with a NullPointerException. Here the capture thread
    // writes long buffers (64 packets per call) and the sender is closed as soon as audio arrives,
    // so the close lands inside a write; whatever the timing, nothing may throw.
    @Test
    public void closingDuringAWriteNeverThrows() throws Exception {
        try (DatagramSocket receiver = receiver()) {
            for (int round = 0; round < 20; round++) {
                VbanSender sender = senderTo(receiver, "Mic");
                assertEquals(0, sender.open());
                short[] pcm = ramp(256 * 64);
                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread capture = new Thread(() -> {
                    try {
                        for (int i = 0; i < 50; i++) {
                            sender.write(pcm, pcm.length);
                        }
                    }
                    catch (Throwable t) {
                        failure.set(t);
                    }
                }, "capture");
                capture.start();

                receiver.receive(new DatagramPacket(new byte[2048], 2048));
                sender.close();
                capture.join(10_000);
                assertFalse("capture thread still running", capture.isAlive());
                assertNull("write threw after close in round " + round, failure.get());

                // Drain what was sent before the close
                receiver.setSoTimeout(50);
                try {
                    while (true) {
                        receiver.receive(new DatagramPacket(new byte[2048], 2048));
                    }
                }
                catch (SocketTimeoutException drained) {
                }
                receiver.setSoTimeout(2000);
            }
        }
    }
}
