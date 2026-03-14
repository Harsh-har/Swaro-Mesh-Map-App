package no.nordicsemi.android.swaromesh;

import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
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
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromesh.databinding.FragmentNetworkBinding;
import no.nordicsemi.android.swaromesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

    private static final String TAG            = "NetworkFragment";
    private static final float  MAX_ZOOM       = 8f;
    private static final float  DOUBLE_TAP_ZOOM = 2.5f;

    private static final String[] SKIP_GROUPS = {
            "Ground_Floor_Walls", "Furniture", "Background"
    };

    private FragmentNetworkBinding binding;
    private SharedViewModel        mViewModel;

    // ── Zoom ─────────────────────────────────────────────────────────────────
    private final Matrix matrix      = new Matrix();
    private final float[] matrixValues = new float[9];
    private float   minZoom    = 1f;
    private static final float TAP_TOLERANCE = 20f; // svg units
    private float   lastTouchX, lastTouchY;
    private boolean isDragging = false;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector      gestureDetector;

    // ── SVG state ─────────────────────────────────────────────────────────────
    private Document svgDocument          = null;
    private String   selectedDeviceId     = null;
    private String   selectedOriginalFill = null;

    // viewBox  e.g. "100 0 1000 640"
    private float vbX = 0f, vbY = 0f, vbW = 1000f, vbH = 640f;

    private final Map<String, RectF> deviceBoundsMap = new LinkedHashMap<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        binding    = FragmentNetworkBinding.inflate(inflater, container, false);
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        setupZoom();

        mViewModel.getSvgUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) loadSVG(uri);
        });

        return binding.getRoot();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    private void loadSVG(Uri uri) {
        try {
            // ── Pass 1: parse DOM ─────────────────────────────────────────────
            InputStream is1 = requireContext().getContentResolver().openInputStream(uri);

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

            selectedDeviceId     = null;
            selectedOriginalFill = null;
            buildDeviceBoundsMap();

            // ── Pass 2: render ────────────────────────────────────────────────
            InputStream is2 = requireContext().getContentResolver().openInputStream(uri);
            SVG     svg     = SVG.getFromInputStream(is2);
            Picture picture = svg.renderToPicture();
            PictureDrawable drawable = new PictureDrawable(picture);
            if (is2 != null) is2.close();

            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.svgView.setVisibility(View.VISIBLE);
            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);

            // Fit to view after layout pass
            binding.svgView.post(() -> {
                float dW = drawable.getIntrinsicWidth();
                float dH = drawable.getIntrinsicHeight();
                float vW = binding.svgView.getWidth();
                float vH = binding.svgView.getHeight();
                if (dW <= 0 || dH <= 0) return;

                minZoom = Math.min(vW / dW, vH / dH);
                matrix.reset();
                matrix.postScale(minZoom, minZoom);
                matrix.postTranslate(
                        (vW - dW * minZoom) / 2f,
                        (vH - dH * minZoom) / 2f);
                binding.svgView.setImageMatrix(matrix);

                Toast.makeText(requireContext(),
                        deviceBoundsMap.size() + " devices detected",
                        Toast.LENGTH_SHORT).show();
            });

        } catch (Exception e) {
            Log.e(TAG, "loadSVG error: " + e.getMessage());
        }
    }

    private void buildDeviceBoundsMap() {
        deviceBoundsMap.clear();
        if (svgDocument == null) return;

        Element devicesGroup = findById(svgDocument.getDocumentElement(), "Devices");
        Element scanRoot     = (devicesGroup != null)
                ? devicesGroup
                : svgDocument.getDocumentElement();

        List<Element> leaves = new ArrayList<>();
        collectAllLeaves(scanRoot, leaves);

        Map<String, Element> idToElement = new LinkedHashMap<>();
        for (Element el : leaves) {
            String id = el.getAttribute("id");
            if (id != null && !id.isEmpty()) idToElement.put(id, el);
        }

        for (Map.Entry<String, Element> entry : idToElement.entrySet()) {
            String  id     = entry.getKey();
            Element el     = entry.getValue();
            RectF   bounds = computeBounds(el);
            if (bounds == null || bounds.isEmpty()) continue;

            deviceBoundsMap.put(id, bounds);
        }

        Log.d(TAG, "Total devices: " + deviceBoundsMap.size());
    }

    private void collectAllLeaves(Element parent, List<Element> result) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element)) continue;
            Element child = (Element) children.item(i);
            String  tag   = child.getTagName().toLowerCase();
            String  id    = child.getAttribute("id");

            if (id != null && shouldSkip(id)) continue;

            if (tag.equals("g")) {
                collectAllLeaves(child, result);
            } else {
                if (id != null && !id.isEmpty()) result.add(child);
            }
        }
    }

    private boolean shouldSkip(String id) {
        for (String s : SKIP_GROUPS) if (s.equals(id)) return true;
        return false;
    }

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

        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();

        String cleaned = data.replaceAll("([MmLlHhVvCcSsQqTtAaZz])", " $1 ");
        String[] tokens = cleaned.trim().split("[\\s,]+");

        char currentCmd = 'M';
        int  argIndex   = 0;

        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            char c = tok.charAt(0);
            if (Character.isLetter(c)) {
                currentCmd = c;
                argIndex   = 0;
                continue;
            }
            float val;
            try { val = Float.parseFloat(tok); } catch (Exception e) { continue; }

            switch (currentCmd) {
                case 'M': case 'L': case 'm': case 'l':
                case 'C': case 'c': case 'S': case 's':
                case 'Q': case 'q': case 'T': case 't':
                    if (argIndex % 2 == 0) xs.add(val); else ys.add(val);
                    argIndex++;
                    break;
                case 'H': xs.add(val); argIndex++; break;
                case 'h': argIndex++; break;
                case 'V': ys.add(val); argIndex++; break;
                case 'v': argIndex++; break;
                case 'A':
                    if (argIndex % 7 == 5) xs.add(val);
                    if (argIndex % 7 == 6) ys.add(val);
                    argIndex++;
                    break;
                case 'a': argIndex++; break;
                default:
                    if (argIndex % 2 == 0) xs.add(val); else ys.add(val);
                    argIndex++;
                    break;
            }
        }

        if (xs.isEmpty() || ys.isEmpty()) return null;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float x : xs) { if (x < minX) minX = x; if (x > maxX) maxX = x; }
        for (float y : ys) { if (y < minY) minY = y; if (y > maxY) maxY = y; }
        if (minX == Float.MAX_VALUE || minY == Float.MAX_VALUE) return null;
        return new RectF(minX, minY, maxX, maxY);
    }

    private float fa(Element el, String attr) {
        String v = el.getAttribute(attr);
        return (v == null || v.isEmpty()) ? 0f : Float.parseFloat(v.trim());
    }

    private void handleSvgClick(float touchX, float touchY) {
        if (svgDocument == null || deviceBoundsMap.isEmpty()) return;

        float[] sc   = touchToSvg(touchX, touchY);
        float   svgX = sc[0], svgY = sc[1];
        Log.d(TAG, "svgX=" + svgX + " svgY=" + svgY);

        String bestId   = null;
        float  bestArea = Float.MAX_VALUE;
        for (Map.Entry<String, RectF> e : deviceBoundsMap.entrySet()) {

            RectF expanded = new RectF(e.getValue());
            expanded.inset(-TAP_TOLERANCE, -TAP_TOLERANCE);

            if (expanded.contains(svgX, svgY)) {

                float area = expanded.width() * expanded.height();
                if (area < bestArea) {
                    bestArea = area;
                    bestId = e.getKey();
                }
            }
        }

        if (bestId != null) {
            onDeviceClicked(bestId);
        } else if (selectedDeviceId != null) {
            restoreColor(selectedDeviceId, selectedOriginalFill);
            selectedDeviceId     = null;
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

        // Toggle same device
        if (deviceId.equals(selectedDeviceId)) {
            restoreColor(deviceId, selectedOriginalFill);
            selectedDeviceId     = null;
            selectedOriginalFill = null;
            reRender();
            Toast.makeText(requireContext(), "Deselected: " + deviceId, Toast.LENGTH_SHORT).show();
            return;
        }

        // Select → red
        Element el = findById(svgDocument.getDocumentElement(), deviceId);
        if (el != null) {
            selectedOriginalFill = el.getAttribute("fill");
            el.setAttribute("fill", "#ff0000");
        }
        selectedDeviceId = deviceId;
        reRender();

        Toast.makeText(requireContext(), "Device: " + deviceId, Toast.LENGTH_SHORT).show();
    }

    private void restoreColor(String deviceId, String originalFill) {
        Element el = findById(svgDocument.getDocumentElement(), deviceId);
        if (el != null) {
            String fill = (originalFill != null && !originalFill.isEmpty())
                    ? originalFill : "transparent";
            el.setAttribute("fill", fill);
        }
    }
    private float[] touchToSvg(float touchX, float touchY) {
        Matrix inv = new Matrix();
        matrix.invert(inv);
        float[] pts = {touchX, touchY};
        inv.mapPoints(pts);

        float dW = binding.svgView.getDrawable() != null
                ? binding.svgView.getDrawable().getIntrinsicWidth()
                : binding.svgView.getWidth();
        float dH = binding.svgView.getDrawable() != null
                ? binding.svgView.getDrawable().getIntrinsicHeight()
                : binding.svgView.getHeight();

        return new float[]{
                vbX + (pts[0] / dW) * vbW,
                vbY + (pts[1] / dH) * vbH
        };
    }

    private void reRender() {
        if (binding == null || svgDocument == null) return;
        try {
            StringWriter sw = new StringWriter();
            TransformerFactory.newInstance().newTransformer()
                    .transform(new DOMSource(svgDocument), new StreamResult(sw));
            SVG             svg = SVG.getFromString(sw.toString());
            PictureDrawable d   = new PictureDrawable(svg.renderToPicture());
            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(d);
            binding.svgView.setImageMatrix(matrix); // preserve current pan/zoom
        } catch (Exception e) {
            Log.e(TAG, "reRender error: " + e.getMessage());
        }
    }

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
    private void setupZoom() {
        binding.svgView.setScaleType(ImageView.ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        float sf = d.getScaleFactor(), cur = getScale(), ns = cur * sf;
                        if (ns < minZoom)  sf = minZoom / cur;
                        if (ns > MAX_ZOOM) sf = MAX_ZOOM / cur;
                        matrix.postScale(sf, sf, d.getFocusX(), d.getFocusY());
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        float cur    = getScale();
                        float target = (cur > minZoom + 0.5f) ? minZoom : DOUBLE_TAP_ZOOM;
                        matrix.postScale(target/cur, target/cur, e.getX(), e.getY());
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        handleSvgClick(e.getX(), e.getY());
                        return true;
                    }
                });

        binding.svgView.setOnTouchListener((v, event) -> {
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
                        matrix.postTranslate(
                                event.getX() - lastTouchX,
                                event.getY() - lastTouchY);
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
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
        if (binding.svgView.getDrawable() == null) return;
        matrix.getValues(matrixValues);
        float scale = matrixValues[Matrix.MSCALE_X];
        float tX    = matrixValues[Matrix.MTRANS_X];
        float tY    = matrixValues[Matrix.MTRANS_Y];
        if (scale < minZoom)  scale = minZoom;
        if (scale > MAX_ZOOM) scale = MAX_ZOOM;
        float dW = binding.svgView.getDrawable().getIntrinsicWidth()  * scale;
        float dH = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();
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