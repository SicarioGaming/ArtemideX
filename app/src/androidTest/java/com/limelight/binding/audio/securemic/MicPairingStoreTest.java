package com.limelight.binding.audio.securemic;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the microphone's pairings are kept: saved and read back, removed, the raw key never in
 * the preferences file on Android 6.0 and later (Keystore wraps it), and anything broken or moved
 * counting as not paired instead of crashing. Puts back whatever pairings the device had.
 */
@RunWith(AndroidJUnit4.class)
public class MicPairingStoreTest {
    private static final String PC_A = "starwire-test-pc-a";
    private static final String PC_B = "starwire-test-pc-b";
    private static final byte[] KEY = MicPairingStore.hex("5dd86a4e7d247a21ee08ad94b7d10b144658270e8efdab10704eef34dfdc288b");

    private Context context;
    private MicPairingStore store;
    private final Map<String, String> saved = new HashMap<>();

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        store = new MicPairingStore(context);
        for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
            saved.put(e.getKey(), String.valueOf(e.getValue()));
        }
        store.clear();
    }

    @After
    public void tearDown() {
        store.clear();
        for (Map.Entry<String, String> e : saved.entrySet()) {
            store.putRaw(e.getKey(), e.getValue());
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(MicPairingStore.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static MicPairing pairing(String uuid, long pairedAt) {
        return new MicPairing(uuid, "Desk PC", MicPairingStore.hex("a0a1a2a3a4a5a6a7"), KEY,
                MicPairingStore.hex("b0b1b2b3b4b5b6b7"), "TEST-PC", pairedAt);
    }

    @Test
    public void savedPairingsComeBackAndGo() {
        assertNull(store.forComputer(PC_A));
        assertNull(store.deviceIdFor(PC_A));
        assertTrue(store.save(pairing(PC_A, 2000)));
        assertTrue(store.save(pairing(PC_B, 1000)));
        MicPairing back = store.forComputer(PC_A);
        assertNotNull(back);
        assertArrayEquals(KEY, back.key);
        assertArrayEquals(MicPairingStore.hex("a0a1a2a3a4a5a6a7"), back.deviceId);
        assertArrayEquals(MicPairingStore.hex("b0b1b2b3b4b5b6b7"), back.receiverId);
        assertEquals("Desk PC", back.computerName);
        assertEquals("TEST-PC", back.receiverName);
        assertEquals(2000, back.pairedAtMs);
        List<MicPairing> all = store.all();
        assertEquals(2, all.size());
        assertEquals("oldest first", PC_B, all.get(0).computerUuid);

        // Pairing again replaces it
        byte[] newKey = KEY.clone();
        newKey[0] ^= 1;
        assertTrue(store.save(new MicPairing(PC_A, "Desk PC", back.deviceId, newKey, back.receiverId, "TEST-PC", 3000)));
        assertArrayEquals(newKey, store.forComputer(PC_A).key);

        store.remove(PC_A);
        assertNull(store.forComputer(PC_A));
        assertEquals(1, store.all().size());
    }

    @Test
    public void theRawKeyIsNeverInTheFileFromAndroid6() throws Exception {
        assertTrue(store.save(pairing(PC_A, 1)));
        String stored = store.rawFor(PC_A);
        String keyHex = MicPairingStore.hex(KEY);
        File file = new File(context.getApplicationInfo().dataDir, "shared_prefs/" + MicPairingStore.PREFS_NAME + ".xml");
        assertTrue(file.getPath(), file.isFile());
        String xml = read(file);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            assertTrue(stored, stored.contains("\"key\":\"ks1:"));
            assertFalse("the key in the clear", stored.contains(keyHex));
            assertFalse("the key in the clear", xml.contains(keyHex));
        }
        else {
            // Android 5.0 and 5.1: kept as it is, in the app's private file
            assertTrue(stored, stored.contains("\"key\":\"raw1:" + keyHex));
        }
    }

    private static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void brokenOrMovedPairingsCountAsNotPaired() throws Exception {
        assertTrue(store.save(pairing(PC_A, 1)));
        String good = store.rawFor(PC_A);

        // Not JSON, or missing fields
        store.putRaw(PC_A, "{not json");
        assertNull(store.forComputer(PC_A));
        assertNull(store.deviceIdFor(PC_A));
        store.putRaw(PC_A, "{\"v\":1}");
        assertNull(store.forComputer(PC_A));

        // The wrapped key changed by one character
        int at = good.indexOf("ks1:") + 10;
        if (at > 10) {
            char c = good.charAt(at);
            String tampered = good.substring(0, at) + (c == '0' ? '1' : '0') + good.substring(at + 1);
            store.putRaw(PC_A, tampered);
            assertNull("a changed wrapped key", store.forComputer(PC_A));
            // The device id is still there, so pairing again replaces the PC's entry
            assertArrayEquals(MicPairingStore.hex("a0a1a2a3a4a5a6a7"), store.deviceIdFor(PC_A));

            // Copied to another PC's entry: bound to the first PC, so it doesn't unwrap
            store.putRaw(PC_B, good);
            assertNull("a key moved to another PC", store.forComputer(PC_B));
        }

        // A key of the wrong length
        store.putRaw(PC_A, good.replaceAll("\"key\":\"[^\"]*\"", "\"key\":\"raw1:0011\""));
        assertNull(store.forComputer(PC_A));
        // all() skips what it can't read
        assertTrue(store.all().isEmpty());

        store.putRaw(PC_A, good);
        assertNotNull(store.forComputer(PC_A));
    }
}
