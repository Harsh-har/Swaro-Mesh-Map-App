package no.nordicsemi.android.swaromapmesh;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class ColorWheelView extends View {

    // Ring thickness
    private static final float RING_THICKNESS_FRACTION = 30f / 110f;

    // Thumb
    private static final float THUMB_RADIUS_PX = 15f;

    // Wheel colors
    private static final float[] SWEEP_POSITIONS = {
            0.0f,
            6.228f / 360f,
            46.800f / 360f,
            88.421f / 360f,
            136.111f / 360f,
            183.054f / 360f,
            230.379f / 360f,
            271.595f / 360f,
            314.519f / 360f,
            1.0f
    };

    private static final int[] SWEEP_COLORS = {
            0xFFFF001E,
            0xFFFF0000,
            0xFFFFAA00,
            0xFFFFFF00,
            0xFF00FF00,
            0xFF00FFEA,
            0xFF0042FF,
            0xFFC200FF,
            0xFFFF00FF,
            0xFFFF001E
    };

    // Paints
    private final Paint huePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint thumbFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Geometry
    private float cx, cy;
    private float outerRadius;
    private float innerRadius;
    private float thumbRadius;

    // Bitmap
    private Bitmap bitmap;

    // Thumb position
    private float thumbX, thumbY;
    private boolean thumbReady = false;

    // Listener
    public interface OnColorPickedListener {
        void onColorPicked(int color);
    }

    private OnColorPickedListener colorPickedListener;

    public void setOnColorPickedListener(OnColorPickedListener listener) {
        this.colorPickedListener = listener;
    }

    // Constructors
    public ColorWheelView(Context context) {
        super(context);
        init();
    }

    public ColorWheelView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ColorWheelView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {

        setLayerType(LAYER_TYPE_SOFTWARE, null);

        holePaint.setColor(Color.TRANSPARENT);
        holePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

        thumbFill.setStyle(Paint.Style.FILL);

        thumbStroke.setStyle(Paint.Style.STROKE);
        thumbStroke.setStrokeWidth(4f);
        thumbStroke.setColor(Color.WHITE);
    }

    // Size
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        cx = w / 2f;
        cy = h / 2f;

        outerRadius = Math.min(w, h) / 2f;
        innerRadius = outerRadius * (1f - RING_THICKNESS_FRACTION);

        thumbRadius = outerRadius * (THUMB_RADIUS_PX / 110f);

        buildShaders();
        rebuildBitmap(w, h);

        if (!thumbReady) {

            // Start thumb at TOP center
            float midRadius = (outerRadius + innerRadius) / 2f;

            thumbX = cx;
            thumbY = cy - midRadius;

            thumbReady = true;

            notifyListener();
        }
    }

    // Shaders
    private void buildShaders() {

        SweepGradient sweep = new SweepGradient(
                cx,
                cy,
                SWEEP_COLORS,
                SWEEP_POSITIONS
        );

        Matrix matrix = new Matrix();

        // Red at top
        matrix.setRotate(-90f, cx, cy);

        sweep.setLocalMatrix(matrix);

        huePaint.setShader(sweep);

        RadialGradient radial = new RadialGradient(
                cx,
                cy,
                outerRadius,
                new int[]{Color.WHITE, 0x00FFFFFF},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP
        );

        satPaint.setShader(radial);
    }
    /**
     * Returns thumb angle in degrees (0–360), 0 = top (12 o'clock), clockwise.
     */
    public int getThumbAngle() {
        float dx = thumbX - cx;
        float dy = thumbY - cy;
        double angleRad = Math.atan2(dy, dx);
        int angleDeg = (int) Math.round(Math.toDegrees(angleRad) + 90);
        if (angleDeg < 0)    angleDeg += 360;
        if (angleDeg >= 360) angleDeg -= 360;
        return angleDeg;
    }
    // Build wheel bitmap
    private void rebuildBitmap(int w, int h) {

        if (bitmap != null) {
            bitmap.recycle();
        }

        bitmap = Bitmap.createBitmap(
                w,
                h,
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(bitmap);

        // Color wheel
        canvas.drawCircle(cx, cy, outerRadius, huePaint);

        // Saturation
        canvas.drawCircle(cx, cy, outerRadius, satPaint);

        // Transparent center
        canvas.drawCircle(cx, cy, innerRadius, holePaint);
    }

    // Draw
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (bitmap == null) return;

        canvas.drawBitmap(bitmap, 0, 0, null);

        int selectedColor = sampleColor(thumbX, thumbY);

        thumbFill.setColor(selectedColor);

        // Thumb fill
        canvas.drawCircle(
                thumbX,
                thumbY,
                thumbRadius,
                thumbFill
        );

        // Thumb border
        canvas.drawCircle(
                thumbX,
                thumbY,
                thumbRadius,
                thumbStroke
        );
    }

    // Touch
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {

            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:

                float[] snapped = clampToRing(x, y);

                thumbX = snapped[0];
                thumbY = snapped[1];

                notifyListener();

                invalidate();

                return true;

            case MotionEvent.ACTION_UP:
                performClick();
                return true;
        }

        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    // Clamp touch to ring center
    private float[] clampToRing(float x, float y) {

        float dx = x - cx;
        float dy = y - cy;

        float len = (float) Math.sqrt(dx * dx + dy * dy);

        if (len == 0f) len = 1f;

        float midRadius = (outerRadius + innerRadius) / 2f;

        return new float[]{
                cx + (dx / len) * midRadius,
                cy + (dy / len) * midRadius
        };
    }

    // Sample selected color
    private int sampleColor(float x, float y) {

        if (bitmap == null) {
            return Color.WHITE;
        }

        int px = Math.round(x);
        int py = Math.round(y);

        if (px < 0 || py < 0 ||
                px >= bitmap.getWidth() ||
                py >= bitmap.getHeight()) {

            return Color.WHITE;
        }

        return bitmap.getPixel(px, py);
    }

    // Notify listener
    private void notifyListener() {

        if (colorPickedListener != null) {
            colorPickedListener.onColorPicked(
                    sampleColor(thumbX, thumbY)
            );
        }
    }
}
