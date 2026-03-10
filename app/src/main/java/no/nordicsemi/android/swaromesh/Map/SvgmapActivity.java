package no.nordicsemi.android.swaromesh.Map;

import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.drawable.PictureDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.caverock.androidsvg.SVG;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

import no.nordicsemi.android.swaromesh.CommandActivity;
import no.nordicsemi.android.swaromesh.R;

public class SvgmapActivity extends AppCompatActivity {

    private static final String TAG = "SvgmapActivity";

    private ImageView imageView;
    private String selectedDeviceId = null;

    private final String[] deviceIds = {
            "a_1","c_1","a_2","c_2","l_10"
    };

    private final Map<String, float[]> deviceInfo = new LinkedHashMap<String, float[]>() {{
        put("a_1",  new float[]{ 725.07f, 375.02f, 20f });
        put("c_1",  new float[]{ 641.64f, 375.02f, 20f });
        put("a_2",  new float[]{ 725.07f, 300.77f, 20f });
        put("c_2",  new float[]{ 641.64f, 300.77f, 20f });
        put("l_10", new float[]{ 683.00f, 345.00f, 20f });
    }};

    // SVG viewBox exact values — "100 0 1000 640"
    private static final float VB_MIN_X  = 100f;
    private static final float VB_MIN_Y  = 0f;
    private static final float VB_WIDTH  = 1000f;
    private static final float VB_HEIGHT = 640f;

    private Matrix matrix = new Matrix();
    private float[] matrixValues = new float[9];

    private float minScale    = 1.0f;
    private float maxScale    = 4.0f;

    private ScaleGestureDetector scaleDetector;
    private float lastX, lastY;
    private float touchStartX, touchStartY;
    private boolean isDragging   = false;
    private boolean isInitialLoad = true;

