package no.nordicsemi.android.swaromapmesh.Decryptor;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class EncryptedMeshImportHelper {

    /**
     * Given a file URI picked by the user, reads the file, auto-derives
     * the password from the filename, decrypts if needed, and returns
     * the plain nRF Mesh JSON string ready for meshManagerApi.
     *
     * @param context  Android context
     * @param uri      URI from file picker (GetContent result)
     * @return         Plain JSON string (decrypted or original)
     * @throws Exception if decryption fails (wrong password / corrupted file)
     */
    public static String readAndDecrypt(Context context, Uri uri) throws Exception {
        String fileName = getFileName(context, uri);
        String rawContent = readUriAsString(context, uri);
        String password = MeshConfigDecryptor.derivePassword(fileName);

        // ADD THESE LOGS:
        android.util.Log.d("MeshImport", "fileName: " + fileName);
        android.util.Log.d("MeshImport", "derivedPassword: " + password);

        return MeshConfigDecryptor.decrypt(rawContent, password);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    private static String getFileName(Context context, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver()
                    .query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            // Fallback: extract from path
            String path = uri.getPath();
            result = (path != null && path.contains("/"))
                    ? path.substring(path.lastIndexOf('/') + 1)
                    : "mesh.json";
        }
        return result;
    }

    private static String readUriAsString(Context context, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().trim();
    }
}