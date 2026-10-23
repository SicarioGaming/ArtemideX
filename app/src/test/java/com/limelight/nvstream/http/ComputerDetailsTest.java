package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Merging a fresh poll of a PC into what the app already knows about it.
 */
public class ComputerDetailsTest {
    private static ComputerDetails.AddressTuple at(String address, int port) {
        return new ComputerDetails.AddressTuple(address, port);
    }

    private static ComputerDetails known() {
        ComputerDetails pc = new ComputerDetails();
        pc.uuid = "uuid-a";
        pc.name = "GAMING-PC";
        pc.localAddress = at("192.168.1.20", 47989);
        pc.remoteAddress = at("203.0.113.9", 47989);
        pc.manualAddress = at("pc.lan", 47989);
        pc.ipv6Address = at("fd00::20", 47989);
        pc.activeAddress = pc.localAddress;
        pc.macAddress = "00:11:22:33:44:55";
        pc.httpsPort = 47984;
        return pc;
    }

    @Test
    public void aPollKeepsAddressesItDidntSee() {
        ComputerDetails pc = known();
        ComputerDetails poll = new ComputerDetails();
        poll.uuid = "uuid-a";
        poll.name = "GAMING-PC renamed";
        poll.state = ComputerDetails.State.ONLINE;
        poll.httpsPort = 47990;
        poll.runningGameId = 7;
        poll.rawAppList = "<root/>";
        pc.update(poll);

        assertEquals("GAMING-PC renamed", pc.name);
        assertEquals(ComputerDetails.State.ONLINE, pc.state);
        assertEquals(at("192.168.1.20", 47989), pc.localAddress);
        assertEquals(at("203.0.113.9", 47989), pc.remoteAddress);
        assertEquals(at("pc.lan", 47989), pc.manualAddress);
        assertEquals(at("fd00::20", 47989), pc.ipv6Address);
        assertEquals(at("192.168.1.20", 47989), pc.activeAddress);
        assertEquals("00:11:22:33:44:55", pc.macAddress);
        assertEquals(47990, pc.httpsPort);
        assertEquals(7, pc.runningGameId);
        assertEquals("<root/>", pc.rawAppList);
    }

    // GS IPv6 Forwarder can make a PC report a loopback address, and some hosts send an all-zero MAC
    @Test
    public void loopbackAddressesAndZeroMacsAreIgnored() {
        ComputerDetails pc = known();
        ComputerDetails poll = new ComputerDetails();
        poll.localAddress = at("127.0.0.1", 47989);
        poll.macAddress = "00:00:00:00:00:00";
        pc.update(poll);
        assertEquals(at("192.168.1.20", 47989), pc.localAddress);
        assertEquals("00:11:22:33:44:55", pc.macAddress);

        poll.localAddress = at("192.168.1.21", 47989);
        poll.macAddress = "66:77:88:99:AA:BB";
        pc.update(poll);
        assertEquals(at("192.168.1.21", 47989), pc.localAddress);
        assertEquals("66:77:88:99:AA:BB", pc.macAddress);
    }

    // GFE sends no remote address, but Sunshine's external port still moves the one we have
    @Test
    public void externalPortMovesTheKnownRemoteAddress() {
        ComputerDetails pc = known();
        ComputerDetails poll = new ComputerDetails();
        poll.externalPort = 50000;
        pc.update(poll);
        assertEquals(50000, pc.remoteAddress.port);
        assertEquals(50000, pc.externalPort);
        assertEquals(50000, pc.guessExternalPort());
    }

    @Test
    public void aCopyHasTheSameDetails() {
        ComputerDetails copy = new ComputerDetails(known());
        assertEquals("uuid-a", copy.uuid);
        assertEquals(at("pc.lan", 47989), copy.manualAddress);
        assertNull(copy.serverCert);
    }

    @Test
    public void guessedExternalPortFallsBackInOrder() {
        ComputerDetails pc = new ComputerDetails();
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, pc.guessExternalPort());
        pc.localAddress = at("192.168.1.20", 1);
        assertEquals(1, pc.guessExternalPort());
        pc.ipv6Address = at("fd00::20", 2);
        assertEquals(2, pc.guessExternalPort());
        pc.activeAddress = at("192.168.1.20", 3);
        assertEquals(3, pc.guessExternalPort());
        pc.remoteAddress = at("203.0.113.9", 4);
        assertEquals(4, pc.guessExternalPort());
        pc.externalPort = 5;
        assertEquals(5, pc.guessExternalPort());
    }

    @Test
    public void addressTuples() {
        assertEquals("192.168.1.20:47989", at("192.168.1.20", 47989).toString());
        assertEquals("[fd00::20]:47989", at("[fd00::20]", 47989).toString());
        assertEquals("fd00::20", at("[fd00::20]", 47989).address);
        assertEquals(at("pc", 1), at("pc", 1));
        assertEquals(at("pc", 1).hashCode(), at("pc", 1).hashCode());
        for (int port : new int[] {0, -1}) {
            try {
                at("pc", port);
                fail("accepted port " + port);
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            at(null, 1);
            fail();
        } catch (IllegalArgumentException expected) {
        }
        assertNull(new ComputerDetails().uuid);
    }
}
