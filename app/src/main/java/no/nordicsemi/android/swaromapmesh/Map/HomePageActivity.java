package no.nordicsemi.android.swaromapmesh.Map;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.RectF;
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

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import no.nordicsemi.android.swaromapmesh.CommandActivity;
import no.nordicsemi.android.swaromapmesh.R;

public class HomePageActivity extends AppCompatActivity {

    private ImageView svgImageView;
    private SharedPreferences preferences;
    private static final String PREF_NAME   = "SVG_PREF";
    private static final String KEY_SVG_URI = "saved_svg_uri";
    private ActivityResultLauncher<String> filePickerLauncher;

    // Zoom
    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private float minZoom = 1f;
    private static final float MAX_ZOOM        = 8f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;
    private float   lastTouchX, lastTouchY;
    private boolean isDragging = false;

    // SVG state
    private Document svgDocument;
    private String   selectedDeviceId     = null;
    private String   selectedOriginalFill = null;


    // viewBox values  e.g. "100 0 1000 640"
    private float vbX = 0f, vbY = 0f, vbW = 1000f, vbH = 640f;

    // id -> bounding box in SVG coordinate space (one entry per device)
    private final Map<String, RectF> deviceBoundsMap = new LinkedHashMap<>();

    // Groups to skip completely
    private static final String[] SKIP_GROUPS = {
            "Ground_Floor_Walls", "Furniture", "Background"
    };

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_page);

        svgImageView = findViewById(R.id.svgImageView);
        Button btnChange = findViewById(R.id.btnChangeSvg);
        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        setupZoom();
        setupFilePicker();

        String savedUri = preferences.getString(KEY_SVG_URI, null);
        if (savedUri != null) loadSVG(Uri.parse(savedUri));
        else                  openFilePicker();

        btnChange.setOnClickListener(v -> openFilePicker());
    }

    // ── File Picker ───────────────────────────────────────────────────────────

    private void setupFilePicker() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) {
                        getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        preferences.edit().putString(KEY_SVG_URI, uri.toString()).apply();
                        loadSVG(uri);
                    }
                });
    }

    private void openFilePicker() { filePickerLauncher.launch("image/svg+xml"); }

    // ── SVG Loading ───────────────────────────────────────────────────────────

    private void loadSVG(Uri uri) {
        try {
            // Pass 1: parse DOM
            InputStream is1 = getContentResolver().openInputStream(uri);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities",   false); } catch (Exception ignored) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) {}
            try { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignored) {}
            try { factory.setXIncludeAware(false); }         catch (Exception ignored) {}
            try { factory.setExpandEntityReferences(false); } catch (Exception ignored) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override public void warning(org.xml.sax.SAXParseException e)    {}
                @Override public void error(org.xml.sax.SAXParseException e)      {}
                @Override public void fatalError(org.xml.sax.SAXParseException e) {}
            });
            builder.setEntityResolver((pub, sys) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));

            svgDocument = builder.parse(is1);
            svgDocument.getDocumentElement().normalize();
            if (is1 != null) is1.close();

            // Parse viewBox
            String vb = svgDocument.getDocumentElement().getAttribute("viewBox");
            if (vb != null && !vb.isEmpty()) {
                String[] p = vb.trim().split("[\\s,]+");
                if (p.length == 4) {
                    vbX = Float.parseFloat(p[0]);
                    vbY = Float.parseFloat(p[1]);
                    vbW = Float.parseFloat(p[2]);
                    vbH = Float.parseFloat(p[3]);
                }
            }

            selectedDeviceId = null;
            selectedOriginalFill = null;
            buildDeviceBoundsMap();

            // Pass 2: render
            InputStream is2 = getContentResolver().openInputStream(uri);
            SVG svg = SVG.getFromInputStream(is2);
            Picture picture = svg.renderToPicture();
            PictureDrawable drawable = new PictureDrawable(picture);
            if (is2 != null) is2.close();

            svgImageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            svgImageView.setImageDrawable(drawable);

            svgImageView.post(() -> {
                float dW = drawable.getIntrinsicWidth();
                float dH = drawable.getIntrinsicHeight();
                float vW = svgImageView.getWidth();
                float vH = svgImageView.getHeight();
                if (dW <= 0 || dH <= 0) return;

                minZoom = Math.min(vW / dW, vH / dH);
                matrix.reset();
                matrix.postScale(minZoom, minZoom);
                matrix.postTranslate((vW - dW * minZoom) / 2f, (vH - dH * minZoom) / 2f);
                svgImageView.setImageMatrix(matrix);

                Toast.makeText(HomePageActivity.this,
                        deviceBoundsMap.size() + " devices detected", Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ── Device Bounds Map ─────────────────────────────────────────────────────
    // Simple rule: every leaf element (path/rect/circle/etc.) with an id
    // inside <g id="Devices"> becomes one independent clickable device.
    // NO clustering, NO grouping — each element is its own device.

    private void buildDeviceBoundsMap() {
        deviceBoundsMap.clear();
        if (svgDocument == null) return;

        Element devicesGroup = findById(svgDocument.getDocumentElement(), "Devices");
        Element scanRoot = (devicesGroup != null)
                ? devicesGroup
                : svgDocument.getDocumentElement();

        // Collect every leaf element with an id, skip wall/furniture groups
        List<Element> leaves = new ArrayList<>();
        collectAllLeaves(scanRoot, leaves);

        // Use LinkedHashMap so duplicate IDs keep the LAST occurrence
        // (handles SVG bug: two elements with id="eng_f_17")
        Map<String, Element> idToElement = new LinkedHashMap<>();
        for (Element el : leaves) {
            String id = el.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                idToElement.put(id, el); // last one wins for duplicates
            }
        }

        for (Map.Entry<String, Element> entry : idToElement.entrySet()) {
            String  id     = entry.getKey();
            Element el     = entry.getValue();
            RectF   bounds = computeBounds(el);
            if (bounds == null || bounds.isEmpty()) continue;

            // Small elements get extra tap padding so they're easier to hit
            boolean tiny = (bounds.width() < 13f || bounds.height() < 13f);
            float   pad  = tiny ? 20f : 13f;
            bounds.inset(-pad, -pad);

            deviceBoundsMap.put(id, bounds);
        }

        android.util.Log.d("SVG", "Total devices: " + deviceBoundsMap.size());
        for (Map.Entry<String, RectF> e : deviceBoundsMap.entrySet())
            android.util.Log.v("SVG", "  " + e.getKey() + " " + e.getValue());
    }

    /**
     * Recursively collect every non-group element with an id attribute.
     * Skips Ground_Floor_Walls, Furniture, Background subtrees.
     */
    private void collectAllLeaves(Element parent, List<Element> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            String  tag   = child.getTagName().toLowerCase();
            String  id    = child.getAttribute("id");

            // Skip known non-device subtrees
            if (id != null && shouldSkip(id)) continue;

            if (tag.equals("g")) {
                // Always recurse into <g> groups
                collectAllLeaves(child, result);
            } else {
                // Leaf element — add if it has an id
                if (id != null && !id.isEmpty()) {
                    result.add(child);
                }
            }
        }
    }

    private boolean shouldSkip(String id) {
        for (String s : SKIP_GROUPS) if (s.equals(id)) return true;
        return false;
    }

    // ── Bounding Box ──────────────────────────────────────────────────────────

    private RectF computeBounds(Element el) {
        try {
            switch (el.getTagName().toLowerCase()) {
                case "rect": {
                    float x = fa(el,"x"), y = fa(el,"y");
                    float w = fa(el,"width"), h = fa(el,"height");
                    return (w > 0 && h > 0) ? new RectF(x, y, x+w, y+h) : null;
                }
                case "circle": {
                    float cx = fa(el,"cx"), cy = fa(el,"cy"), r = fa(el,"r");
                    return r > 0 ? new RectF(cx-r, cy-r, cx+r, cy+r) : null;
                }
                case "ellipse": {
                    float cx = fa(el,"cx"), cy = fa(el,"cy");
                    float rx = fa(el,"rx"), ry = fa(el,"ry");
                    return new RectF(cx-rx, cy-ry, cx+rx, cy+ry);
                }
                case "path":     return boundsFromNumbers(el.getAttribute("d"));
                case "polygon":
                case "polyline": return boundsFromNumbers(el.getAttribute("points"));
                case "line": {
                    float x1=fa(el,"x1"), y1=fa(el,"y1");
                    float x2=fa(el,"x2"), y2=fa(el,"y2");
                    return new RectF(Math.min(x1,x2), Math.min(y1,y2),
                            Math.max(x1,x2), Math.max(y1,y2));
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private RectF boundsFromNumbers(String data) {
        if (data == null || data.isEmpty()) return null;
        Matcher m = Pattern.compile("[-+]?[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?").matcher(data);
        List<Float> nums = new ArrayList<>();
        while (m.find()) {
            try { nums.add(Float.parseFloat(m.group())); } catch (Exception ignored) {}
        }
        if (nums.size() < 2) return null;
        float minX=Float.MAX_VALUE, minY=Float.MAX_VALUE;
        float maxX=-Float.MAX_VALUE, maxY=-Float.MAX_VALUE;
        for (int i = 0; i+1 < nums.size(); i += 2) {
            float x = nums.get(i), y = nums.get(i+1);
            if (x < minX) minX = x; if (x > maxX) maxX = x;
            if (y < minY) minY = y; if (y > maxY) maxY = y;
        }
        return minX == Float.MAX_VALUE ? null : new RectF(minX, minY, maxX, maxY);
    }

    private float fa(Element el, String attr) {
        String v = el.getAttribute(attr);
        return (v == null || v.isEmpty()) ? 0f : Float.parseFloat(v.trim());
    }
    // ── Click Handling ────────────────────────────────────────────────────────

    private void handleSvgClick(float touchX, float touchY) {
        if (svgDocument == null || deviceBoundsMap.isEmpty()) return;

        float[] sc   = touchToSvg(touchX, touchY);
        float   svgX = sc[0], svgY = sc[1];
        android.util.Log.d("SVG_CLICK", "svgX=" + svgX + " svgY=" + svgY);

        // Find smallest bounding box containing the tap point
        String bestId   = null;
        float  bestArea = Float.MAX_VALUE;
        for (Map.Entry<String, RectF> e : deviceBoundsMap.entrySet()) {
            if (e.getValue().contains(svgX, svgY)) {
                float area = e.getValue().width() * e.getValue().height();
                if (area < bestArea) { bestArea = area; bestId = e.getKey(); }
            }
        }

        if (bestId != null) {
            onDeviceClicked(bestId);
        } else if (selectedDeviceId != null) {
            // Tap on empty space — deselect
            restoreColor(selectedDeviceId, selectedOriginalFill);
            selectedDeviceId = null;
            selectedOriginalFill = null;
            reRender();
        }
    }

    private void onDeviceClicked(String deviceId) {
        if (svgDocument == null) return;

        // Deselect previous
        if (selectedDeviceId != null && !selectedDeviceId.equals(deviceId)) {
            restoreColor(selectedDeviceId, selectedOriginalFill);
        }

        // Toggle: tap same device again = deselect
        if (deviceId.equals(selectedDeviceId)) {
            restoreColor(deviceId, selectedOriginalFill);
            selectedDeviceId = null;
            selectedOriginalFill = null;
            reRender();
            Toast.makeText(this, "Deselected: " + deviceId, Toast.LENGTH_SHORT).show();
            return;
        }

        // Select new device → turn red
        Element el = findById(svgDocument.getDocumentElement(), deviceId);
        if (el != null) {
            selectedOriginalFill = el.getAttribute("fill");
            el.setAttribute("fill", "#ff0000");
        }
        selectedDeviceId = deviceId;
        reRender();

        // ✅ Navigate to new Activity when eng_f_18 is clicked
        if (deviceId.equals("eng_f_18")) {
            Intent intent = new Intent(HomePageActivity.this, CommandActivity.class);
            intent.putExtra("device_id", deviceId); // pass ID if needed
            startActivity(intent);
            return;
        }

        Toast.makeText(this, "Device: " + deviceId, Toast.LENGTH_SHORT).show();
    }
    private void restoreColor(String deviceId, String originalFill) {
        Element el = findById(svgDocument.getDocumentElement(), deviceId);
        if (el != null) {
            String fill = (originalFill != null && !originalFill.isEmpty())
                    ? originalFill : "transparent";
            el.setAttribute("fill", fill);
        }
    }

    // ── Coordinate Conversion ─────────────────────────────────────────────────

    private float[] touchToSvg(float touchX, float touchY) {
        Matrix inv = new Matrix();
        matrix.invert(inv);
        float[] pts = {touchX, touchY};
        inv.mapPoints(pts);

        float dW = svgImageView.getDrawable() != null
                ? svgImageView.getDrawable().getIntrinsicWidth()  : svgImageView.getWidth();
        float dH = svgImageView.getDrawable() != null
                ? svgImageView.getDrawable().getIntrinsicHeight() : svgImageView.getHeight();

        // vbX=100 offset is included here — this was the main coordinate bug
        return new float[]{
                vbX + (pts[0] / dW) * vbW,
                vbY + (pts[1] / dH) * vbH
        };
    }

    // ── Re-render ─────────────────────────────────────────────────────────────

    private void reRender() {
        try {
            StringWriter sw = new StringWriter();
            TransformerFactory.newInstance().newTransformer()
                    .transform(new DOMSource(svgDocument), new StreamResult(sw));
            SVG svg = SVG.getFromString(sw.toString());
            PictureDrawable d = new PictureDrawable(svg.renderToPicture());
            svgImageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            svgImageView.setImageDrawable(d);
            svgImageView.setImageMatrix(matrix);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ── DOM Utility ───────────────────────────────────────────────────────────

    private Element findById(Element root, String targetId) {
        if (targetId.equals(root.getAttribute("id"))) return root;
        NodeList ch = root.getChildNodes();
        for (int i = 0; i < ch.getLength(); i++) {
            if (ch.item(i) instanceof Element) {
                Element found = findById((Element) ch.item(i), targetId);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private void setupZoom() {
        svgImageView.setScaleType(ImageView.ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float sf = d.getScaleFactor(), cur = getScale(), ns = cur * sf;
                        if (ns < minZoom) sf = minZoom / cur;
                        if (ns > MAX_ZOOM) sf = MAX_ZOOM / cur;
                        matrix.postScale(sf, sf, d.getFocusX(), d.getFocusY());
                        clampMatrix();
                        svgImageView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        float cur = getScale();
                        float target = (cur > minZoom + 0.5f) ? minZoom : DOUBLE_TAP_ZOOM;
                        matrix.postScale(target/cur, target/cur, e.getX(), e.getY());
                        clampMatrix();
                        svgImageView.setImageMatrix(matrix);
                        return true;
                    }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        handleSvgClick(e.getX(), e.getY());
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
                        matrix.postTranslate(event.getX() - lastTouchX, event.getY() - lastTouchY);
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

    private float getScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void clampMatrix() {
        if (svgImageView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float tX    = matrixValues[Matrix.MTRANS_X];
        float tY    = matrixValues[Matrix.MTRANS_Y];
        if (scale < minZoom) scale = minZoom;
        if (scale > MAX_ZOOM) scale = MAX_ZOOM;
        float dW = svgImageView.getDrawable().getIntrinsicWidth()  * scale;
        float dH = svgImageView.getDrawable().getIntrinsicHeight() * scale;
        float vW = svgImageView.getWidth();
        float vH = svgImageView.getHeight();
        float minTX = (dW < vW) ? (vW - dW) / 2f : Math.min(0, vW - dW);
        float maxTX = (dW < vW) ? (vW - dW) / 2f : 0;
        float minTY = (dH < vH) ? (vH - dH) / 2f : Math.min(0, vH - dH);
        float maxTY = (dH < vH) ? (vH - dH) / 2f : 0;
        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrixValues[Matrix.MTRANS_X] = Math.max(minTX, Math.min(maxTX, tX));
        matrixValues[Matrix.MTRANS_Y] = Math.max(minTY, Math.min(maxTY, tY));
        matrix.setValues(matrixValues);
    }
}