package com.limelight.nvstream.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * What the app reads from a host's serverinfo. Only parses text: nothing here opens a connection.
 */
public class NvHttpServerInfoTest {
    static NvHTTP http() throws Exception {
        return http(0);
    }

    static NvHTTP http(int httpsPort) throws Exception {
        LimelightCryptoProvider noKeys = new LimelightCryptoProvider() {
            @Override public X509Certificate getClientCertificate() { return null; }
            @Override public PrivateKey getClientPrivateKey() { return null; }
            @Override public byte[] getPemEncodedClientCertificate() { return null; }
            @Override public String encodeBase64String(byte[] data) { return null; }
        };
        return new NvHTTP(new ComputerDetails.AddressTuple("192.168.1.20", NvHTTP.DEFAULT_HTTP_PORT), httpsPort,
                "client", null, noKeys);
    }

    // Shaped like Vibepollo's
    private static String serverInfo(String extra) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">"
                + "<hostname>GAMING-PC</hostname><appversion>7.1.431.-1</appversion><uniqueid>0F1E2D3C</uniqueid>"
                + "<mac>00:11:22:33:44:55</mac><LocalIP>192.168.1.20</LocalIP><PairStatus>1</PairStatus>"
                + extra + "</root>";
    }

    @Test
    public void readsTheDetails() throws Exception {
        ComputerDetails d = http().getComputerDetails(serverInfo(
                "<HttpsPort>47990</HttpsPort><ExternalPort>48000</ExternalPort>"
                        + "<state>SUNSHINE_SERVER_BUSY</state><currentgame>7</currentgame>"));
        assertEquals("GAMING-PC", d.name);
        assertEquals("0F1E2D3C", d.uuid);
        assertEquals("00:11:22:33:44:55", d.macAddress);
        assertEquals(47990, d.httpsPort);
        assertEquals(48000, d.externalPort);
        assertEquals(new ComputerDetails.AddressTuple("192.168.1.20", NvHTTP.DEFAULT_HTTP_PORT), d.localAddress);
        assertNull(d.remoteAddress);
        assertEquals(PairingManager.PairState.PAIRED, d.pairState);
        assertEquals(7, d.runningGameId);
        assertFalse(d.nvidiaServer);
        assertEquals(ComputerDetails.State.ONLINE, d.state);
    }

    @Test
    public void currentGameOnlyCountsWhileBusy() throws Exception {
        ComputerDetails d = http().getComputerDetails(serverInfo(
                "<state>SUNSHINE_SERVER_FREE</state><currentgame>7</currentgame>"));
        assertEquals(0, d.runningGameId);
    }

    @Test
    public void missingPortsUseTheDefaults() throws Exception {
        ComputerDetails d = http().getComputerDetails(serverInfo("<state>SUNSHINE_SERVER_FREE</state>"));
        assertEquals(47984, d.httpsPort);
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, d.externalPort);
        assertEquals("UNKNOWN", http().getComputerDetails(serverInfo("<state>x</state>")
                .replace("<hostname>GAMING-PC</hostname>", "")).name);
    }

    // Regression: a port or game number that isn't a number threw NumberFormatException, which
    // the serverinfo poller doesn't catch
    @Test
    public void portsThatArentNumbersAreTreatedAsMissing() throws Exception {
        ComputerDetails d = http().getComputerDetails(serverInfo(
                "<HttpsPort>https</HttpsPort><ExternalPort>99999999999</ExternalPort><state>SUNSHINE_SERVER_FREE</state>"));
        assertEquals(47984, d.httpsPort);
        assertEquals(NvHTTP.DEFAULT_HTTP_PORT, d.externalPort);
    }

    // Out of range they crashed later: AddressTuple refuses port 0 or below, and getHttpsUrl()
    // refuses anything above 65535
    @Test
    public void portsOutOfRangeAreTreatedAsMissing() throws Exception {
        for (String port : new String[] {"0", "-1", "65536", "70000"}) {
            ComputerDetails d = http().getComputerDetails(serverInfo("<HttpsPort>" + port + "</HttpsPort>"
                    + "<ExternalIP>203.0.113.9</ExternalIP><ExternalPort>" + port + "</ExternalPort><state>x</state>"));
            assertEquals(port, 47984, d.httpsPort);
            assertEquals(port, NvHTTP.DEFAULT_HTTP_PORT, d.externalPort);
            assertEquals(new ComputerDetails.AddressTuple("203.0.113.9", NvHTTP.DEFAULT_HTTP_PORT), d.remoteAddress);
        }
        assertEquals(65535, http().getHttpsPort(serverInfo("<HttpsPort>65535</HttpsPort>")));
        assertEquals(1, http().getHttpsPort(serverInfo("<HttpsPort>1</HttpsPort>")));
    }

    @Test(expected = XmlPullParserException.class)
    public void aRunningGameThatIsntANumberIsAParserError() throws Exception {
        http().getComputerDetails(serverInfo("<state>SUNSHINE_SERVER_BUSY</state><currentgame>seven</currentgame>"));
    }

    @Test(expected = XmlPullParserException.class)
    public void theUniqueIdIsMandatory() throws Exception {
        http().getComputerDetails(serverInfo("<state>x</state>").replace("<uniqueid>0F1E2D3C</uniqueid>", ""));
    }

    @Test
    public void geforceExperienceIsRecognisedByItsCodename() throws Exception {
        assertTrue(http().getComputerDetails(serverInfo("<state>MJOLNIR_STATE_FREE</state>")).nvidiaServer);
    }

    @Test
    public void serverVersionHasFourParts() throws Exception {
        assertArrayEquals(new int[] {7, 1, 431, -1}, http().getServerAppVersionQuad(serverInfo("")));
        assertEquals(7, http().getServerMajorVersion(serverInfo("")));
        for (String bad : new String[] {"7.1", "7.1.431.-1.5", "seven.1.431.-1"}) {
            try {
                http().getServerAppVersionQuad(serverInfo("").replace("7.1.431.-1", bad));
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
            }
        }
    }
}
