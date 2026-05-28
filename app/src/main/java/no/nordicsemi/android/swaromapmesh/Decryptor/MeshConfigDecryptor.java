package no.nordicsemi.android.swaromapmesh.Decryptor;

import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class MeshConfigDecryptor {

    private static final String KDF_ALGORITHM    = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final int    KEY_LENGTH_BITS  = 256;
    private static final int    GCM_TAG_LENGTH   = 128;

    public static String decrypt(String encryptedJsonString, String password) throws Exception {
        JSONObject envelope = new JSONObject(encryptedJsonString);

        // If not encrypted, return as-is
        if (!envelope.optBoolean("encrypted", false)) {
            return encryptedJsonString;
        }

        int    iterations = envelope.getInt("iterations");
        byte[] salt       = Base64.decode(envelope.getString("salt"),  Base64.NO_WRAP);
        byte[] nonce      = Base64.decode(envelope.getString("nonce"), Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(envelope.getString("data"),  Base64.NO_WRAP);

        SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        byte[]    keyBytes  = factory.generateSecret(spec).getEncoded();
        SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

        byte[] decryptedBytes = cipher.doFinal(ciphertext);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    public static String derivePassword(String fileName) {
        // Strip .json, then append _encrypted@swaja.com
        String base = fileName.replaceAll("(?i)\\.json$", "");
        // base already has _encrypted (e.g. "8v_encrypted")
        return base + "@swaja.com";
    }
}