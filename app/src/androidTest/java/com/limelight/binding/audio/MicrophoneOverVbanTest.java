package com.limelight.binding.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.testing.DeviceCaps;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Real microphone audio arriving over UDP as VBAN packets a receiver accepts. Needs the device's
 * microphone; the packet format, address policy and close handling are JVM tests (VbanSenderTest).
 */
@RunWith(AndroidJUnit4.class)
public class MicrophoneOverVbanTest {
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
    public void realMicrophoneStreamsOverVban() throws Exception {
        Assume.assumeTrue("needs runtime permission granting", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P);
        DeviceCaps.assumeHardware("a microphone", DeviceCaps.hasMicrophone());
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
