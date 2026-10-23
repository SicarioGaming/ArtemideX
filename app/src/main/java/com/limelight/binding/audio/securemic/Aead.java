package com.limelight.binding.audio.securemic;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.GeneralSecurityException;

/**
 * AES-256-GCM with a 12-byte nonce and a 16-byte tag, from the platform's own provider (Conscrypt
 * on Android). One instance per key, used by one thread at a time.
 */
public final class Aead {
    public static final int TAG_LENGTH = 16;
    public static final int NONCE_LENGTH = 12;

    private final SecretKeySpec key;
    private final Cipher cipher;

    public Aead(byte[] key) throws GeneralSecurityException {
        if (key.length != 32) {
            throw new GeneralSecurityException("AES-256 needs a 32-byte key");
        }
        this.key = new SecretKeySpec(key, "AES");
        cipher = Cipher.getInstance("AES/GCM/NoPadding");
    }

    /** The provider doing the work, for the log. */
    public String providerName() {
        return cipher.getProvider().getName();
    }

    /**
     * Encrypts in[inAt..inAt+inLength) into out at outAt, ciphertext then tag. Returns the bytes
     * written (inLength + 16).
     */
    public int seal(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength,
                    byte[] in, int inAt, int inLength, byte[] out, int outAt) throws GeneralSecurityException {
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce, nonceAt, NONCE_LENGTH));
        cipher.updateAAD(aad, aadAt, aadLength);
        return cipher.doFinal(in, inAt, inLength, out, outAt);
    }

    /**
     * Decrypts ciphertext and tag into out at outAt. Returns the plaintext's length, or -1 if it
     * isn't authentic (out may then hold anything).
     */
    public int open(byte[] nonce, int nonceAt, byte[] aad, int aadAt, int aadLength,
                    byte[] in, int inAt, int inLength, byte[] out, int outAt) throws GeneralSecurityException {
        if (inLength < TAG_LENGTH || out.length - outAt < inLength - TAG_LENGTH) {
            return -1;
        }
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce, nonceAt, NONCE_LENGTH));
        cipher.updateAAD(aad, aadAt, aadLength);
        try {
            return cipher.doFinal(in, inAt, inLength, out, outAt);
        }
        catch (AEADBadTagException e) {
            return -1;
        }
    }
}
