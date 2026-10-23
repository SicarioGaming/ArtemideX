package com.limelight.binding.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The VBAN microphone sender: packets a VBAN receiver accepts (checked the way the open-source
 * vban receptor checks them), sent only to home-network addresses, and real microphone audio
 * arriving over UDP.
 */
@RunWith(AndroidJUnit4.class)
public class VbanSenderTest {
    private static final int PACKET_SIZE = VbanSender.HEADER_SIZE + VbanSender.SAMPLES_PER_PACKET * 2;

    // The checks a VBAN receiver makes before playing a packet (quiniouben/vban packet_check)
    private static void assertValidVbanAudio(byte[] packet, int length, String streamName) {
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

    private static int frameNumber(byte[] packet) {
        return (packet[24] & 0xFF) | (packet[25] & 0xFF) << 8 | (packet[26] & 0xFF) << 16 | (packet[27] & 0xFF) << 24;
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
        assertArrayEquals(padded, VbanSender.streamNameBytes(null));
        assertArrayEquals(padded, VbanSender.streamNameBytes("  Stream1 "));
    }

    @Test
    public void onlySendsToHomeNetworksAndPrivateVpns() throws Exception {
        for (String home : new String[] {"192.168.0.20", "10.0.0.5", "172.16.4.2", "127.0.0.1",
                "169.254.10.1", "100.101.102.103", "fe80::1", "fd7a:115c:a1e0::1"}) {
            assertTrue(home, VbanSender.isHomeNetworkAddress(InetAddress.getByName(home)));
        }
        for (String internet : new String[] {"8.8.8.8", "100.128.0.1", "172.32.0.1", "2001:4860:4860::8888"}) {
            assertFalse(internet, VbanSender.isHomeNetworkAddress(InetAddress.getByName(internet)));
        }
        assertFalse(VbanSender.isHomeNetworkAddress(null));
    }

    @Test
    public void audioArrivesInOrderedPacketsOf256Samples() throws Exception {
        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            receiver.setSoTimeout(2000);
            VbanSender sender = new VbanSender(InetAddress.getByName("127.0.0.1"), receiver.getLocalPort(), "Mic");
            assertEquals(0, sender.open());

            // A ramp in uneven chunks, as the microphone delivers it: 1000 samples make 3 full packets
            short[] ramp = new short[1000];
            for (int i = 0; i < ramp.length; i++) {
                ramp[i] = (short) (i * 37 - 16000);
            }
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
            receiver.setSoTimeout(300);
            try {
                receiver.receive(new DatagramPacket(buffer, buffer.length));
                throw new AssertionError("a partly filled packet was sent");
            }
            catch (SocketTimeoutException expected) {
            }
            sender.close();
            sender.close();
        }
    }

    @Test
    public void realMicrophoneStreamsOverVban() throws Exception {
        Assume.assumeTrue("needs runtime permission granting", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(target.getPackageName(), Manifest.permission.RECORD_AUDIO);

        try (DatagramSocket receiver = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            receiver.setSoTimeout(3000);
            MicrophoneCaptureManager capture = new MicrophoneCaptureManager(target);
            VbanSender sender = new VbanSender(InetAddress.getByName("127.0.0.1"), receiver.getLocalPort(), "Stream1");
            assertTrue("microphone didn't start", capture.startStreaming(0, null, sender));

            List<Integer> frames = new ArrayList<>();
            byte[] buffer = new byte[2048];
            long end = System.currentTimeMillis() + 1000;
            try {
                while (System.currentTimeMillis() < end) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    receiver.receive(packet);
                    assertValidVbanAudio(buffer, packet.getLength(), "Stream1");
                    frames.add(frameNumber(buffer));
                }
            }
            finally {
                capture.stop();
            }

            // 48000 samples a second in 256-sample packets is 187 a second; allow for start-up
            assertTrue("only " + frames.size() + " packets in a second", frames.size() >= 100);
            for (int i = 1; i < frames.size(); i++) {
                assertEquals("frame numbers count up", frames.get(i - 1) + 1, (int) frames.get(i));
            }
        }
    }
}
