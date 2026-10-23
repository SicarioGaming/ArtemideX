package com.limelight.binding.audio.securemic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.limelight.LimeLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The PCs Starwire has paired its microphone with, one per PC (by its uuid), in the app's private
 * preferences file "mic_pairings". Like all of Starwire's preferences it's left out of backups and
 * device transfers (res/xml/backup_rules.xml and backup_rules_s.xml).
 *
 * On Android 6.0 and later each pairing key is encrypted with an AES key that never leaves Android
 * Keystore, so the file alone is no use to anyone. Android 5.0 and 5.1 have no AES keys in
 * Keystore, so there the key is kept as it is, in the app's private file (the maintainer's call).
 * A pairing that can't be read any more (Keystore lost its key after an update or restore, say)
 * counts as not paired, so the user pairs again; it never crashes.
 */
public final class MicPairingStore {
    static final String PREFS_NAME = "mic_pairings";
    static final String KEYSTORE_ALIAS = "starwire_mic_pairings";
    private static final String WRAPPED = "ks1:";
    private static final String PLAIN = "raw1:";
    private static final int GCM_IV = 12;

    private final SharedPreferences prefs;

    public MicPairingStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The pairing with this PC, or null if there's none or it can't be read. */
    public synchronized MicPairing forComputer(String computerUuid) {
        if (computerUuid == null) {
            return null;
        }
        String saved = prefs.getString(computerUuid, null);
        if (saved == null) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(saved);
            byte[] key = unwrap(o.getString("key"), computerUuid);
            if (key == null) {
                return null;
            }
            return new MicPairing(computerUuid, o.optString("computerName"), hex(o.getString("deviceId")), key,
                    hex(o.getString("receiverId")), o.optString("receiverName"), o.optLong("pairedAt"));
        }
        catch (JSONException | RuntimeException e) {
            LimeLog.warning("Microphone pairing for " + computerUuid + " can't be read: " + e);
            return null;
        }
    }

    /**
     * The device id Starwire uses with this PC, even if its key can't be read any more, so pairing
     * again replaces the PC's old entry instead of adding another. null if never paired.
     */
    public synchronized byte[] deviceIdFor(String computerUuid) {
        String saved = computerUuid != null ? prefs.getString(computerUuid, null) : null;
        if (saved == null) {
            return null;
        }
        try {
            byte[] id = hex(new JSONObject(saved).getString("deviceId"));
            return id.length == SwmcCodec.ID_LENGTH ? id : null;
        }
        catch (JSONException | RuntimeException e) {
            return null;
        }
    }

    /** Keeps a pairing, replacing any earlier one with the same PC. False if it couldn't. */
    public synchronized boolean save(MicPairing pairing) {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            o.put("deviceId", hex(pairing.deviceId));
            o.put("key", wrap(pairing.key, pairing.computerUuid));
            o.put("receiverId", hex(pairing.receiverId));
            o.put("receiverName", pairing.receiverName);
            o.put("computerName", pairing.computerName);
            o.put("pairedAt", pairing.pairedAtMs);
            return prefs.edit().putString(pairing.computerUuid, o.toString()).commit();
        }
        catch (JSONException | GeneralSecurityException | RuntimeException e) {
            LimeLog.warning("Couldn't save the microphone pairing: " + e);
            return false;
        }
    }

    /** Forgets the pairing with this PC, on disk before it returns. False if it couldn't. */
    public synchronized boolean remove(String computerUuid) {
        return prefs.edit().remove(computerUuid).commit();
    }

    /** Every pairing that can be read, oldest first. */
    public synchronized List<MicPairing> all() {
        List<MicPairing> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (e.getValue() instanceof String) {
                MicPairing p = forComputer(e.getKey());
                if (p != null) {
                    out.add(p);
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.pairedAtMs, b.pairedAtMs));
        return out;
    }

    // ---------------------------------------------------------------- the key, wrapped
    // Bound to the PC's uuid, so a wrapped key copied to another PC's entry doesn't unwrap
    private static byte[] associatedData(String computerUuid) {
        return ("starwire mic pairing v1|" + computerUuid).getBytes(StandardCharsets.UTF_8);
    }

    private static String wrap(byte[] key, String computerUuid) throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return PLAIN + hex(key);
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey(true));
        cipher.updateAAD(associatedData(computerUuid));
        byte[] sealed = cipher.doFinal(key);
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != GCM_IV) {
            throw new GeneralSecurityException("unexpected IV from Keystore");
        }
        byte[] out = new byte[GCM_IV + sealed.length];
        System.arraycopy(iv, 0, out, 0, GCM_IV);
        System.arraycopy(sealed, 0, out, GCM_IV, sealed.length);
        return WRAPPED + hex(out);
    }

    // The 32-byte key, or null if it can't be read
    private static byte[] unwrap(String stored, String computerUuid) {
        try {
            byte[] key;
            if (stored.startsWith(PLAIN)) {
                key = hex(stored.substring(PLAIN.length()));
            }
            else if (stored.startsWith(WRAPPED)) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    return null;
                }
                byte[] blob = hex(stored.substring(WRAPPED.length()));
                if (blob.length <= GCM_IV) {
                    return null;
                }
                Key wrapping = keystoreKey(false);
                if (wrapping == null) {
                    LimeLog.warning("Microphone pairing: its Keystore key is gone");
                    return null;
                }
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, wrapping, new GCMParameterSpec(128, blob, 0, GCM_IV));
                cipher.updateAAD(associatedData(computerUuid));
                key = cipher.doFinal(blob, GCM_IV, blob.length - GCM_IV);
            }
            else {
                return null;
            }
            return key.length == SwmcCodec.KEY_LENGTH ? key : null;
        }
        catch (GeneralSecurityException | RuntimeException e) {
            LimeLog.warning("Microphone pairing can't be unwrapped: " + e);
            return null;
        }
    }

    // The AES key in Android Keystore; made the first time if create is set
    private static Key keystoreKey(boolean create) throws GeneralSecurityException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new GeneralSecurityException("no AES keys in Keystore before Android 6.0");
        }
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        try {
            keyStore.load(null);
        }
        catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        Key key = keyStore.getKey(KEYSTORE_ALIAS, null);
        if (key != null || !create) {
            return key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    // ---------------------------------------------------------------- hex
    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] hex(String s) {
        if (s.length() % 2 != 0) {
            throw new IllegalArgumentException("odd hex");
        }
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("not hex");
            }
            b[i] = (byte) (hi << 4 | lo);
        }
        return b;
    }

    // For tests: the stored text for a PC
    String rawFor(String computerUuid) {
        return prefs.getString(computerUuid, null);
    }

    // For tests: a stored entry as it is, for checking what broken ones do
    boolean putRaw(String computerUuid, String value) {
        return prefs.edit().putString(computerUuid, value).commit();
    }

    // For tests: forget everything
    boolean clear() {
        return prefs.edit().clear().commit();
    }
}
