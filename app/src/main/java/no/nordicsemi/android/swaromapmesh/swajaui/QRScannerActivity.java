package no.nordicsemi.android.swaromapmesh.swajaui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;

public class QRScannerActivity extends AppCompatActivity {

    private boolean isScanned = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startQRScanner();
    }

    private void startQRScanner() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setPrompt("Scan QR for Map");
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (isScanned) return;

        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);

        if (result != null && result.getContents() != null) {
            isScanned = true;
            String qrUrl = result.getContents();
            Toast.makeText(this, "QR Scanned!", Toast.LENGTH_SHORT).show();
            downloadSvg(qrUrl);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
            finish();
        }
    }
    private void downloadSvg(String urlString) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
                StringBuilder svgData = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    svgData.append(line).append("\n");
                }
                reader.close();

                // Save to cache
                File cacheFile = new File(getCacheDir(), "map.svg");
                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(svgData.toString().getBytes());
                fos.close();

                // Directly show SVG
                runOnUiThread(() -> {
                    Toast.makeText(this, "Map Loaded!", Toast.LENGTH_SHORT).show();
                    Intent intent = new Intent(this, SvgViewerActivity.class);
                    intent.putExtra("svg_path", cacheFile.getAbsolutePath());
                    startActivity(intent);
                    finish();
                });

            } catch (Exception e) {
                android.util.Log.e("QRScanner", "Error: " + e.getMessage(), e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
            }
        }).start();
        Log.d("QR_DEBUG", "Downloading from: " + urlString);
    }
    // Parses area IDs directly from File using FileInputStream
    private ArrayList<String> parseAreaIdsFromFile(File file) {
        ArrayList<String> areas = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}

            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));

            java.io.InputStream is = new java.io.FileInputStream(file);
            org.w3c.dom.Document doc = builder.parse(is);
            is.close();
            doc.getDocumentElement().normalize();

            // Find <g id="Areas"> or similar group
            org.w3c.dom.Element areasGroup = findElementById(doc.getDocumentElement(), "Areas");
            android.util.Log.d("QRScanner", "Areas group found: " + (areasGroup != null));

            if (areasGroup != null) {
                org.w3c.dom.NodeList children = areasGroup.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    org.w3c.dom.Node child = children.item(i);
                    if (child instanceof org.w3c.dom.Element) {
                        org.w3c.dom.Element el = (org.w3c.dom.Element) child;
                        String id = el.getAttribute("id");
                        if (id != null && !id.isEmpty()) {
                            areas.add(id);
                            android.util.Log.d("QRScanner", "Area added: " + id);
                        }
                    }
                }
            }

        } catch (Exception e) {
            android.util.Log.e("QRScanner", "Parse error: " + e.getMessage(), e);
        }
        return areas;
    }

    private org.w3c.dom.Element findElementById(org.w3c.dom.Element root, String targetId) {
        if (targetId.equals(root.getAttribute("id"))) return root;
        org.w3c.dom.NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node child = children.item(i);
            if (child instanceof org.w3c.dom.Element) {
                org.w3c.dom.Element found = findElementById((org.w3c.dom.Element) child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }
}