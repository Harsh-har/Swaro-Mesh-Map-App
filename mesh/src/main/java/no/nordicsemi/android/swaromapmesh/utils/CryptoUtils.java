package no.nordicsemi.android.swaromapmesh.utils;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {
    private static final String TAG = "CryptoUtils";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    
    // Default encryption key - Ideally this should be more secure or user-provided
    private static final String DEFAULT_KEY = "SwaroMeshMapSecureKey2024";

    private static SecretKeySpec setKey(String myKey) {
        try {
            byte[] key = myKey.getBytes(Charset.forName("UTF-8"));
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            key = sha.digest(key);
            key = Arrays.copyOf(key, 16); // use only first 128 bit for AES-128
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
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encrypted = cipher.doFinal(strToEncrypt.getBytes(Charset.forName("UTF-8")));
            return Base64.encodeToString(encrypted, Base64.DEFAULT);
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
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decoded = Base64.decode(strToDecrypt, Base64.DEFAULT);
            return new String(cipher.doFinal(decoded), Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "Error while decrypting: " + e.getMessage());
            // If decryption fails, it might be a legacy unencrypted file
            return null;
        }
    }
    
    /**
     * Checks if the string is likely encrypted or just plain JSON.
     */
    public static boolean isEncrypted(String str) {
        if (str == null || str.trim().isEmpty()) return false;
        String trimmed = str.trim();
        // If it starts with '{' it's probably plain JSON
        return !trimmed.startsWith("{");
    }
}
