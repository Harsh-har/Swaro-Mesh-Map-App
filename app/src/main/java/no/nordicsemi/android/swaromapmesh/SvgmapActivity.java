//package no.nordicsemi.android.swaromapmesh;
//
//import android.graphics.Matrix;
//import android.graphics.Picture;
//import android.graphics.PointF;
//import android.graphics.drawable.PictureDrawable;
//import android.os.Bundle;
//import android.view.Menu;
//import android.view.MenuItem;
//import android.view.MotionEvent;
//import android.view.ScaleGestureDetector;
//import android.view.View;
//import android.view.animation.Animation;
//import android.view.animation.AnimationUtils;
//import android.widget.ImageView;
//import android.widget.FrameLayout;
//
//import androidx.annotation.NonNull;
//import androidx.appcompat.app.AppCompatActivity;
//import androidx.fragment.app.Fragment;
//import androidx.fragment.app.FragmentTransaction;
//import androidx.lifecycle.ViewModelProvider;
//
//import com.caverock.androidsvg.SVG;
//import com.google.android.material.bottomnavigation.BottomNavigationView;
//import com.google.android.material.navigation.NavigationBarView;
//
//import java.io.BufferedReader;
//import java.io.InputStream;
//import java.io.InputStreamReader;
//
//import dagger.hilt.android.AndroidEntryPoint;
//import no.nordicsemi.android.swaromapmesh.databinding.ActivitySvgmapBinding;
//import no.nordicsemi.android.swaromapmesh.viewmodels.SharedViewModel;
//
//@AndroidEntryPoint
//public class SvgmapActivity extends AppCompatActivity implements
//        NavigationBarView.OnItemSelectedListener,
//        NavigationBarView.OnItemReselectedListener {
//
//    private static final String CURRENT_FRAGMENT = "CURRENT_FRAGMENT";
//    private static final String SELECTED_DEVICE_ID = "SELECTED_DEVICE_ID";
//    private static final String MATRIX_STATE = "MATRIX_STATE";
//    private static final String IS_SVG_VISIBLE = "IS_SVG_VISIBLE";
//
//    private SharedViewModel mViewModel;
//    private ActivitySvgmapBinding binding;
//
//    // SVG Map related
//    private ImageView imageView;
//    private FrameLayout fragmentContainer;
//    private String selectedDeviceId = null;
//
//    private String[] deviceIds = {
//            "l_1","l_2","l_3","l_4","l_5","l_6",
//            "l_7","l_8","l_9","l_10"
//    };
//
//    private Matrix matrix = new Matrix();
//    private float[] matrixValues = new float[9];
//
//    private float minScale = 1.0f;
//    private float maxScale = 4.0f;
//    private float initialScale = 1.0f;
//
//    private ScaleGestureDetector scaleDetector;
//    private float lastX, lastY;
//    private boolean isDragging = false;
//    private boolean isInitialLoad = true;
//    private PointF lastFocusPoint = new PointF();
//    private boolean isSvgVisible = true; // Default true
//
//    // Fragment references
//    private NetworkFragment mNetworkFragment; // Ab iski zaroorat nahi, but rakh sakte hain
//    private DevicesFilterActivity mDevicesFilterFragment;
//    private GroupsFragment mGroupsFragment;
//    private ProxyFilterFragment mProxyFilterFragment;
//    private Fragment mSettingsFragment;
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        setTheme(R.style.AppTheme);
//        super.onCreate(savedInstanceState);
//
//        mViewModel = new ViewModelProvider(this).get(SharedViewModel.class);
//
//        binding = ActivitySvgmapBinding.inflate(getLayoutInflater());
//        setContentView(binding.getRoot());
//
//        setSupportActionBar(binding.toolbar);
//        if (getSupportActionBar() != null) {
//            getSupportActionBar().setTitle(R.string.app_name);
//        }
//
//        // Initialize views
//        imageView = binding.svgImageView;
//        fragmentContainer = binding.fragmentContainer;
//
//        imageView.setScaleType(ImageView.ScaleType.MATRIX);
//        imageView.setImageMatrix(matrix);
//
//        // Restore state if available
//        if (savedInstanceState != null) {
//            selectedDeviceId = savedInstanceState.getString(SELECTED_DEVICE_ID);
//            isSvgVisible = savedInstanceState.getBoolean(IS_SVG_VISIBLE, true);
//
//            float[] savedMatrix = savedInstanceState.getFloatArray(MATRIX_STATE);
//            if (savedMatrix != null) {
//                matrix.setValues(savedMatrix);
//                isInitialLoad = false;
//            }
//        }
//
//        // Initialize fragments (except NetworkFragment - ab SVG default hai)
//        initializeFragments(savedInstanceState);
//
//        // Setup bottom navigation
//        BottomNavigationView bottomNavigationView = binding.bottomNavigationView;
//        bottomNavigationView.setOnItemSelectedListener(this);
//        bottomNavigationView.setOnItemReselectedListener(this);
//
//        // Set initial state - SVG visible, no fragment visible
//        if (savedInstanceState == null) {
//            // Koi fragment select nahi, SVG visible rahega
//            bottomNavigationView.setSelectedItemId(R.id.action_network); // Network option, but we'll handle it specially
//            hideAllFragments();
//            showSvgOnly();
//        } else {
//            int selectedItem = savedInstanceState.getInt(CURRENT_FRAGMENT, R.id.action_network);
//            bottomNavigationView.setSelectedItemId(selectedItem);
//
//            if (selectedItem == R.id.action_network) {
//                showSvgOnly();
//            } else {
//                showFragment(selectedItem);
//            }
//        }
//
//        // Setup gesture detector
//        scaleDetector = new ScaleGestureDetector(this, new ScaleListener());
//
//        // Load SVG
//        loadSVG();
//
//        // Setup touch listener
//        setupTouchListener();
//    }
//
//    private void initializeFragments(Bundle savedInstanceState) {
//        if (savedInstanceState == null) {
//            // Create new fragments (except NetworkFragment)
//            mDevicesFilterFragment = new DevicesFilterActivity();
//            mGroupsFragment = new GroupsFragment();
//            mProxyFilterFragment = new ProxyFilterFragment();
//            mSettingsFragment = new SettingsFragment();
//
//            // Add fragments to container (initially hidden)
//            getSupportFragmentManager().beginTransaction()
//                    .add(R.id.fragment_container, mDevicesFilterFragment, "DevicesFilterFragment")
//                    .add(R.id.fragment_container, mGroupsFragment, "GroupsFragment")
//                    .add(R.id.fragment_container, mProxyFilterFragment, "ProxyFilterFragment")
//                    .add(R.id.fragment_container, mSettingsFragment, "SettingsFragment")
//                    .hide(mDevicesFilterFragment)
//                    .hide(mGroupsFragment)
//                    .hide(mProxyFilterFragment)
//                    .hide(mSettingsFragment)
//                    .commit();
//        } else {
//            // Find existing fragments
//            mDevicesFilterFragment = (DevicesFilterActivity) getSupportFragmentManager()
//                    .findFragmentByTag("DevicesFilterFragment");
//            mGroupsFragment = (GroupsFragment) getSupportFragmentManager()
//                    .findFragmentByTag("GroupsFragment");
//            mProxyFilterFragment = (ProxyFilterFragment) getSupportFragmentManager()
//                    .findFragmentByTag("ProxyFilterFragment");
//            mSettingsFragment = getSupportFragmentManager()
//                    .findFragmentByTag("SettingsFragment");
//        }
//    }
//
//    private void hideAllFragments() {
//        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
//
//        if (mDevicesFilterFragment != null) ft.hide(mDevicesFilterFragment);
//        if (mGroupsFragment != null) ft.hide(mGroupsFragment);
//        if (mProxyFilterFragment != null) ft.hide(mProxyFilterFragment);
//        if (mSettingsFragment != null) ft.hide(mSettingsFragment);
//
//        ft.commit();
//    }
//
//    private void showSvgOnly() {
//        // Hide all fragments
//        hideAllFragments();
//
//        // Show SVG
//        imageView.setVisibility(View.VISIBLE);
//        isSvgVisible = true;
//
//        // Update title if needed
//        if (getSupportActionBar() != null) {
//            getSupportActionBar().setTitle(R.string.app_name);
//        }
//    }
//
//    private void hideSvg() {
//        imageView.setVisibility(View.GONE);
//        isSvgVisible = false;
//    }
//
//    private void setupTouchListener() {
//        imageView.setOnTouchListener((v, event) -> {
//            // Only handle touch if SVG is visible
//            if (!isSvgVisible) return false;
//
//            scaleDetector.onTouchEvent(event);
//
//            switch (event.getActionMasked()) {
//                case MotionEvent.ACTION_DOWN:
//                    lastX = event.getX();
//                    lastY = event.getY();
//                    isDragging = true;
//                    break;
//
//                case MotionEvent.ACTION_MOVE:
//                    if (!scaleDetector.isInProgress() && isDragging) {
//                        float dx = event.getX() - lastX;
//                        float dy = event.getY() - lastY;
//
//                        matrix.postTranslate(dx, dy);
//                        checkAndFixBoundaries();
//                        imageView.setImageMatrix(matrix);
//
//                        lastX = event.getX();
//                        lastY = event.getY();
//                    }
//                    break;
//
//                case MotionEvent.ACTION_UP:
//                    isDragging = false;
//
//                    if (!scaleDetector.isInProgress()) {
//                        handleDeviceTap(event.getX(), event.getY());
//                    }
//                    break;
//            }
//            return true;
//        });
//    }
//
//    private void handleDeviceTap(float x, float y) {
//        String clickedId = detectTappedDevice(x, y);
//
//        if (clickedId != null) {
//            if (clickedId.equals(selectedDeviceId)) {
//                selectedDeviceId = null;
//            } else {
//                selectedDeviceId = clickedId;
//            }
//            loadSVG();
//
//            // Update ViewModel with selected device
////            mViewModel.setSelectedDeviceId(selectedDeviceId);
//        }
//    }
//
//    private String detectTappedDevice(float x, float y) {
//        float width = imageView.getWidth();
//        float height = imageView.getHeight();
//
//        if (x < width/2 && y < height/3) {
//            return deviceIds[0];
//        } else if (x >= width/2 && y < height/3) {
//            return deviceIds[1];
//        } else if (x < width/2 && y < 2*height/3) {
//            return deviceIds[2];
//        } else if (x >= width/2 && y < 2*height/3) {
//            return deviceIds[3];
//        } else if (x < width/2 && y < height) {
//            return deviceIds[4];
//        } else if (x >= width/2 && y < height) {
//            return deviceIds[5];
//        } else if (x < width/2 && y < 4*height/3) {
//            return deviceIds[6];
//        } else if (x >= width/2 && y < 4*height/3) {
//            return deviceIds[7];
//        } else if (x < width/2 && y < 5*height/3) {
//            return deviceIds[8];
//        } else if (x >= width/2 && y < 5*height/3) {
//            return deviceIds[9];
//        }
//        return null;
//    }
//
//    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
//        @Override
//        public boolean onScaleBegin(ScaleGestureDetector detector) {
//            lastFocusPoint.set(detector.getFocusX(), detector.getFocusY());
//            return true;
//        }
//
//        @Override
//        public boolean onScale(ScaleGestureDetector detector) {
//            float scale = detector.getScaleFactor();
//
//            matrix.getValues(matrixValues);
//            float currentScale = matrixValues[Matrix.MSCALE_X];
//
//            float newScale = currentScale * scale;
//
//            if (newScale < minScale) {
//                scale = minScale / currentScale;
//            } else if (newScale > maxScale) {
//                scale = maxScale / currentScale;
//            }
//
//            float focusX = detector.getFocusX();
//            float focusY = detector.getFocusY();
//
//            matrix.postScale(scale, scale, focusX, focusY);
//            checkAndFixBoundaries();
//            imageView.setImageMatrix(matrix);
//
//            lastFocusPoint.set(focusX, focusY);
//            return true;
//        }
//
//        @Override
//        public void onScaleEnd(ScaleGestureDetector detector) {
//            super.onScaleEnd(detector);
//            checkAndFixBoundaries();
//            imageView.setImageMatrix(matrix);
//        }
//    }
//
//    private void checkAndFixBoundaries() {
//        if (imageView.getDrawable() == null) return;
//
//        matrix.getValues(matrixValues);
//        float scaleX = matrixValues[Matrix.MSCALE_X];
//        float scaleY = matrixValues[Matrix.MSCALE_Y];
//        float transX = matrixValues[Matrix.MTRANS_X];
//        float transY = matrixValues[Matrix.MTRANS_Y];
//
//        float imageWidth = imageView.getDrawable().getIntrinsicWidth() * scaleX;
//        float imageHeight = imageView.getDrawable().getIntrinsicHeight() * scaleY;
//
//        float viewWidth = imageView.getWidth();
//        float viewHeight = imageView.getHeight();
//
//        float minX, maxX, minY, maxY;
//
//        if (imageWidth <= viewWidth) {
//            minX = (viewWidth - imageWidth) / 2;
//            maxX = minX;
//        } else {
//            minX = viewWidth - imageWidth;
//            maxX = 0;
//            if (minX > maxX) {
//                float temp = minX;
//                minX = maxX;
//                maxX = temp;
//            }
//        }
//
//        if (imageHeight <= viewHeight) {
//            minY = (viewHeight - imageHeight) / 2;
//            maxY = minY;
//        } else {
//            minY = viewHeight - imageHeight;
//            maxY = 0;
//        }
//
//        float newTransX = transX;
//        float newTransY = transY;
//        boolean changed = false;
//
//        if (transX < minX) {
//            newTransX = minX;
//            changed = true;
//        } else if (transX > maxX) {
//            newTransX = maxX;
//            changed = true;
//        }
//
//        if (transY < minY) {
//            newTransY = minY;
//            changed = true;
//        } else if (transY > maxY) {
//            newTransY = maxY;
//            changed = true;
//        }
//
//        if (changed) {
//            matrix.postTranslate(newTransX - transX, newTransY - transY);
//        }
//    }
//
//    private void loadSVG() {
//        try {
//            InputStream inputStream = getAssets().open("Test_Map_dark.svg");
//
//            BufferedReader reader = new BufferedReader(
//                    new InputStreamReader(inputStream)
//            );
//
//            StringBuilder builder = new StringBuilder();
//            String line;
//
//            while ((line = reader.readLine()) != null) {
//                builder.append(line);
//            }
//
//            String svgContent = builder.toString();
//
//            for (String id : deviceIds) {
//                String color = id.equals(selectedDeviceId)
//                        ? "#00FF00"
//                        : "#fb0";
//
//                svgContent = svgContent.replaceAll(
//                        "id=\"" + id + "\" fill=\"#[^\"]*\"",
//                        "id=\"" + id + "\" fill=\"" + color + "\""
//                );
//            }
//
//            SVG svg = SVG.getFromString(svgContent);
//
//            int width = imageView.getWidth();
//            int height = imageView.getHeight();
//
//            if (width == 0 || height == 0) {
//                imageView.post(this::loadSVG);
//                return;
//            }
//
//            Picture picture = svg.renderToPicture(width, height);
//            PictureDrawable drawable = new PictureDrawable(picture);
//
//            imageView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
//
//            Matrix savedMatrix = null;
//            if (!isInitialLoad && imageView.getDrawable() != null) {
//                savedMatrix = new Matrix(matrix);
//            }
//
//            imageView.setImageDrawable(drawable);
//
//            if (isInitialLoad) {
//                matrix.reset();
//                float imageWidth = drawable.getIntrinsicWidth();
//                float imageHeight = drawable.getIntrinsicHeight();
//
//                float scaleX = width / imageWidth;
//                float scaleY = height / imageHeight;
//                float scale = Math.min(scaleX, scaleY);
//
//                float tx = (width - imageWidth * scale) / 2;
//                float ty = (height - imageHeight * scale) / 2;
//
//                matrix.setScale(scale, scale);
//                matrix.postTranslate(tx, ty);
//
//                initialScale = scale;
//                minScale = scale * 1.0f;
//                maxScale = scale * 4.0f;
//
//                isInitialLoad = false;
//            } else {
//                if (savedMatrix != null) {
//                    matrix.set(savedMatrix);
//
//                    matrix.getValues(matrixValues);
//                    float currentScale = matrixValues[Matrix.MSCALE_X];
//
//                    if (currentScale < minScale) {
//                        float scale = minScale / currentScale;
//                        matrix.postScale(scale, scale, width/2, height/2);
//                    } else if (currentScale > maxScale) {
//                        float scale = maxScale / currentScale;
//                        matrix.postScale(scale, scale, width/2, height/2);
//                    }
//
//                    checkAndFixBoundaries();
//                }
//            }
//
//            imageView.setImageMatrix(matrix);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }
//
//    // Navigation Methods
//    @Override
//    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
//
//        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
//
//        if (item.getItemId() == R.id.action_network) {
//            ft.show(mNetworkFragment)
//                    .hide(mDevicesFilterFragment)
//                    .hide(mGroupsFragment)
//                    .hide(mProxyFilterFragment)
//                    .hide(mSettingsFragment);
//        }
//
//        else if (item.getItemId() == R.id.action_device_filter) {
//            ft.hide(mNetworkFragment)
//                    .show(mDevicesFilterFragment)
//                    .hide(mGroupsFragment)
//                    .hide(mProxyFilterFragment)
//                    .hide(mSettingsFragment);
//        }
//
//        else if (item.getItemId() == R.id.action_groups) {
//            ft.hide(mNetworkFragment)
//                    .hide(mDevicesFilterFragment)
//                    .show(mGroupsFragment)
//                    .hide(mProxyFilterFragment)
//                    .hide(mSettingsFragment);
//        }
//
//        else if (item.getItemId() == R.id.action_proxy) {
//            ft.hide(mNetworkFragment)
//                    .hide(mDevicesFilterFragment)
//                    .hide(mGroupsFragment)
//                    .show(mProxyFilterFragment)
//                    .hide(mSettingsFragment);
//        }
//
//        else if (item.getItemId() == R.id.action_settings) {
//            ft.hide(mNetworkFragment)
//                    .hide(mDevicesFilterFragment)
//                    .hide(mGroupsFragment)
//                    .hide(mProxyFilterFragment)
//                    .show(mSettingsFragment);
//        }
//
//        ft.commit();
//        invalidateOptionsMenu();
//        return true;
//    }
//
//
//
//    @Override
//    public void onNavigationItemReselected(@NonNull MenuItem item) {
//        // No-op
//    }
//
//
//    private void resetMapView() {
//        // Reset to initial zoom and position
//        isInitialLoad = true;
//        loadSVG();
//    }
//
//    private void showFragment(int itemId) {
//        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
//
//        // First hide all fragments
//        if (mDevicesFilterFragment != null) ft.hide(mDevicesFilterFragment);
//        if (mGroupsFragment != null) ft.hide(mGroupsFragment);
//        if (mProxyFilterFragment != null) ft.hide(mProxyFilterFragment);
//        if (mSettingsFragment != null) ft.hide(mSettingsFragment);
//
//        // Then show selected fragment
//        if (itemId == R.id.action_device_filter) {
//            if (mDevicesFilterFragment != null) ft.show(mDevicesFilterFragment);
//        } else if (itemId == R.id.action_groups) {
//            if (mGroupsFragment != null) ft.show(mGroupsFragment);
//        } else if (itemId == R.id.action_proxy) {
//            if (mProxyFilterFragment != null) ft.show(mProxyFilterFragment);
//        } else if (itemId == R.id.action_settings) {
//            if (mSettingsFragment != null) ft.show(mSettingsFragment);
//        }
//
//        ft.commit();
//        invalidateOptionsMenu();
//    }
//
//    // Options Menu
//    @Override
//    public boolean onCreateOptionsMenu(Menu menu) {
//
//        Boolean isConnected = mViewModel.isConnectedToProxy().getValue();
//
//        getMenuInflater().inflate(
//                isConnected != null && isConnected
//                        ? R.menu.menu_connect_icon
//                        : R.menu.menu_disconnect_icon,
//                menu
//        );
//
//        MenuItem item;
//
//        if (isConnected == null || !isConnected) {
//
//            // DISCONNECTED → start blinking
//            item = menu.findItem(R.id.action_disconnection_state);
//
//            if (item != null) {
//
//                View view = findViewById(item.getItemId());
//
//                if (view != null) {
//
//                    Animation blink =
//                            AnimationUtils.loadAnimation(this, R.anim.blink);
//
//                    view.startAnimation(blink);
//                }
//            }
//
//        } else {
//
//            // CONNECTED → stop blinking
//            item = menu.findItem(R.id.action_connection_state);
//
//            if (item != null) {
//
//                View view = findViewById(item.getItemId());
//
//                if (view != null) {
//                    view.clearAnimation();
//                }
//            }
//        }
//
//        return true;
//    }
//
//    @Override
//    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
//        if (item.getItemId() == R.id.action_connection_state) {
//            mViewModel.navigateToScannerActivity(this, false);
//            return true;
//        } else if (item.getItemId() == R.id.action_disconnection_state) {
//            mViewModel.disconnect();
//            return true;
//        }
//        return super.onOptionsItemSelected(item);
//    }
//
//    // Save/Restore State
//    @Override
//    protected void onSaveInstanceState(@NonNull Bundle outState) {
//        super.onSaveInstanceState(outState);
//        outState.putInt(CURRENT_FRAGMENT, binding.bottomNavigationView.getSelectedItemId());
//        outState.putString(SELECTED_DEVICE_ID, selectedDeviceId);
//        outState.putBoolean(IS_SVG_VISIBLE, isSvgVisible);
//
//        float[] matrixArray = new float[9];
//        matrix.getValues(matrixArray);
//        outState.putFloatArray(MATRIX_STATE, matrixArray);
//    }
//}