    private static final float TAP_SLOP = 12f; // pixels — drag vs tap

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_svgmap);

        imageView = findViewById(R.id.svgImageView);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        imageView.setImageMatrix(matrix);

        scaleDetector = new ScaleGestureDetector(this, new ScaleListener());

        loadSVG();

        imageView.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    lastX = touchStartX = event.getX();
                    lastY = touchStartY = event.getY();
                    isDragging = true;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    isDragging = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (!scaleDetector.isInProgress() && isDragging
                            && event.getPointerCount() == 1) {
                        float dx = event.getX() - lastX;
                        float dy = event.getY() - lastY;
                        matrix.postTranslate(dx, dy);
                        checkAndFixBoundaries();
                        imageView.setImageMatrix(matrix);
                        lastX = event.getX();
                        lastY = event.getY();
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    int ri = event.getActionIndex() == 0 ? 1 : 0;
                    if (ri < event.getPointerCount()) {
                        lastX = event.getX(ri);
                        lastY = event.getY(ri);
                        isDragging = true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    isDragging = false;
                    if (!scaleDetector.isInProgress()) {
                        float upX = event.getX();
                        float upY = event.getY();
                        float dist = (float) Math.hypot(upX - touchStartX, upY - touchStartY);

                        if (dist < TAP_SLOP) {
                            String clickedId = getDeviceAtScreenPoint(upX, upY);
                            Log.d(TAG, "Tap -> device: " + clickedId);
                            if (clickedId != null) {
                                // Check if clicked device is c_1 or c_2
                                if ("c_1".equals(clickedId) || "c_2".equals(clickedId)) {
                                    // Show toast with the address
                                    Toast.makeText(this, "Address: 0x003E", Toast.LENGTH_SHORT).show();

                                    // Navigate to ProvisionActivity
                                    Intent intent = new Intent(this, CommandActivity.class);
                                    // Optionally pass the device ID and address as extras
                                    intent.putExtra("device_id", clickedId);
                                    intent.putExtra("address", "0x003E");
                                    startActivity(intent);

                                    // Clear selection since we're navigating away
                                    selectedDeviceId = null;
                                } else {
                                    // For other devices, just toggle selection as before
                                    selectedDeviceId = clickedId.equals(selectedDeviceId)
                                            ? null : clickedId;
                                }
                                loadSVG();
                            }
                        }
                    }
                    break;
            }
            return true;
        });
    }

    // ─────────────────────────────────────────────────────────────
    //  Click detection — screen → SVG coordinate space
    // ─────────────────────────────────────────────────────────────

    private String getDeviceAtScreenPoint(float screenX, float screenY) {
        // Step 1: Screen pixel → rendered image space (undo pan/zoom matrix)
        Matrix inv = new Matrix();
        if (!matrix.invert(inv)) return null;

        float[] pt = {screenX, screenY};
        inv.mapPoints(pt);

        // pt[0], pt[1] = position in rendered image (0..imageView.width, 0..imageView.height)
        float renderedX = pt[0];
        float renderedY = pt[1];

        //
        float viewW = imageView.getWidth();
        float viewH = imageView.getHeight();

        float svgAspect  = VB_WIDTH  / VB_HEIGHT;   // 1000/640 = 1.5625
        float viewAspect = viewW / viewH;

        float svgRenderW, svgRenderH, offX, offY;

        if (svgAspect > viewAspect) {
            // Width constrained — pillarbox (top/bottom padding)
            svgRenderW = viewW;
            svgRenderH = viewW / svgAspect;
            offX = 0f;
            offY = (viewH - svgRenderH) / 2f;
        } else {
            // Height constrained — letterbox (left/right padding)
            svgRenderH = viewH;
            svgRenderW = viewH * svgAspect;
            offX = (viewW - svgRenderW) / 2f;
            offY = 0f;
        }

        // Rendered pixel → SVG viewBox units (including minX/minY offset)
        float svgX = ((renderedX - offX) / svgRenderW) * VB_WIDTH  + VB_MIN_X;
        float svgY = ((renderedY - offY) / svgRenderH) * VB_HEIGHT + VB_MIN_Y;

        Log.d(TAG, String.format("Screen(%.1f,%.1f) -> Rendered(%.1f,%.1f) -> SVG(%.1f,%.1f)",
                screenX, screenY, renderedX, renderedY, svgX, svgY));

        // Step 3: Closest device within tap radius
        String best     = null;
        float  bestDist = Float.MAX_VALUE;

        for (String id : deviceIds) {
            float[] info = deviceInfo.get(id);
            if (info == null) continue;

            float cx     = info[0];
            float cy     = info[1];
            float radius = info[2];

            float dist = (float) Math.hypot(svgX - cx, svgY - cy);

            if (dist <= radius && dist < bestDist) {
                bestDist = dist;
                best     = id;
            }
        }

        return best;
    }

    // ─────────────────────────────────────────────────────────────
    //  Scale gesture
    // ─────────────────────────────────────────────────────────────

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            matrix.getValues(matrixValues);
            float cur = matrixValues[Matrix.MSCALE_X];
            float sf  = detector.getScaleFactor();

            float next = cur * sf;
            if      (next < minScale) sf = minScale / cur;
            else if (next > maxScale) sf = maxScale / cur;

            matrix.postScale(sf, sf, detector.getFocusX(), detector.getFocusY());
            imageView.setImageMatrix(matrix);
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            checkAndFixBoundaries();
            imageView.setImageMatrix(matrix);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Boundary check
    // ─────────────────────────────────────────────────────────────

    private void checkAndFixBoundaries() {
        if (imageView.getDrawable() == null) return;

        matrix.getValues(matrixValues);
        float scaleX = matrixValues[Matrix.MSCALE_X];
        float scaleY = matrixValues[Matrix.MSCALE_Y];
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];

        float imgW = imageView.getDrawable().getIntrinsicWidth()  * scaleX;
        float imgH = imageView.getDrawable().getIntrinsicHeight() * scaleY;
        float vW   = imageView.getWidth();
        float vH   = imageView.getHeight();

        float minX = imgW <= vW ? (vW - imgW) / 2f : vW - imgW;
        float maxX = imgW <= vW ? minX : 0f;
        float minY = imgH <= vH ? (vH - imgH) / 2f : vH - imgH;
        float maxY = imgH <= vH ? minY : 0f;

        float nx = Math.max(minX, Math.min(maxX, transX));
        float ny = Math.max(minY, Math.min(maxY, transY));

        if (nx != transX || ny != transY) {
            matrix.postTranslate(nx - transX, ny - transY);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Load & render SVG
    // ─────────────────────────────────────────────────────────────

    private void loadSVG() {
        try {
            InputStream is = getAssets().open("Test_Map_dark.svg");
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb  = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            String svg = sb.toString();

            // Update device colors
            for (String id : deviceIds) {
                String color = id.equals(selectedDeviceId) ? "#00FF00" : "#fb0";
                svg = svg.replaceAll(
                        "id=\"" + id + "\" fill=\"#[^\"]*\"",
                        "id=\"" + id + "\" fill=\"" + color + "\""
                );
            }

            SVG svgObj = SVG.getFromString(svg);

            int w = imageView.getWidth();
            int h = imageView.getHeight();

            if (w == 0 || h == 0) {
                imageView.post(this::loadSVG);
                return;
            }

            Picture pic = svgObj.renderToPicture(w, h);
            PictureDrawable drawable = new PictureDrawable(pic);

            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            Matrix saved = (!isInitialLoad && imageView.getDrawable() != null)
                    ? new Matrix(matrix) : null;

            imageView.setImageDrawable(drawable);

            if (isInitialLoad) {
                matrix.reset();

                float imgW = drawable.getIntrinsicWidth();
                float imgH = drawable.getIntrinsicHeight();
                float scale = Math.min(w / imgW, h / imgH);
                float tx = (w - imgW * scale) / 2f;
                float ty = (h - imgH * scale) / 2f;

                matrix.setScale(scale, scale);
                matrix.postTranslate(tx, ty);

                minScale = scale;
                maxScale = scale * 4f;
                isInitialLoad = false;

            } else if (saved != null) {
                matrix.set(saved);
                matrix.getValues(matrixValues);
                float cur = matrixValues[Matrix.MSCALE_X];

                if      (cur < minScale) { float s = minScale/cur; matrix.postScale(s,s,w/2f,h/2f); }
                else if (cur > maxScale) { float s = maxScale/cur; matrix.postScale(s,s,w/2f,h/2f); }

                checkAndFixBoundaries();
            }

            imageView.setImageMatrix(matrix);

        } catch (Exception e) {
            Log.e(TAG, "loadSVG error", e);
        }
    }
}