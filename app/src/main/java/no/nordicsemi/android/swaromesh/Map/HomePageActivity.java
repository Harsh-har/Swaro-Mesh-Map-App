package no.nordicsemi.android.swaromesh.Map;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.caverock.androidsvg.SVG;

import java.io.InputStream;

import no.nordicsemi.android.swaromesh.R;

public class HomePageActivity extends AppCompatActivity {

    private ImageView svgImageView;
    private SharedPreferences preferences;
    private static final String PREF_NAME = "SVG_PREF";
    private static final String KEY_SVG_URI = "saved_svg_uri";

    private ActivityResultLauncher<String> filePickerLauncher;

    // Zoom variables
    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private float minZoom = 1f; // dynamically calculated
    private static final float MAX_ZOOM = 8f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float lastTouchX, lastTouchY;
    private boolean isDragging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_page);

        svgImageView = findViewById(R.id.svgImageView);
        Button btnChangeSvg = findViewById(R.id.btnChangeSvg);

        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        setupZoom();
        setupFilePicker();

        String savedUri = preferences.getString(KEY_SVG_URI, null);
        if (savedUri != null) {
            loadSVG(Uri.parse(savedUri));
        } else {
            openFilePicker();
        }

        btnChangeSvg.setOnClickListener(v -> openFilePicker());
    }

    // ─── Zoom Setup ───────────────────────────────────────────────────────────

    private void setupZoom() {
        svgImageView.setScaleType(ImageView.ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float scaleFactor = detector.getScaleFactor();
                        float currentScale = getCurrentScale();
                        float newScale = currentScale * scaleFactor;

                        // Clamp between minZoom and MAX_ZOOM
                        if (newScale < minZoom) scaleFactor = minZoom / currentScale;
                        if (newScale > MAX_ZOOM) scaleFactor = MAX_ZOOM / currentScale;

                        matrix.postScale(scaleFactor, scaleFactor,
                                detector.getFocusX(), detector.getFocusY());
                        clampMatrix();
                        svgImageView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        float currentScale = getCurrentScale();
                        float targetScale = (currentScale > minZoom + 0.5f) ? minZoom : DOUBLE_TAP_ZOOM;
                        float scaleFactor = targetScale / currentScale;
                        matrix.postScale(scaleFactor, scaleFactor, e.getX(), e.getY());
                        clampMatrix();
                        svgImageView.setImageMatrix(matrix);
                        return true;
                    }
                });

        svgImageView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    isDragging = true;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (isDragging && !scaleDetector.isInProgress()) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        matrix.postTranslate(dx, dy);
                        clampMatrix();
                        svgImageView.setImageMatrix(matrix);
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    break;
            }
            return true;
        });
    }

    private float getCurrentScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void clampMatrix() {
        if (svgImageView.getDrawable() == null) return;

        matrix.getValues(matrixValues);
        float scale  = matrixValues[Matrix.MSCALE_X];
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];

        // Clamp scale
        if (scale < minZoom) scale = minZoom;
        if (scale > MAX_ZOOM) scale = MAX_ZOOM;

        float drawableW = svgImageView.getDrawable().getIntrinsicWidth()  * scale;
        float drawableH = svgImageView.getDrawable().getIntrinsicHeight() * scale;
        float viewW     = svgImageView.getWidth();
        float viewH     = svgImageView.getHeight();

        float minTransX = (drawableW < viewW) ? (viewW - drawableW) / 2f : Math.min(0, viewW - drawableW);
        float maxTransX = (drawableW < viewW) ? (viewW - drawableW) / 2f : 0;
        float minTransY = (drawableH < viewH) ? (viewH - drawableH) / 2f : Math.min(0, viewH - drawableH);
        float maxTransY = (drawableH < viewH) ? (viewH - drawableH) / 2f : 0;

        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrixValues[Matrix.MTRANS_X] = Math.max(minTransX, Math.min(maxTransX, transX));
        matrixValues[Matrix.MTRANS_Y] = Math.max(minTransY, Math.min(maxTransY, transY));
        matrix.setValues(matrixValues);
    }

    // ─── File Picker ──────────────────────────────────────────────────────────

    private void setupFilePicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        preferences.edit().putString(KEY_SVG_URI, uri.toString()).apply();
                        loadSVG(uri);
                    }
                });
    }

    private void openFilePicker() {
        filePickerLauncher.launch("image/svg+xml");
    }

    // ─── SVG Loading ──────────────────────────────────────────────────────────

    private void loadSVG(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            SVG svg = SVG.getFromInputStream(inputStream);
            Picture picture = svg.renderToPicture();
            PictureDrawable drawable = new PictureDrawable(picture);

            svgImageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            svgImageView.setImageDrawable(drawable);

            // Wait for view to be laid out, then calculate fit scale
            svgImageView.post(() -> {
                float drawableW = drawable.getIntrinsicWidth();
                float drawableH = drawable.getIntrinsicHeight();
                float viewW     = svgImageView.getWidth();
                float viewH     = svgImageView.getHeight();

                if (drawableW <= 0 || drawableH <= 0) return;

                // minZoom = scale that fits SVG perfectly inside view
                float scaleX = viewW / drawableW;
                float scaleY = viewH / drawableH;
                minZoom = Math.min(scaleX, scaleY);

                // Center the SVG at fit scale
                float offsetX = (viewW - drawableW * minZoom) / 2f;
                float offsetY = (viewH - drawableH * minZoom) / 2f;

                matrix.reset();
                matrix.postScale(minZoom, minZoom);
                matrix.postTranslate(offsetX, offsetY);
                svgImageView.setImageMatrix(matrix);
            });

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading SVG", Toast.LENGTH_SHORT).show();
        }
    }
}