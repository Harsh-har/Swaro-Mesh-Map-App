package no.nordicsemi.android.swaromapmesh.utils;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Handles encryption/decryption of exported mesh network JSON files.
 *
 * Uses AES-256-GCM (authenticated encryption) instead of ECB:
 *  - A fresh random 12-byte IV is generated for every encryption, so the
 *    same plaintext never produces the same ciphertext twice.
 *  - GCM's built-in authentication tag detects tampering/corruption, so a
 *    modified or corrupted file will fail to decrypt instead of silently
 *    producing garbage.
 *
 * Output format (before Base64): [12-byte IV] + [ciphertext + 16-byte GCM tag]
 * This whole byte array is Base64-encoded, matching the previous behaviour
 * so isEncrypted() / import-export code does not need to change.
 *
 * NOTE: the key below is still a fixed, hardcoded shared secret. That means
 * anyone who decompiles the app (or this desktop tool) can recover the key.
 * This upgrade fixes the ECB weakness, but it is still "obfuscation" rather
 * than protection against someone with access to the app/tool binaries.
 * If you need real protection against that, move to a user-supplied
 * passphrase or an Android Keystore-backed key instead.
 */
public class CryptoUtils {
    private static final String TAG = "CryptoUtils";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // bytes, recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // bits

    // Shared secret. MUST be identical here and in the desktop encrypt/decrypt tool.
    private static final String DEFAULT_KEY = "SwaroMeshMapSecureKey2024";

    private static SecretKeySpec setKey(String myKey) {
        try {
            byte[] key = myKey.getBytes(Charset.forName("UTF-8"));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            key = sha.digest(key); // 32 bytes -> AES-256
            return new SecretKeySpec(key, ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "Error setting key: " + e.getMessage());
        }
        return null;
    }

    public static String encrypt(String strToEncrypt) {
        if (strToEncrypt == null) return null;
        try {
            SecretKeySpec secretKey = setKey(DEFAULT_KEY);
            if (secretKey == null) return null;

            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(strToEncrypt.getBytes(Charset.forName("UTF-8")));

            // Prepend IV so decrypt() can recover it later.
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.encodeToString(combined, Base64.DEFAULT);
        } catch (Exception e) {
            Log.e(TAG, "Error while encrypting: " + e.getMessage());
        }
        return null;
    }

    public static String decrypt(String strToDecrypt) {
        if (strToDecrypt == null) return null;
        try {
            SecretKeySpec secretKey = setKey(DEFAULT_KEY);
            if (secretKey == null) return null;

            byte[] combined = Base64.decode(strToDecrypt, Base64.DEFAULT);
            if (combined.length < GCM_IV_LENGTH) return null;

            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plain = cipher.doFinal(cipherText);
            return new String(plain, Charset.forName("UTF-8"));
        } catch (Exception e) {
            // This will also legitimately fail for corrupted/tampered files,
            // since GCM authentication fails first.
            Log.e(TAG, "Error while decrypting: " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks if the string is likely encrypted or just plain JSON.
     * AES-GCM output, once Base64-encoded, never starts with '{', so this
     * heuristic still works unchanged.
     */
    public static boolean isEncrypted(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        String trimmed = str.trim();
        return !trimmed.startsWith("{");
    }
}