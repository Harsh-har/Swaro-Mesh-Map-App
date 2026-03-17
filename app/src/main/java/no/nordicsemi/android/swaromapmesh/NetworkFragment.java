package no.nordicsemi.android.swaromapmesh;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.OverScroller;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import dagger.hilt.android.AndroidEntryPoint;
import no.nordicsemi.android.swaromapmesh.ble.ScannerActivity;
import no.nordicsemi.android.swaromapmesh.databinding.FragmentNetworkBinding;
import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;

@AndroidEntryPoint
public class NetworkFragment extends Fragment {

    private static final String TAG = "NetworkFragment";
    private static final float MAX_ZOOM = 10f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float TAP_TOLERANCE = 2f;
    private static final long ANIMATION_DURATION = 280L;
    private static final int FLING_DURATION = 2000;

    private FragmentNetworkBinding binding;
    private SharedViewModel mViewModel;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Device data class
    private static class DeviceInfo {
        String id;
        Element element;
        RectF bounds;
        String elementId;
        String originalColor;

        DeviceInfo(String id, Element element, RectF bounds, String elementId) {
            this.id = id;
            this.element = element;
            this.bounds = bounds;
            this.elementId = elementId;
            this.originalColor = null;
        }
    }

    // Zoom/Pan matrix
    private final Matrix matrix = new Matrix();
    private final float[] matrixValues = new float[9];
    private float minZoom = 1f;
    private float lastTouchX, lastTouchY;
    private boolean isDragging = false;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    // Gesture detectors
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private OverScroller scroller;
    private VelocityTracker velocityTracker;
    private ValueAnimator flingAnimator;
    private ValueAnimator zoomAnimator;

    // SVG state
    private SVG currentSvg;
    private Document svgDocument;
    private final Map<String, DeviceInfo> deviceMap = new LinkedHashMap<>();
    private String selectedDeviceId;

    // ViewBox
   // 200 0 800 640
    private float vbX = 200f, vbY = 0f, vbW = 800f, vbH = 640f;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentNetworkBinding.inflate(inflater, container, false);
        mViewModel = new ViewModelProvider(requireActivity()).get(SharedViewModel.class);

        setupZoomAndPan();

        // Show placeholder initially
        showPlaceholder(true);

