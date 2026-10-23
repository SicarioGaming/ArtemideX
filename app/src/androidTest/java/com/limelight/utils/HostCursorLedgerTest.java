package com.limelight.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Checks the per-PC record of a hidden cursor that has to survive Starwire being killed.
 */
@RunWith(AndroidJUnit4.class)
public class HostCursorLedgerTest {
    private static final String HOST = "0F1E2D3C-host-a";

    private File dir;

    @Before
    public void setUp() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        dir = new File(context.getCacheDir(), "host_cursor_ledger_test_" + System.nanoTime());
    }

    @After
    public void tearDown() {
        deleteAll(dir);
    }

    private static void deleteAll(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteAll(child);
            }
        }
        file.delete();
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
        long before = System.currentTimeMillis();
        new HostCursorLedger(dir, HOST).markHidden(7, "always");

        // A fresh object on the same folder stands in for the app being killed and restarted
        HostCursorLedger.Debt debt = new HostCursorLedger(dir, HOST).read();
        assertNotNull(debt);
        assertEquals(7, debt.appId);
        assertEquals("always", debt.reason);
        assertTrue(debt.hiddenAtMs >= before && debt.hiddenAtMs <= System.currentTimeMillis());
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
        writeRecord(HOST, "not a cursor record");
        assertNull(new HostCursorLedger(dir, HOST).read());

        writeRecord(HOST, "v1 hidden soon 7 always");
        assertNull(new HostCursorLedger(dir, HOST).read());

        writeRecord(HOST, "v2 hidden 1 7 always");
        assertNull(new HostCursorLedger(dir, HOST).read());

        writeRecord(HOST, "");
        assertNull(new HostCursorLedger(dir, HOST).read());
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
    public void reasonsWithSpacesSurvive() {
        new HostCursorLedger(dir, HOST).markHidden(1, "stopped without a link");
        assertEquals("stopped without a link", new HostCursorLedger(dir, HOST).read().reason);
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
}
