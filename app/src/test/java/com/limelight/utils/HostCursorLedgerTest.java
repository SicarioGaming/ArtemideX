package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Checks the per-PC record of a hidden cursor that has to survive Starwire being killed.
 */
public class HostCursorLedgerTest {
    private static final String HOST = "0F1E2D3C-host-a";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File dir;

    @Before
    public void setUp() throws IOException {
        dir = tmp.newFolder();
    }

    private File recordFile(String hostKey) {
        return new File(new File(dir, "host_cursor"), HostCursorLedger.fileNameFor(hostKey));
    }

    private void writeRecord(String hostKey, String text) throws IOException {
        File file = recordFile(hostKey);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void noRecordMeansNothingToFix() {
        assertNull(new HostCursorLedger(dir, HOST).read());
    }

    @Test
    public void aNewInstanceSeesTheRecord() {
        new HostCursorLedger(dir, HOST).markHidden(7, "always");

        // A fresh object on the same folder stands in for the app being killed and restarted
        HostCursorLedger.Debt debt = new HostCursorLedger(dir, HOST).read();
        assertNotNull(debt);
        assertEquals(7, debt.appId);
        assertEquals("always", debt.reason);
        assertTrue(debt.hiddenAtMs > 0);
    }

    // The format a record is saved in, so records from an earlier version still read
    @Test
    public void readsTheSavedFormat() throws IOException {
        writeRecord(HOST, "v1 hidden 1761200000000 42 terminated:-1\n");
        HostCursorLedger.Debt debt = new HostCursorLedger(dir, HOST).read();
        assertEquals(1761200000000L, debt.hiddenAtMs);
        assertEquals(42, debt.appId);
        assertEquals("terminated:-1", debt.reason);
    }

    @Test
    public void markShownRemovesTheRecord() {
        HostCursorLedger ledger = new HostCursorLedger(dir, HOST);
        ledger.markHidden(7, "touch");
        assertTrue(recordFile(HOST).exists());

        ledger.markShown();
        assertFalse(recordFile(HOST).exists());
        assertNull(new HostCursorLedger(dir, HOST).read());

        // Showing twice is harmless
        ledger.markShown();
        assertNull(ledger.read());
    }

    @Test
    public void aDamagedRecordReadsAsNothing() throws IOException {
        for (String damaged : new String[] {"not a cursor record", "v1 hidden soon 7 always", "v2 hidden 1 7 always",
                "v1 hidden notanumber", "v1 shown 1 7 always", "v1 hidden 1 seven always", ""}) {
            writeRecord(HOST, damaged);
            assertNull(damaged, new HostCursorLedger(dir, HOST).read());
        }
    }

    @Test
    public void setReasonKeepsTheRecordAndItsApp() {
        HostCursorLedger ledger = new HostCursorLedger(dir, HOST);
        ledger.markHidden(42, "always");
        ledger.setReason("terminated:-1");

        HostCursorLedger.Debt debt = new HostCursorLedger(dir, HOST).read();
        assertNotNull(debt);
        assertEquals(42, debt.appId);
        assertEquals("terminated:-1", debt.reason);
    }

    @Test
    public void setReasonWithoutARecordAddsNone() {
        HostCursorLedger ledger = new HostCursorLedger(dir, HOST);
        ledger.setReason("terminated:-1");
        assertNull(ledger.read());
    }

    @Test
    public void reasonsWithSpacesSurviveAndNewLinesAreFlattened() {
        new HostCursorLedger(dir, HOST).markHidden(1, "stopped without a link");
        assertEquals("stopped without a link", new HostCursorLedger(dir, HOST).read().reason);
        new HostCursorLedger(dir, HOST).markHidden(1, "two\nlines");
        assertEquals("two lines", new HostCursorLedger(dir, HOST).read().reason);
        new HostCursorLedger(dir, HOST).markHidden(1, "");
        assertEquals("-", new HostCursorLedger(dir, HOST).read().reason);
    }

    @Test
    public void eachPcHasItsOwnRecord() {
        String byAddress = HostCursorLedger.hostKey(null, "192.168.1.20");
        assertEquals("addr:192.168.1.20", byAddress);
        assertEquals(HOST, HostCursorLedger.hostKey(HOST, "192.168.1.20"));
        assertNotEquals(HostCursorLedger.fileNameFor(HOST), HostCursorLedger.fileNameFor(byAddress));

        new HostCursorLedger(dir, HOST).markHidden(1, "always");
        assertNull(new HostCursorLedger(dir, byAddress).read());
        new HostCursorLedger(dir, byAddress).markShown();
        assertNotNull(new HostCursorLedger(dir, HOST).read());
    }

    // SHA-1 in hex, so a PC's name or address never ends up in a file name
    @Test
    public void fileNamesAreAHashOfTheKey() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", HostCursorLedger.fileNameFor(""));
        assertEquals(40, HostCursorLedger.fileNameFor("addr:fe80::1%wlan0").length());
        assertTrue(HostCursorLedger.fileNameFor("addr:fe80::1%wlan0").matches("[0-9a-f]{40}"));
    }
}