        // Load default SVG from assets
        loadSVGFromAssets("officetest.svg");

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Observe ViewModel for URI changes
        mViewModel.getSvgUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) {
                Log.d(TAG, "URI received: " + uri.toString());
                loadSvg(uri);
            }
        });
    }

    private void loadSVGFromAssets(String assetFileName) {
        showLoading(true);
        Log.d(TAG, "Loading SVG from assets: " + assetFileName);

        executorService.execute(() -> {
            try {
                // Check if file exists in assets
                String[] assets = requireContext().getAssets().list("");
                boolean fileFound = false;
                if (assets != null) {
                    for (String asset : assets) {
                        if (asset.equals(assetFileName)) {
                            fileFound = true;
                            break;
                        }
                    }
                }

                if (!fileFound) {
                    mainHandler.post(() -> {
                        showLoading(false);
                        showPlaceholder(true);
                        Toast.makeText(requireContext(),
                                "SVG file not found in assets: " + assetFileName,
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // Pass 1: parse DOM for device detection
                InputStream is1 = requireContext().getAssets().open(assetFileName);
                Document document = parseDocument(is1);
                if (is1 != null) is1.close();

                // Parse viewBox
                if (document != null) {
                    parseViewBox(document);
                }

                // Extract devices
                Map<String, DeviceInfo> devices = extractDevices(document);

                // Pass 2: render SVG
                InputStream is2 = requireContext().getAssets().open(assetFileName);
                SVG svg = SVG.getFromInputStream(is2);
                if (is2 != null) is2.close();

                mainHandler.post(() -> {
                    currentSvg = svg;
                    svgDocument = document;
                    deviceMap.clear();
                    deviceMap.putAll(devices);

                    renderSvg(svg);
                    showLoading(false);

                    if (deviceMap.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "No devices found in SVG",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(),
                                deviceMap.size() + " devices detected",
                                Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Devices found: " + deviceMap.keySet());
                    }
                });

            } catch (SVGParseException e) {
                Log.e(TAG, "SVG Parse Error", e);
                mainHandler.post(() -> {
                    showLoading(false);
                    showPlaceholder(true);
                    Toast.makeText(requireContext(),
                            "Invalid SVG file: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG from assets", e);
                mainHandler.post(() -> {
                    showLoading(false);
                    showPlaceholder(true);
                    Toast.makeText(requireContext(),
                            "Error loading SVG: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (flingAnimator != null) flingAnimator.cancel();
        if (zoomAnimator != null) zoomAnimator.cancel();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        executorService.shutdown();
        binding = null;
    }

    // ==================== UI STATE ====================

    private void showPlaceholder(boolean show) {
        if (binding == null) return;

        Log.d(TAG, "showPlaceholder: " + show);

        if (show) {
            binding.svgPlaceholder.setVisibility(View.VISIBLE);
            binding.svgView.setVisibility(View.GONE);
            binding.progressBar.setVisibility(View.GONE);
        } else {
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.svgView.setVisibility(View.VISIBLE);
        }
    }

    private void showLoading(boolean show) {
        if (binding == null) return;

        Log.d(TAG, "showLoading: " + show);

        if (show) {
            binding.progressBar.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.svgView.setVisibility(View.GONE);
        } else {
            binding.progressBar.setVisibility(View.GONE);
        }
    }

    // ==================== SVG LOADING ====================

    private void loadSvg(Uri uri) {
        showLoading(true);
        Log.d(TAG, "Loading SVG from URI: " + uri.toString());

        executorService.execute(() -> {
            try {
                // Parse SVG for rendering
                InputStream is1 = requireContext().getContentResolver().openInputStream(uri);
                if (is1 == null) {
                    mainHandler.post(() -> {
                        showLoading(false);
                        showPlaceholder(true);
                        Toast.makeText(requireContext(),
                                "Failed to open SVG file",
                                Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                SVG svg = SVG.getFromInputStream(is1);
                is1.close();

                // Parse DOM for device detection
                InputStream is2 = requireContext().getContentResolver().openInputStream(uri);
                Document document = parseDocument(is2);
                if (is2 != null) is2.close();

                // Parse viewBox
                if (document != null) {
                    parseViewBox(document);
                }

                // Extract devices
                Map<String, DeviceInfo> devices = extractDevices(document);

                mainHandler.post(() -> {
                    currentSvg = svg;
                    svgDocument = document;
                    deviceMap.clear();
                    deviceMap.putAll(devices);

                    renderSvg(svg);
                    showLoading(false);

                    if (deviceMap.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "No devices found in SVG",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(),
                                deviceMap.size() + " devices detected",
                                Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Devices found: " + deviceMap.keySet());
                    }
                });

            } catch (SVGParseException e) {
                Log.e(TAG, "SVG Parse Error", e);
                mainHandler.post(() -> {
                    showLoading(false);
                    showPlaceholder(true);
                    Toast.makeText(requireContext(),
                            "Invalid SVG file",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Error loading SVG", e);
                mainHandler.post(() -> {
                    showLoading(false);
                    showPlaceholder(true);
                    Toast.makeText(requireContext(),
                            "Error loading SVG: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private Document parseDocument(InputStream inputStream) {
        if (inputStream == null) return null;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);

            // Security features
            try {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (Exception ignored) {}

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));

            Document document = builder.parse(inputStream);
            document.getDocumentElement().normalize();
            return document;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing document", e);
            return null;
        }
    }

    private void parseViewBox(Document document) {
        String vb = document.getDocumentElement().getAttribute("viewBox");
        if (vb != null && !vb.isEmpty()) {
            String[] parts = vb.trim().split("[\\s,]+");
            if (parts.length == 4) {
                try {
                    vbX = Float.parseFloat(parts[0]);
                    vbY = Float.parseFloat(parts[1]);
                    vbW = Float.parseFloat(parts[2]);
                    vbH = Float.parseFloat(parts[3]);
                    Log.d(TAG, String.format("ViewBox: (%.2f, %.2f, %.2f, %.2f)", vbX, vbY, vbW, vbH));
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private Map<String, DeviceInfo> extractDevices(Document document) {
        Map<String, DeviceInfo> devices = new LinkedHashMap<>();
        if (document == null) return devices;

        try {
            // Find Devices group
            Element devicesGroup = findElementById(document.getDocumentElement(), "Devices");
            Element root = devicesGroup != null ? devicesGroup : document.getDocumentElement();

            // Collect all device elements
            List<Element> deviceElements = new ArrayList<>();
            collectDeviceElements(root, deviceElements);

            // Process each device
            for (Element element : deviceElements) {
                String id = element.getAttribute("id");
                if (id == null || id.isEmpty()) continue;

                RectF bounds = computeBounds(element);
                if (bounds == null || bounds.isEmpty()) continue;

                String elementId = extractElementId(element);
                devices.put(id, new DeviceInfo(id, element, bounds, elementId));
                Log.d(TAG, "Found device: " + id + " with bounds: " + bounds.toShortString());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error extracting devices", e);
        }

        return devices;
    }

    private void collectDeviceElements(Element element, List<Element> result) {
        String id = element.getAttribute("id");
        String tagName = element.getTagName().toLowerCase();

        // If element has an ID, consider it as a potential device
        if (id != null && !id.isEmpty()) {
            result.add(element);
        }

        // Always recurse for groups
        if ("g".equals(tagName)) {
            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element) {
                    collectDeviceElements((Element) child, result);
                }
            }
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

    private RectF computeBounds(Element element) {
        String tagName = element.getTagName().toLowerCase();

        switch (tagName) {
            case "g":
                return computeGroupBounds(element);
            case "rect":
                return computeRectBounds(element);
            case "circle":
                return computeCircleBounds(element);
            case "ellipse":
                return computeEllipseBounds(element);
            case "path":
                return computePathBounds(element);
            case "polygon":
            case "polyline":
                return computePolyBounds(element);
            case "line":
                return computeLineBounds(element);
            default:
                return null;
        }
    }

    private RectF computeGroupBounds(Element element) {
        RectF union = null;
        NodeList children = element.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                RectF bounds = computeBounds((Element) child);
                if (bounds != null) {
                    if (union == null) {
                        union = new RectF(bounds);
                    } else {
                        union.union(bounds);
                    }
                }
            }
        }

        return union;
    }

    private RectF computeRectBounds(Element element) {
        Float x = getFloatAttribute(element, "x");
        Float y = getFloatAttribute(element, "y");
        Float width = getFloatAttribute(element, "width");
        Float height = getFloatAttribute(element, "height");

        if (width == null || height == null || width <= 0 || height <= 0) return null;

        float xVal = x != null ? x : 0f;
        float yVal = y != null ? y : 0f;

        return new RectF(xVal, yVal, xVal + width, yVal + height);
    }

    private RectF computeCircleBounds(Element element) {
        Float cx = getFloatAttribute(element, "cx");
        Float cy = getFloatAttribute(element, "cy");
        Float r = getFloatAttribute(element, "r");

        if (r == null || r <= 0) return null;

        float cxVal = cx != null ? cx : 0f;
        float cyVal = cy != null ? cy : 0f;

        return new RectF(cxVal - r, cyVal - r, cxVal + r, cyVal + r);
    }

    private RectF computeEllipseBounds(Element element) {
        Float cx = getFloatAttribute(element, "cx");
        Float cy = getFloatAttribute(element, "cy");
        Float rx = getFloatAttribute(element, "rx");
        Float ry = getFloatAttribute(element, "ry");

        if (rx == null || ry == null) return null;

        float cxVal = cx != null ? cx : 0f;
        float cyVal = cy != null ? cy : 0f;

        return new RectF(cxVal - rx, cyVal - ry, cxVal + rx, cyVal + ry);
    }

    private RectF computePathBounds(Element element) {
        String d = element.getAttribute("d");
        return parsePathData(d);
    }

    private RectF computePolyBounds(Element element) {
        String points = element.getAttribute("points");
        return parsePoints(points);
    }

    private RectF computeLineBounds(Element element) {
        Float x1 = getFloatAttribute(element, "x1");
        Float y1 = getFloatAttribute(element, "y1");
        Float x2 = getFloatAttribute(element, "x2");
        Float y2 = getFloatAttribute(element, "y2");

        float x1Val = x1 != null ? x1 : 0f;
        float y1Val = y1 != null ? y1 : 0f;
        float x2Val = x2 != null ? x2 : 0f;
        float y2Val = y2 != null ? y2 : 0f;

        return new RectF(
                Math.min(x1Val, x2Val),
                Math.min(y1Val, y2Val),
                Math.max(x1Val, x2Val),
                Math.max(y1Val, y2Val)
        );
    }

    private RectF parsePathData(String data) {
        if (data == null || data.isEmpty()) return null;

        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();

        String cleaned = data.replaceAll("([MmLlHhVvCcSsQqTtAaZz])", " $1 ");
        String[] tokens = cleaned.trim().split("[\\s,]+");

        char currentCmd = 'M';
        int argIndex = 0;

        for (String token : tokens) {
            if (token.isEmpty()) continue;

            char firstChar = token.charAt(0);
            if (Character.isLetter(firstChar)) {
                currentCmd = firstChar;
                argIndex = 0;
                continue;
            }

            try {
                float value = Float.parseFloat(token);

                switch (currentCmd) {
                    case 'M': case 'L': case 'm': case 'l':
                    case 'C': case 'c': case 'S': case 's':
                    case 'Q': case 'q': case 'T': case 't':
                        if (argIndex % 2 == 0) xs.add(value); else ys.add(value);
                        argIndex++;
                        break;
                    case 'H':
                        xs.add(value);
                        argIndex++;
                        break;
                    case 'h':
                        argIndex++;
                        break;
                    case 'V':
                        ys.add(value);
                        argIndex++;
                        break;
                    case 'v':
                        argIndex++;
                        break;
                    case 'A': case 'a':
                        if (argIndex % 7 == 5) xs.add(value);
                        if (argIndex % 7 == 6) ys.add(value);
                        argIndex++;
                        break;
                    default:
                        if (argIndex % 2 == 0) xs.add(value); else ys.add(value);
                        argIndex++;
                        break;
                }
            } catch (NumberFormatException ignored) {}
        }

        if (xs.isEmpty() || ys.isEmpty()) return null;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (float x : xs) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
        }
        for (float y : ys) {
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        return new RectF(minX, minY, maxX, maxY);
    }

    private RectF parsePoints(String points) {
        if (points == null || points.isEmpty()) return null;

        String[] tokens = points.trim().split("[\\s,]+");
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();

        for (int i = 0; i < tokens.length - 1; i += 2) {
            try {
                xs.add(Float.parseFloat(tokens[i]));
                ys.add(Float.parseFloat(tokens[i + 1]));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {}
        }

        if (xs.isEmpty() || ys.isEmpty()) return null;

        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

        for (float x : xs) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
        }
        for (float y : ys) {
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }

        return new RectF(minX, minY, maxX, maxY);
    }

    private Float getFloatAttribute(Element element, String attr) {
        String value = element.getAttribute(attr);
        if (value == null || value.isEmpty()) return null;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String extractElementId(Element element) {
        NodeList metadataList = element.getElementsByTagName("metadata");
        if (metadataList.getLength() == 0) return null;

        Node metadata = metadataList.item(0);
        NodeList children = metadata.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "elementId".equalsIgnoreCase(child.getNodeName())) {
                String text = child.getTextContent();
                return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
            }
        }

        return null;
    }

    private void renderSvg(SVG svg) {
        try {
            Log.d(TAG, "Rendering SVG...");
            Picture picture = svg.renderToPicture();
            PictureDrawable drawable = new PictureDrawable(picture);

            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);

            // Make sure ImageView is visible
            binding.svgView.setVisibility(View.VISIBLE);
            binding.svgPlaceholder.setVisibility(View.GONE);
            binding.progressBar.setVisibility(View.GONE);

            Log.d(TAG, "SVG rendered, dimensions: " +
                    drawable.getIntrinsicWidth() + "x" + drawable.getIntrinsicHeight());

            // Use post to ensure view dimensions are available
            binding.svgView.post(() -> {
                fitToView();
                binding.svgView.invalidate();
                Log.d(TAG, "fitToView completed");
            });

        } catch (Exception e) {
            Log.e(TAG, "Error rendering SVG", e);
            showPlaceholder(true);
        }
    }

    private void fitToView() {
        if (binding == null || binding.svgView.getDrawable() == null) {
            Log.e(TAG, "fitToView: binding or drawable is null");
            return;
        }

        float dW = binding.svgView.getDrawable().getIntrinsicWidth();
        float dH = binding.svgView.getDrawable().getIntrinsicHeight();
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        Log.d(TAG, String.format("fitToView - drawable: %.2fx%.2f, view: %.2fx%.2f", dW, dH, vW, vH));

        if (dW <= 0 || dH <= 0 || vW <= 0 || vH <= 0) {
            Log.e(TAG, "fitToView: invalid dimensions");
            return;
        }

        minZoom = Math.min(vW / dW, vH / dH);
        Log.d(TAG, "minZoom: " + minZoom);

        matrix.reset();
        matrix.postScale(minZoom, minZoom);
        matrix.postTranslate(
                (vW - dW * minZoom) / 2f,
                (vH - dH * minZoom) / 2f
        );

        binding.svgView.setImageMatrix(matrix);
    }

    private void reRenderSvg() {
        if (svgDocument == null || currentSvg == null) return;

        try {
            String svgString = documentToString(svgDocument);
            SVG svg = SVG.getFromString(svgString);
            Picture picture = svg.renderToPicture();
            PictureDrawable drawable = new PictureDrawable(picture);

            binding.svgView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            binding.svgView.setImageDrawable(drawable);
            binding.svgView.setImageMatrix(matrix);
            binding.svgView.invalidate();

        } catch (Exception e) {
            Log.e(TAG, "Error re-rendering SVG", e);
        }
    }

    private String documentToString(Document document) {
        if (document == null) return "";

        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error converting document to string", e);
            return "";
        }
    }

    // ==================== TOUCH HANDLING ====================

    private void setupZoomAndPan() {
        binding.svgView.setScaleType(ImageView.ScaleType.MATRIX);
        scroller = new OverScroller(requireContext(), new DecelerateInterpolator(2.5f));

        scaleDetector = new ScaleGestureDetector(requireContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        float scaleFactor = detector.getScaleFactor();
                        float currentScale = getScale();
                        float newScale = currentScale * scaleFactor;

                        if (newScale < minZoom) newScale = minZoom;
                        if (newScale > MAX_ZOOM) newScale = MAX_ZOOM;

                        float effectiveScale = newScale / currentScale;
                        matrix.postScale(effectiveScale, effectiveScale,
                                detector.getFocusX(), detector.getFocusY());
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(requireContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        float currentScale = getScale();
                        float targetScale = (currentScale > minZoom + 0.5f) ? minZoom : DOUBLE_TAP_ZOOM;
                        animateZoomTo(targetScale, e.getX(), e.getY());
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        handleSvgTap(e.getX(), e.getY());
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2,
                                           float velocityX, float velocityY) {
                        startFling(velocityX, velocityY);
                        return true;
                    }
                });

        binding.svgView.setOnTouchListener(this::handleTouch);
    }

    private boolean handleTouch(View v, MotionEvent event) {
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (flingAnimator != null) flingAnimator.cancel();
                if (zoomAnimator != null) zoomAnimator.cancel();
                scroller.forceFinished(true);

                activePointerId = event.getPointerId(0);
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                isDragging = true;
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                isDragging = false;
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (isDragging && !scaleDetector.isInProgress()) {
                    int pointerIndex = event.findPointerIndex(activePointerId);
                    if (pointerIndex == -1) {
                        activePointerId = event.getPointerId(0);
                        break;
                    }

                    float curX = event.getX(pointerIndex);
                    float curY = event.getY(pointerIndex);
                    float dx = curX - lastTouchX;
                    float dy = curY - lastTouchY;

                    if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) {
                        matrix.postTranslate(dx, dy);
                        clampMatrix();
                        binding.svgView.setImageMatrix(matrix);
                    }

                    lastTouchX = curX;
                    lastTouchY = curY;
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
                isDragging = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int pointerIndex = event.getActionIndex();
                int pointerId = event.getPointerId(pointerIndex);
                if (pointerId == activePointerId) {
                    int newPointerIndex = pointerIndex == 0 ? 1 : 0;
                    activePointerId = event.getPointerId(newPointerIndex);
                    lastTouchX = event.getX(newPointerIndex);
                    lastTouchY = event.getY(newPointerIndex);
                }
                break;
            }
        }

        return true;
    }

    // ==================== TAP HANDLING ====================

    private void handleSvgTap(float touchX, float touchY) {
        if (svgDocument == null || deviceMap.isEmpty()) return;

        float[] svgCoords = touchToSvgCoordinates(touchX, touchY);
        float svgX = svgCoords[0];
        float svgY = svgCoords[1];

        Log.d(TAG, String.format("SVG coordinates: (%.2f, %.2f)", svgX, svgY));

        String deviceId = findDeviceAt(svgX, svgY);

        if (deviceId != null) {
            onDeviceSelected(deviceId);
        } else {
            deselectCurrentDevice();
        }
    }

    private float[] touchToSvgCoordinates(float touchX, float touchY) {
        Matrix inverse = new Matrix();
        matrix.invert(inverse);
        float[] points = {touchX, touchY};
        inverse.mapPoints(points);

        float dW = binding.svgView.getDrawable() != null ?
                binding.svgView.getDrawable().getIntrinsicWidth() : 1;
        float dH = binding.svgView.getDrawable() != null ?
                binding.svgView.getDrawable().getIntrinsicHeight() : 1;

        float svgX = vbX + (points[0] / dW) * vbW;
        float svgY = vbY + (points[1] / dH) * vbH;

        return new float[]{svgX, svgY};
    }

    private String findDeviceAt(float svgX, float svgY) {
        String bestId = null;
        float smallestArea = Float.MAX_VALUE;

        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            RectF bounds = entry.getValue().bounds;
            RectF expanded = new RectF(bounds);
            expanded.inset(-TAP_TOLERANCE, -TAP_TOLERANCE);

            if (expanded.contains(svgX, svgY)) {
                float area = bounds.width() * bounds.height();
                if (area < smallestArea) {
                    smallestArea = area;
                    bestId = entry.getKey();
                }
            }
        }

        return bestId;
    }

    private void onDeviceSelected(String deviceId) {
        if (deviceId.equals(selectedDeviceId)) {
            deselectCurrentDevice();
            Toast.makeText(requireContext(), "Deselected: " + deviceId, Toast.LENGTH_SHORT).show();
            return;
        }

        // Special navigation for l_10
        if ("l_10".equals(deviceId)) {
            Intent intent = new Intent(requireContext(), ScannerActivity.class);
            intent.putExtra("device_id", deviceId);
            startActivity(intent);
            return;
        }

        // Deselect previous
        deselectCurrentDevice();

        // Select new device
        selectDevice(deviceId);

        // Show toast with metadata
        DeviceInfo device = deviceMap.get(deviceId);
        String message = device != null && device.elementId != null ?
                "Device: " + deviceId + " | Element ID: " + device.elementId :
                "Device: " + deviceId;

        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show();
        Log.d(TAG, "Selected: " + deviceId);
    }

    private void selectDevice(String deviceId) {
        DeviceInfo device = deviceMap.get(deviceId);
        if (device == null) return;

        // Store original color if not already stored
        if (device.originalColor == null) {
            device.originalColor = getDeviceColor(device.element);
        }

        // Highlight device
        setDeviceColor(device.element, "#ff0000");
        selectedDeviceId = deviceId;
        reRenderSvg();
    }

    private void deselectCurrentDevice() {
        if (selectedDeviceId != null) {
            DeviceInfo device = deviceMap.get(selectedDeviceId);
            if (device != null && device.originalColor != null) {
                setDeviceColor(device.element, device.originalColor);
            }
            selectedDeviceId = null;
            reRenderSvg();
        }
    }

    private String getDeviceColor(Element element) {
        String fill = element.getAttribute("fill");
        if (fill != null && !fill.isEmpty()) return fill;

        // Check children for fill
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                fill = ((Element) child).getAttribute("fill");
                if (fill != null && !fill.isEmpty()) return fill;
            }
        }

        return "#ffbb00"; // Default amber color
    }

    private void setDeviceColor(Element element, String color) {
        String tagName = element.getTagName().toLowerCase();

        if (!"g".equals(tagName)) {
            element.setAttribute("fill", color);
            return;
        }

        // For groups, set color on all children
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element) {
                ((Element) child).setAttribute("fill", color);
            }
        }
    }

    // ==================== ZOOM & PAN UTILITIES ====================

    private float getScale() {
        matrix.getValues(matrixValues);
        return matrixValues[Matrix.MSCALE_X];
    }

    private void clampMatrix() {
        if (binding == null || binding.svgView.getDrawable() == null) return;

        matrix.getValues(matrixValues);

        float scale = matrixValues[Matrix.MSCALE_X];
        float transX = matrixValues[Matrix.MTRANS_X];
        float transY = matrixValues[Matrix.MTRANS_Y];

        // Clamp scale
        if (scale < minZoom) scale = minZoom;
        if (scale > MAX_ZOOM) scale = MAX_ZOOM;

        float dW = binding.svgView.getDrawable().getIntrinsicWidth() * scale;
        float dH = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        // Clamp translation
        float minTransX = (dW < vW) ? (vW - dW) / 2f : Math.min(0, vW - dW);
        float maxTransX = (dW < vW) ? (vW - dW) / 2f : 0;
        float minTransY = (dH < vH) ? (vH - dH) / 2f : Math.min(0, vH - dH);
        float maxTransY = (dH < vH) ? (vH - dH) / 2f : 0;

        transX = Math.max(minTransX, Math.min(maxTransX, transX));
        transY = Math.max(minTransY, Math.min(maxTransY, transY));

        matrixValues[Matrix.MSCALE_X] = scale;
        matrixValues[Matrix.MSCALE_Y] = scale;
        matrixValues[Matrix.MTRANS_X] = transX;
        matrixValues[Matrix.MTRANS_Y] = transY;
        matrix.setValues(matrixValues);
    }

    private void animateZoomTo(float targetScale, float pivotX, float pivotY) {
        if (zoomAnimator != null) zoomAnimator.cancel();

        float startScale = getScale();

        zoomAnimator = ValueAnimator.ofFloat(startScale, targetScale);
        zoomAnimator.setDuration(ANIMATION_DURATION);
        zoomAnimator.setInterpolator(new DecelerateInterpolator(2f));
        zoomAnimator.addUpdateListener(animation -> {
            if (binding == null) return;
            float newScale = (float) animation.getAnimatedValue();
            float scaleFactor = newScale / getScale();
            matrix.postScale(scaleFactor, scaleFactor, pivotX, pivotY);
            clampMatrix();
            binding.svgView.setImageMatrix(matrix);
        });
        zoomAnimator.start();
    }

    private void startFling(float velocityX, float velocityY) {
        if (binding == null || binding.svgView.getDrawable() == null) return;

        matrix.getValues(matrixValues);
        int startX = (int) matrixValues[Matrix.MTRANS_X];
        int startY = (int) matrixValues[Matrix.MTRANS_Y];
        float scale = matrixValues[Matrix.MSCALE_X];

        float dW = binding.svgView.getDrawable().getIntrinsicWidth() * scale;
        float dH = binding.svgView.getDrawable().getIntrinsicHeight() * scale;
        float vW = binding.svgView.getWidth();
        float vH = binding.svgView.getHeight();

        int minX = (dW < vW) ? (int) ((vW - dW) / 2f) : (int) (vW - dW);
        int maxX = (dW < vW) ? (int) ((vW - dW) / 2f) : 0;
        int minY = (dH < vH) ? (int) ((vH - dH) / 2f) : (int) (vH - dH);
        int maxY = (dH < vH) ? (int) ((vH - dH) / 2f) : 0;

        scroller.fling(
                startX, startY,
                (int) velocityX, (int) velocityY,
                minX, maxX,
                minY, maxY,
                0, 0
        );

        if (flingAnimator != null) flingAnimator.cancel();

        flingAnimator = ValueAnimator.ofFloat(0f, 1f);
        flingAnimator.setDuration(FLING_DURATION);
        flingAnimator.addUpdateListener(animation -> {
            if (binding == null) {
                animation.cancel();
                return;
            }
            if (scroller.computeScrollOffset()) {
                matrix.getValues(matrixValues);
                matrixValues[Matrix.MTRANS_X] = scroller.getCurrX();
                matrixValues[Matrix.MTRANS_Y] = scroller.getCurrY();
                matrix.setValues(matrixValues);
                clampMatrix();
                binding.svgView.setImageMatrix(matrix);
            } else {
                animation.cancel();
            }
        });
        flingAnimator.start();
    }
}