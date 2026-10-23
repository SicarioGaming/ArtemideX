package com.limelight.nvstream.wol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * The Wake-on-LAN magic packet: 6 bytes of 0xFF, then the PC's MAC address 16 times. The MAC
 * comes from the host's serverinfo, so whatever text it is, building the packet must not throw.
 * Nothing is sent here.
 */
public class WakeOnLanPayloadTest {
    private static byte[] payloadFor(String mac) throws Exception {
        ComputerDetails pc = new ComputerDetails();
        pc.macAddress = mac;
        Method m = WakeOnLanSender.class.getDeclaredMethod("createWolPayload", ComputerDetails.class);
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(null, pc);
        } catch (InvocationTargetException e) {
            throw new AssertionError("threw for MAC " + mac, e.getCause());
        }
    }

    private static void assertMagicPacket(byte[] payload, byte[] mac) {
        assertEquals(102, payload.length);
        for (int i = 0; i < 6; i++) {
            assertEquals((byte) 0xFF, payload[i]);
        }
        for (int r = 0; r < 16; r++) {
            assertArrayEquals("repeat " + r, mac, Arrays.copyOfRange(payload, 6 + r * 6, 12 + r * 6));
        }
    }

    @Test
    public void magicPacketRepeatsTheMac() throws Exception {
        assertMagicPacket(payloadFor("AA:BB:CC:DD:EE:0F"),
                new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, 0x0F});
        assertMagicPacket(payloadFor("00:11:22:33:44:55"), new byte[] {0x00, 0x11, 0x22, 0x33, 0x44, 0x55});
        assertMagicPacket(payloadFor("aa:bb:cc:dd:ee:ff"),
                new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF});
    }

    // A malformed part stops the MAC there; the rest stays zero
    @Test
    public void malformedMacsGiveAPacketWithoutThrowing() throws Exception {
        assertMagicPacket(payloadFor("AA:BB:CC:DD:EE:GG"),
                new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, 0});
        assertMagicPacket(payloadFor("AA:BB"), new byte[] {(byte) 0xAA, (byte) 0xBB, 0, 0, 0, 0});
        assertMagicPacket(payloadFor("AA:BB:CC:DD:EE:FF:11:22"),
                new byte[] {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF});
        for (String mac : new String[] {"", ":::::", "AABBCCDDEEFF", "AA-BB-CC-DD-EE-FF", "not a mac", "99999999999:1"}) {
            assertEquals(mac, 102, payloadFor(mac).length);
        }
    }
}
