package no.nordicsemi.android.swaromapmesh.swajaui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import no.nordicsemi.android.swaromapmesh.R;

public class MapActivity extends AppCompatActivity {

    private static final String TAG = "MapActivity";

    private WebView     webView;
    private ProgressBar progressBar;
    private TextView    tvStatus;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean svgHandled = false;

    // ==================== LIFECYCLE ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        progressBar = findViewById(R.id.progressBar);
        tvStatus    = findViewById(R.id.tvStatus);
        webView     = findViewById(R.id.webView);

        String scannedUrl = getIntent().getStringExtra("SCANNED_URL");

        if (scannedUrl == null || scannedUrl.isEmpty()) {
            Toast.makeText(this, "No URL received from QR", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Log.d(TAG, "Scanned URL: " + scannedUrl);
        tvStatus.setText("Loading map...");
        progressBar.setVisibility(View.VISIBLE);

        setupWebView(scannedUrl);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (webView != null) webView.destroy();
    }

    // ==================== WEBVIEW SETUP ====================

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView(String url) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 10; Mobile) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
        );

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String loadingUrl = request.getUrl().toString();
                Log.d(TAG, "WebView navigating to: " + loadingUrl);

                if (loadingUrl.toLowerCase().contains(".svg") && !svgHandled) {
                    svgHandled = true;
                    webView.setVisibility(View.GONE);
                    tvStatus.setText("Parsing SVG map...");
                    downloadAndParseSvg(loadingUrl);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page finished: " + url);

                if (svgHandled) return;

                if (url.toLowerCase().contains(".svg")) {
                    svgHandled = true;
                    webView.setVisibility(View.GONE);
                    tvStatus.setText("Parsing SVG map...");
                    downloadAndParseSvg(url);
                    return;
                }

                webView.postDelayed(() -> {
                    if (svgHandled) return;
                    webView.evaluateJavascript(
                            "(function() {" +
                                    "  var all = document.querySelectorAll('[href],[src],[data]');" +
                                    "  for(var i=0; i<all.length; i++){" +
                                    "    var u = all[i].href || all[i].src || all[i].getAttribute('data') || '';" +
                                    "    if(u && u.toLowerCase().indexOf('.svg') !== -1) return u;" +
                                    "  }" +
                                    "  var html = document.documentElement.innerHTML;" +
                                    "  var m = html.match(/https?:\\/\\/[^\"'\\s]+\\.svg[^\"'\\s]*/i);" +
                                    "  return m ? m[0] : '';" +
                                    "})()",
                            value -> {
                                if (svgHandled) return;
                                if (value != null && !value.equals("\"\"") && !value.isEmpty()) {
                                    String svgUrl = value.replace("\"", "").trim();
                                    Log.d(TAG, "JS found SVG URL: " + svgUrl);
                                    svgHandled = true;
                                    runOnUiThread(() -> {
                                        webView.setVisibility(View.GONE);
                                        tvStatus.setText("Parsing SVG map...");
                                    });
                                    downloadAndParseSvg(svgUrl);
                                } else {
                                    Log.w(TAG, "No SVG found on page: " + url);
                                }
                            }
                    );
                }, 500);
            }
        });

        webView.setVisibility(View.INVISIBLE);
        webView.loadUrl(url);
    }

    // ==================== DOWNLOAD & PARSE SVG ====================

    private void downloadAndParseSvg(String svgUrl) {
        executor.execute(() -> {
            try {
                Log.d(TAG, "Downloading SVG: " + svgUrl);

                // ✅ Ek baar download — bytes mein
                HttpURLConnection connection =
                        (HttpURLConnection) new URL(svgUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.connect();

                InputStream is = connection.getInputStream();
                byte[] svgBytes = readAllBytes(is);
                is.close();
                connection.disconnect();

                // ✅ Local file mein save karo — sirf ek baar
                File localFile = new File(getFilesDir(), "cached_map.svg");
                FileOutputStream fos = new FileOutputStream(localFile);
                fos.write(svgBytes);
                fos.close();

                String localUri = Uri.fromFile(localFile).toString();
                Log.d(TAG, "SVG saved locally: " + localUri);

                // ✅ Same bytes se parse karo — no extra network call
                Document doc = parseDocument(new ByteArrayInputStream(svgBytes));
                processDocument(doc, localUri);

            } catch (Exception e) {
                Log.e(TAG, "downloadAndParseSvg error", e);
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this,
                            "Error reading SVG: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
    }

    // ==================== HELPER ====================

    private byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int len;
        while ((len = is.read(chunk)) != -1) buffer.write(chunk, 0, len);
        return buffer.toByteArray();
    }

    // ==================== PROCESS PARSED DOCUMENT ====================

    private void processDocument(Document doc, String localUri) {
        if (doc == null) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "Could not parse SVG", Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }

        Element iconsGroup = findElementById(doc.getDocumentElement(), "Icons");
        ArrayList<String> areaList = new ArrayList<>();

        if (iconsGroup != null) {
            NodeList children = iconsGroup.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (!(node instanceof Element)) continue;
                Element el  = (Element) node;
                String  tag = el.getTagName().toLowerCase();
                if (tag.contains(":")) tag = tag.substring(tag.indexOf(':') + 1);
                if (!"g".equals(tag)) continue;
                String areaId = el.getAttribute("id");
                if (areaId != null && !areaId.isEmpty()) {
                    areaList.add(areaId);
                }
            }
        }

        Log.d(TAG, "Areas found: " + areaList.size() + " → " + areaList);

        if (areaList.isEmpty()) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                Toast.makeText(this, "No areas found in SVG", Toast.LENGTH_LONG).show();
                finish();
            });
            return;
        }

        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);

            int[] counts = new int[areaList.size()];

            // ✅ Local file:// URI save karo — HTTP nahi
            getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("saved_svg_uri", localUri)
                    .putStringSet("saved_area_list", new HashSet<>(areaList))
                    .putString("saved_counts", new com.google.gson.Gson().toJson(counts))
                    .apply();

            Intent intent = new Intent(this, AreaListActivity.class);
            intent.putExtra("svg_uri", localUri);
            intent.putStringArrayListExtra("area_list", areaList);
            startActivity(intent);
            finish();
        });
    }

    // ==================== XML HELPERS ====================

    private Document parseDocument(InputStream inputStream) {
        if (inputStream == null) return null;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try {
                factory.setFeature(
                        "http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature(
                        "http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();
            return doc;

        } catch (Exception e) {
            Log.e(TAG, "parseDocument error", e);
            return null;
        }
    }

    private Element findElementById(Element root, String targetId) {
        if (targetId.equals(root.getAttribute("id"))) return root;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                Element found = findElementById((Element) child, targetId);
                if (found != null) return found;
            }
        }
        return null;
    }
}