package no.nordicsemi.android.swaromapmesh.swajaui;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import no.nordicsemi.android.swaromapmesh.R;

public class SvgViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_svg_viewer);

        String svgPath = getIntent().getStringExtra("svg_path");

        WebView webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());

        if (svgPath != null) {
            webView.loadUrl("file://" + svgPath);
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }
}