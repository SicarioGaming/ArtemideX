package com.limelight.computers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.LimelightCryptoProvider;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * The "PC not paired" flicker: while paired, the games screen closed with "PC not paired" now
 * and then, and the PC's card said so for about a second. The PC's answers below are shaped like
 * the ones Vibepollo gave the tablet: asked at its IPv4 and IPv6 address at once, as the poll
 * does, about a third of the answers came back over HTTPS with PairStatus 0 and Permission 0, and
 * asked again on its own right after, it said paired. Only parses text: nothing here opens a
 * connection.
 */
public class PairStateCheckTest {
    private static final String UUID = "0F1E2D3C";

    private static String serverInfo(String mac, int pairStatus, int permission) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\">"
                + "<hostname>GAMING-PC</hostname><appversion>7.1.431.-1</appversion>"
                + "<uniqueid>" + UUID + "</uniqueid><HttpsPort>47984</HttpsPort>"
                + "<mac>" + mac + "</mac><LocalIP>192.168.1.20</LocalIP>"
                + "<PairStatus>" + pairStatus + "</PairStatus><Permission>" + permission + "</Permission>"
                + "<state>SUNSHINE_SERVER_FREE</state><currentgame>0</currentgame></root>";
    }

    // Over HTTPS, from a client the host knows
    private static final String PAIRED = serverInfo("00:11:22:33:44:55", 1, 2147483647);
    // Over HTTPS too, status 200, when another request from the same client arrived at the same moment
    private static final String ODD = serverInfo("00:11:22:33:44:55", 0, 0);
    // Over HTTP, which is what the app asks when it has no certificate or HTTPS refuses it
    private static final String OVER_HTTP = serverInfo("00:00:00:00:00:00", 0, 0);

    private static ComputerDetails answer(String serverInfo) throws Exception {
        LimelightCryptoProvider noKeys = new LimelightCryptoProvider() {
            @Override public X509Certificate getClientCertificate() { return null; }
            @Override public PrivateKey getClientPrivateKey() { return null; }
            @Override public byte[] getPemEncodedClientCertificate() { return null; }
            @Override public String encodeBase64String(byte[] data) { return null; }
        };
        ComputerDetails.AddressTuple address = new ComputerDetails.AddressTuple("192.168.1.20", NvHTTP.DEFAULT_HTTP_PORT);
        ComputerDetails details = new NvHTTP(address, 0, "client", null, noKeys).getComputerDetails(serverInfo);
        // parallelPollPc() sets the address that answered
        details.activeAddress = address;
        return details;
    }

    // The PC as the app knows it after a first poll that said paired
    private static ComputerDetails pairedPc() throws Exception {
        ComputerDetails pc = new ComputerDetails();
        PairStateCheck.merge(pc, answer(PAIRED), PairStateCheckTest::neverAsked);
        assertEquals(PairState.PAIRED, pc.pairState);
        return pc;
    }

    private static PairState neverAsked() {
        fail("asked the PC again when there was nothing to confirm");
        return null;
    }

    /** Answers to "ask again", in order, and how many were asked for. */
    private static final class Again implements PairStateCheck.AskAgain {
        private final List<String> answers = new ArrayList<>();
        int asked;

        Again(String... answers) {
            for (String a : answers) {
                this.answers.add(a);
            }
        }

        @Override
        public PairState pairState() throws IOException {
            if (asked >= answers.size()) {
                fail("asked again more often than expected");
            }
            String a = answers.get(asked++);
            if (a == null) {
                throw new IOException("no answer");
            }
            try {
                return answer(a).pairState;
            }
            catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @Test
    public void oneOddAnswerDoesntUnpairAPairedPc() throws Exception {
        // The polling thread's polls while the games screen is open, one every 1.5 s: what the
        // screen's listener sees after each is the PC's pair state
        ComputerDetails pc = pairedPc();
        Again again = new Again(PAIRED);
        List<PairState> seen = new ArrayList<>();
        for (String poll : new String[] {PAIRED, PAIRED, ODD, PAIRED, PAIRED}) {
            PairStateCheck.merge(pc, answer(poll), again);
            seen.add(pc.pairState);
            assertEquals(ComputerDetails.State.ONLINE, pc.state);
        }
        assertFalse("the PC showed as not paired after one odd answer: " + seen, seen.contains(PairState.NOT_PAIRED));
        assertEquals("asked again only after the odd answer", 1, again.asked);
    }

    @Test
    public void aPcUnpairedOnTheHostStillShowsAsUnpairedAtOnce() throws Exception {
        // Removed from the host's paired clients: HTTPS refuses the certificate, so the app falls
        // back to HTTP, which says not paired, and asked again it says the same
        ComputerDetails pc = pairedPc();
        Again again = new Again(OVER_HTTP);
        PairStateCheck.merge(pc, answer(OVER_HTTP), again);
        assertEquals(PairState.NOT_PAIRED, pc.pairState);
        assertEquals(1, again.asked);
        // From then on it's not paired, and "not paired" needs no second request
        PairStateCheck.merge(pc, answer(OVER_HTTP), PairStateCheckTest::neverAsked);
        assertEquals(PairState.NOT_PAIRED, pc.pairState);
    }

    @Test
    public void noAnswerToTheSecondRequestKeepsItPairedUntilTheNextPoll() throws Exception {
        ComputerDetails pc = pairedPc();
        Again again = new Again(null, OVER_HTTP);
        PairStateCheck.merge(pc, answer(ODD), again);
        assertEquals(PairState.PAIRED, pc.pairState);
        // The next poll says not paired again, and this time the second request agrees
        PairStateCheck.merge(pc, answer(ODD), again);
        assertEquals(PairState.NOT_PAIRED, pc.pairState);
        assertEquals(2, again.asked);
    }

    @Test
    public void theFirstPollAfterTheAppStartsDoesntUnpairIt() throws Exception {
        // Read from the database: the certificate pinned when it paired, no pair state yet. Its
        // card is tapped as soon as it shows online, and a card that says not paired starts pairing.
        ComputerDetails pc = savedPc();
        assertNull(pc.pairState);
        Again again = new Again(PAIRED);
        PairStateCheck.merge(pc, answer(ODD), again);
        assertEquals(PairState.PAIRED, pc.pairState);
        assertEquals(1, again.asked);
    }

    @Test
    public void aPcNotKnownToBePairedTakesTheAnswerAsItIs() throws Exception {
        // Never polled yet
        ComputerDetails fresh = new ComputerDetails();
        assertNull(fresh.pairState);
        PairStateCheck.merge(fresh, answer(OVER_HTTP), PairStateCheckTest::neverAsked);
        assertEquals(PairState.NOT_PAIRED, fresh.pairState);
        // Paired since: one answer is enough to show it
        PairStateCheck.merge(fresh, answer(PAIRED), PairStateCheckTest::neverAsked);
        assertEquals(PairState.PAIRED, fresh.pairState);
    }

    @Test
    public void aSavedPcFoundByMdnsDoesntComeBackUnpaired() throws Exception {
        // addComputerBlocking(): mDNS finds the PC again, a first poll without a certificate goes
        // over HTTP, then it takes the saved PC's certificate and polls again. That second poll
        // runs alongside the polling thread's, and its answer was the odd one. The saved PC has
        // been polled already, or not yet.
        for (ComputerDetails saved : new ComputerDetails[] {pairedPc(), savedPc()}) {
            ComputerDetails found = new ComputerDetails();
            PairStateCheck.merge(found, answer(OVER_HTTP), PairStateCheckTest::neverAsked);
            assertEquals(PairState.NOT_PAIRED, found.pairState);

            PairStateCheck.takeSaved(found, saved);
            Again again = new Again(PAIRED);
            PairStateCheck.merge(found, answer(ODD), again);
            // runPoll() tells the listeners about found, then addTuple() copies it into the saved PC
            assertEquals(PairState.PAIRED, found.pairState);
            saved.update(found);
            assertEquals(PairState.PAIRED, saved.pairState);
            assertEquals(1, again.asked);
        }
    }

    // A self-signed certificate made for this test with keytool; nothing checks it
    private static final String TEST_CERT = "-----BEGIN CERTIFICATE-----\n"
            + "MIIBQTCB56ADAgECAghbt8YJ1FbeqTAKBggqhkjOPQQDAzAUMRIwEAYDVQQDEwlU\n"
            + "ZXN0IGhvc3QwIBcNMjYxMDIzMTAwMTQ5WhgPMjEyNjA5MjkxMDAxNDlaMBQxEjAQ\n"
            + "BgNVBAMTCVRlc3QgaG9zdDBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABO8a7B88\n"
            + "dATFBysTAObxOAvWtAsh1Melh/WE9EOyYLi7F4SBuiA2AJm5VrTZZZmg0d380ucP\n"
            + "ID5PKXHk7PfKnu+jITAfMB0GA1UdDgQWBBSrbk9yJcuQGFuLp1/O3jRu4BFxwjAK\n"
            + "BggqhkjOPQQDAwNJADBGAiEA+X/nhJQvD+JaDTH2+v/YtpzRm/MvY/KGoYVhFGnP\n"
            + "zNACIQCHhHbMTEDFSrchgnAvT4brwaTZeRvoMmQQnQgewLKz5g==\n"
            + "-----END CERTIFICATE-----\n";

    // The PC as ComputerDatabaseManager reads it when the app starts: paired once, not polled yet
    private static ComputerDetails savedPc() throws Exception {
        ComputerDetails pc = new ComputerDetails();
        pc.uuid = UUID;
        pc.name = "GAMING-PC";
        pc.serverCert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(TEST_CERT.getBytes(StandardCharsets.US_ASCII)));
        return pc;
    }
}
