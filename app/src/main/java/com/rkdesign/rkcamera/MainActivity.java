package com.rkdesign.rkcamera;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.ZoomState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;

import androidx.media3.common.MediaItem;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.SingleColorLut;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RK Camera AI v2.2. Tampilan bergaya kamera iOS, menu pengaturan, stabilizer Mati/Standar/Maks,
 * kunci horizon, zoom sampai 100x (software di atas batas hardware) dan filter bergaya iPhone.
 * Pemrosesan berat (warna, stabilisasi) dilakukan setelah foto/video diambil.
 */
public class MainActivity extends AppCompatActivity {
    private static final int YELLOW = Color.rgb(255, 214, 10);
    private static final int TOP_BAR_DP = 52;
    private static final int BOTTOM_PANEL_DP = 168;
    private static final float MAX_ZOOM = 100f;

    private interface SegmentListener { void onSelected(int index); }

    /** Data yang dikunci saat rekaman dimulai, dipakai saat video diproses. */
    private static final class ExportPlan {
        final int stabMode;
        final boolean horizon;
        final IPhoneLook look;
        final List<double[]> rollTimeline;
        final List<double[]> zoomTimeline;
        final int quality;
        ExportPlan(int stabMode, boolean horizon, IPhoneLook look, List<double[]> rollTimeline,
                   List<double[]> zoomTimeline, int quality) {
            this.stabMode = stabMode; this.horizon = horizon; this.look = look;
            this.rollTimeline = rollTimeline; this.zoomTimeline = zoomTimeline; this.quality = quality;
        }
    }

    private final class GridOverlay extends View {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        GridOverlay(Context context) {
            super(context);
            linePaint.setColor(Color.argb(120, 255, 255, 255));
            linePaint.setStrokeWidth(Math.max(1f, dp(1) * 0.8f));
        }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            for (int i = 1; i < 3; i++) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, linePaint);
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, linePaint);
            }
        }
    }

    private CameraSettings settings;
    private FrameLayout root, previewHolder, topBar, settingsOverlay, shutterButton;
    private LinearLayout zoomRow, bottomPanel;
    private ScrollView sheetScroll;
    private PreviewView previewView;
    private IconView flashButton, settingsButton;
    private TextView timerText, videoLabel, photoLabel, statusText, stabHint;
    private View shutterInnerView, focusBox, gridView;
    private ImageView thumbView;
    private final ArrayList<TextView> zoomChips = new ArrayList<>();
    private final ArrayList<Float> zoomChipValues = new ArrayList<>();
    private final ArrayList<TextView> lookChips = new ArrayList<>();

    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private Camera camera;
    private LiveData<ZoomState> zoomLive;
    private Observer<ZoomState> zoomObserver;
    private ValueAnimator zoomAnimator;

    private boolean videoMode = false;
    private boolean frontCamera = false;
    private int flashMode = 0;                 // 0 = otomatis, 1 = nyala, 2 = mati
    private int captureRotation = Surface.ROTATION_0;
    private float hwMin = 1f, hwMax = 1f;
    private float zoomTarget = 1f;             // zoom total yang diminta (1x = kamera utama)
    private float softZoom = 1f;               // bagian zoom yang dikerjakan software
    private volatile boolean isProcessingVideo = false;
    private boolean thumbLoaded = false;
    private String pendingVideoStamp;
    private volatile Uri lastSavedUri;
    private volatile String lastSavedMime = "image/jpeg";
    private Transformer activeTransformer;

    // Data perekaman video
    private IPhoneLook recordingLook = IPhoneLook.ALL[0];
    private int recStab = VideoStabilizer.STANDARD;
    private boolean recHorizon = false;
    private long recStartNs = 0L;
    private long recStartMs = 0L;
    private final ArrayList<double[]> rollTimeline = new ArrayList<>();
    private final ArrayList<double[]> zoomTimeline = new ArrayList<>();

    private SensorManager sensorManager;
    private boolean rollSensorActive = false;
    private boolean gravityIsAccel = false;
    private float lpX, lpY;
    private boolean lpInit = false;
    private OrientationEventListener orientationListener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService processingExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbExecutor = Executors.newSingleThreadExecutor();

    private final Runnable hideFocusRunnable = () -> {
        if (focusBox != null) {
            focusBox.animate().alpha(0f).setDuration(250).withEndAction(() -> focusBox.setVisibility(View.GONE)).start();
        }
    };

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (recording == null || timerText == null) return;
            long s = Math.max(0L, (SystemClock.elapsedRealtime() - recStartMs) / 1000L);
            timerText.setText(String.format(Locale.US, "● %d:%02d", s / 60, s % 60));
            mainHandler.postDelayed(this, 500);
        }
    };

    private final SensorEventListener rollListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            if (recStartNs == 0L || e.values == null || e.values.length < 2) return;
            float gx = e.values[0], gy = e.values[1];
            if (gravityIsAccel) {
                if (!lpInit) { lpX = gx; lpY = gy; lpInit = true; }
                else { lpX += 0.15f * (gx - lpX); lpY += 0.15f * (gy - lpY); }
                gx = lpX; gy = lpY;
            }
            // Miring HP searah jarum jam = atan2(-x, y). Dibuat relatif terhadap kelipatan 90 derajat
            // terdekat supaya tetap benar bila HP dipegang menyamping.
            double roll = Math.toDegrees(Math.atan2(-gx, gy));
            roll = roll - 90.0 * Math.round(roll / 90.0);
            long tUs = (e.timestamp - recStartNs) / 1000L;
            rollTimeline.add(new double[]{tUs, roll});
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean cameraGranted = Boolean.TRUE.equals(result.get(Manifest.permission.CAMERA));
                boolean storageGranted = Build.VERSION.SDK_INT > 28 ||
                        Boolean.TRUE.equals(result.get(Manifest.permission.WRITE_EXTERNAL_STORAGE));
                if (cameraGranted && storageGranted) {
                    startCamera();
                } else {
                    setStatus("Izin kamera diperlukan untuk membuka kamera.");
                    toast("Izinkan akses kamera agar RK Camera AI dapat digunakan.");
                }
            });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new CameraSettings(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                int rotation;
                if (orientation >= 45 && orientation < 135) rotation = Surface.ROTATION_270;
                else if (orientation >= 135 && orientation < 225) rotation = Surface.ROTATION_180;
                else if (orientation >= 225 && orientation < 315) rotation = Surface.ROTATION_90;
                else rotation = Surface.ROTATION_0;
                if (rotation == captureRotation) return;
                captureRotation = rotation;
                if (imageCapture != null) imageCapture.setTargetRotation(rotation);
                if (videoCapture != null) videoCapture.setTargetRotation(rotation);
            }
        };
        buildUi();
        if (hasPermissions()) startCamera(); else requestPermissionsNow();
    }

    @Override protected void onResume() {
        super.onResume();
        if (orientationListener != null && orientationListener.canDetectOrientation()) orientationListener.enable();
    }

    @Override protected void onPause() {
        if (orientationListener != null) orientationListener.disable();
        if (recording == null) stopRollSensor();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (settingsOverlay != null && settingsOverlay.getVisibility() == View.VISIBLE) {
            hideSettings();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewHolder = new FrameLayout(this);
        previewHolder.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        previewHolder.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        gridView = new GridOverlay(this);
        gridView.setVisibility(settings.grid() ? View.VISIBLE : View.GONE);
        previewHolder.addView(gridView, new FrameLayout.LayoutParams(-1, -1));
        focusBox = new View(this);
        GradientDrawable focusDrawable = new GradientDrawable();
        focusDrawable.setShape(GradientDrawable.RECTANGLE);
        focusDrawable.setColor(Color.TRANSPARENT);
        focusDrawable.setStroke(dp(2), YELLOW);
        focusBox.setBackground(focusDrawable);
        focusBox.setVisibility(View.GONE);
        previewHolder.addView(focusBox, new FrameLayout.LayoutParams(dp(76), dp(76)));
        root.addView(previewHolder, new FrameLayout.LayoutParams(-1, -1));

        // Bar atas: flash (kiri), timer (tengah), pengaturan (kanan)
        topBar = new FrameLayout(this);
        flashButton = new IconView(this, IconView.FLASH_AUTO);
        flashButton.setPadding(dp(11), dp(11), dp(11), dp(11));
        FrameLayout.LayoutParams flashLp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL);
        flashLp.leftMargin = dp(10);
        topBar.addView(flashButton, flashLp);
        timerText = label("", 13, Color.WHITE, true);
        timerText.setGravity(Gravity.CENTER);
        timerText.setBackground(pillDrawable(Color.rgb(255, 59, 48), Color.TRANSPARENT));
        timerText.setPadding(dp(12), 0, dp(12), 0);
        timerText.setVisibility(View.GONE);
        topBar.addView(timerText, new FrameLayout.LayoutParams(-2, dp(28), Gravity.CENTER));
        settingsButton = new IconView(this, IconView.SLIDERS);
        settingsButton.setPadding(dp(11), dp(11), dp(11), dp(11));
        FrameLayout.LayoutParams setLp = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.CENTER_VERTICAL);
        setLp.rightMargin = dp(10);
        topBar.addView(settingsButton, setLp);
        root.addView(topBar, new FrameLayout.LayoutParams(-1, dp(TOP_BAR_DP), Gravity.TOP));

        // Deretan tombol zoom (posisi diatur di applyPreviewLayout)
        zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setBackground(pillDrawable(Color.argb(110, 0, 0, 0), Color.TRANSPARENT));
        zoomRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        root.addView(zoomRow, new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        // Panel bawah: status, pilihan mode, rana
        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        statusText = label("", 11, Color.rgb(170, 170, 176), false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setSingleLine(true);
        statusText.setPadding(dp(16), 0, dp(16), 0);
        bottomPanel.addView(statusText, new LinearLayout.LayoutParams(-1, dp(22)));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER);
        videoLabel = label("VIDEO", 14, Color.WHITE, true);
        photoLabel = label("FOTO", 14, Color.WHITE, true);
        videoLabel.setGravity(Gravity.CENTER);
        photoLabel.setGravity(Gravity.CENTER);
        videoLabel.setLetterSpacing(0.06f);
        photoLabel.setLetterSpacing(0.06f);
        modes.addView(videoLabel, new LinearLayout.LayoutParams(dp(92), dp(38)));
        modes.addView(photoLabel, new LinearLayout.LayoutParams(dp(92), dp(38)));
        bottomPanel.addView(modes, new LinearLayout.LayoutParams(-1, dp(38)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout leftCell = new FrameLayout(this);
        thumbView = new ImageView(this);
        thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable thumbBg = new GradientDrawable();
        thumbBg.setColor(Color.rgb(36, 36, 38));
        thumbBg.setCornerRadius(dp(10));
        thumbView.setBackground(thumbBg);
        thumbView.setClipToOutline(true);
        thumbView.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        leftCell.addView(thumbView, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        controls.addView(leftCell, new LinearLayout.LayoutParams(0, -1, 1f));

        shutterButton = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(4), Color.WHITE);
        shutterButton.setBackground(ring);
        shutterInnerView = new View(this);
        shutterInnerView.setBackground(circleDrawable(Color.WHITE));
        shutterButton.addView(shutterInnerView, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
        controls.addView(shutterButton, new LinearLayout.LayoutParams(dp(78), dp(78)));

        FrameLayout rightCell = new FrameLayout(this);
        final FrameLayout flipCircle = new FrameLayout(this);
        flipCircle.setBackground(circleDrawable(Color.argb(210, 52, 52, 56)));
        IconView flipIcon = new IconView(this, IconView.FLIP);
        flipIcon.setPadding(dp(11), dp(11), dp(11), dp(11));
        flipCircle.addView(flipIcon, new FrameLayout.LayoutParams(-1, -1));
        rightCell.addView(flipCircle, new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.CENTER));
        controls.addView(rightCell, new LinearLayout.LayoutParams(0, -1, 1f));

        bottomPanel.addView(controls, new LinearLayout.LayoutParams(-1, dp(94)));
        root.addView(bottomPanel, new FrameLayout.LayoutParams(-1, dp(BOTTOM_PANEL_DP), Gravity.BOTTOM));

        buildSettingsSheet();
        setContentView(root);

        setModeUi();
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            if (r - l != or2 - ol || b - t != ob - ot) applyPreviewLayout();
        });
        root.post(this::applyPreviewLayout);

        videoLabel.setOnClickListener(v -> switchMode(true));
        photoLabel.setOnClickListener(v -> switchMode(false));
        shutterButton.setOnClickListener(v -> onShutterPressed());
        flashButton.setOnClickListener(v -> cycleFlash());
        settingsButton.setOnClickListener(v -> showSettings());
        flipCircle.setOnClickListener(v -> {
            if (recording != null) { toast("Hentikan rekaman terlebih dahulu."); return; }
            flipCircle.animate().rotationBy(180f).setDuration(280).start();
            frontCamera = !frontCamera;
            startCamera();
        });
        thumbView.setOnClickListener(v -> openLastMediaOrPicker());

        final ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float factor = (float) Math.pow(detector.getScaleFactor(), 1.8);
                        setZoomTarget(zoomTarget * factor);
                        return true;
                    }
                });
        final GestureDetector gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDown(MotionEvent e) { return true; }
                    @Override public boolean onSingleTapUp(MotionEvent e) {
                        focusAt(e.getX(), e.getY());
                        return true;
                    }
                    @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > dp(80) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                            switchMode(dx > 0);   // geser kanan = VIDEO, geser kiri = FOTO
                            return true;
                        }
                        return false;
                    }
                });
        previewHolder.setOnTouchListener((view, event) -> {
            scaleDetector.onTouchEvent(event);
            if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) gestureDetector.onTouchEvent(event);
            return true;
        });
    }

    /** Preview 4:3 di bawah bar atas untuk foto; layar penuh untuk video (seperti kamera iPhone). */
    private void applyPreviewLayout() {
        if (root == null || root.getWidth() == 0) return;
        int w = root.getWidth(), h = root.getHeight();
        int top = dp(TOP_BAR_DP), bottom = dp(BOTTOM_PANEL_DP);
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) previewHolder.getLayoutParams();
        int zoomTop;
        if (videoMode) {
            p.width = -1; p.height = -1; p.topMargin = 0; p.bottomMargin = 0;
            zoomTop = h - bottom - dp(52);
        } else {
            int avail = h - top - bottom;
            int ph = Math.min(Math.round(w * 4f / 3f), avail);
            p.width = -1; p.height = ph; p.topMargin = top; p.bottomMargin = 0;
            p.gravity = Gravity.TOP;
            zoomTop = top + ph - dp(52);
        }
        previewHolder.setLayoutParams(p);
        FrameLayout.LayoutParams zp = (FrameLayout.LayoutParams) zoomRow.getLayoutParams();
        zp.topMargin = Math.max(top, zoomTop);
        zoomRow.setLayoutParams(zp);
    }

    private void setModeUi() {
        if (videoLabel == null) return;
        videoLabel.setTextColor(videoMode ? YELLOW : Color.WHITE);
        photoLabel.setTextColor(videoMode ? Color.WHITE : YELLOW);
        topBar.setBackgroundColor(videoMode ? Color.argb(70, 0, 0, 0) : Color.TRANSPARENT);
        bottomPanel.setBackgroundColor(videoMode ? Color.argb(90, 0, 0, 0) : Color.TRANSPARENT);
        if (videoMode) {
            if (recording != null) {
                shutterInnerView.setBackground(roundedDrawable(Color.rgb(255, 59, 48), dp(8)));
                shutterInnerView.setLayoutParams(new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            } else {
                shutterInnerView.setBackground(circleDrawable(Color.rgb(255, 59, 48)));
                shutterInnerView.setLayoutParams(new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
            }
        } else {
            shutterInnerView.setBackground(circleDrawable(Color.WHITE));
            shutterInnerView.setLayoutParams(new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
        }
        boolean rec = recording != null;
        videoLabel.setAlpha(rec ? 0.3f : 1f);
        photoLabel.setAlpha(rec ? 0.3f : 1f);
    }

    private void onShutterPressed() {
        if (videoMode) {
            if (isProcessingVideo && recording == null) {
                toast("Video sebelumnya masih diproses. Tunggu sampai selesai sebelum merekam lagi.");
                return;
            }
            toggleRecording();
        } else {
            shutterInnerView.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction(
                    () -> shutterInnerView.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
            takePhoto();
        }
    }

    private void switchMode(boolean toVideo) {
        if (recording != null) { toast("Hentikan rekaman terlebih dahulu."); return; }
        if (videoMode == toVideo) return;
        videoMode = toVideo;
        setModeUi();
        applyPreviewLayout();
        startCamera();
    }

    // ------------------------------------------------------------------ pengaturan

    private void buildSettingsSheet() {
        settingsOverlay = new FrameLayout(this);
        settingsOverlay.setBackgroundColor(Color.argb(150, 0, 0, 0));
        settingsOverlay.setPadding(0, dp(70), 0, 0);
        settingsOverlay.setVisibility(View.GONE);
        settingsOverlay.setClickable(true);
        settingsOverlay.setOnClickListener(v -> hideSettings());

        sheetScroll = new ScrollView(this);
        GradientDrawable sheetBg = new GradientDrawable();
        sheetBg.setColor(Color.rgb(28, 28, 30));
        sheetBg.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), 0, 0, 0, 0});
        sheetScroll.setBackground(sheetBg);
        sheetScroll.setClickable(true);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(18), dp(8), dp(18), dp(28));
        sheetScroll.addView(sheet, new FrameLayout.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Pengaturan Kamera", 18, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView done = label("Selesai", 16, YELLOW, true);
        done.setGravity(Gravity.CENTER);
        done.setPadding(dp(8), 0, 0, 0);
        done.setOnClickListener(v -> hideSettings());
        header.addView(done, new LinearLayout.LayoutParams(-2, dp(48)));
        sheet.addView(header);

        sheet.addView(sectionTitle("STABILISASI VIDEO"));
        sheet.addView(segmented(new String[]{"Mati", "Standar", "Maks"}, settings.stabilizer(), i -> {
            settings.setStabilizer(i);
            stabHint.setText(stabilizerHint(i));
        }), segmentParams());
        stabHint = hint(stabilizerHint(settings.stabilizer()));
        sheet.addView(stabHint);

        sheet.addView(sectionTitle("KUNCI HORIZON (VIDEO)"));
        sheet.addView(segmented(new String[]{"Mati", "Aktif"}, settings.horizonLock() ? 1 : 0, i -> {
            settings.setHorizonLock(i == 1);
            if (i == 1 && frontCamera) toast("Kunci horizon hanya bekerja di kamera belakang.");
        }), segmentParams());
        sheet.addView(hint("Menjaga cakrawala tetap lurus saat HP miring sampai 15°, mirip Horizon Lock Samsung. Layar sedikit terpotong saat HP miring."));

        sheet.addView(sectionTitle("FILTER"));
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        lookChips.clear();
        for (int i = 0; i < IPhoneLook.ALL.length; i++) {
            TextView chip = label(IPhoneLook.ALL[i].name, 12, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), 0, dp(14), 0);
            final int index = i;
            chip.setOnClickListener(v -> { settings.setLookIndex(index); paintLookChips(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
            lp.rightMargin = dp(8);
            chipRow.addView(chip, lp);
            lookChips.add(chip);
        }
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams chipScrollLp = new LinearLayout.LayoutParams(-1, dp(42));
        chipScrollLp.topMargin = dp(6);
        sheet.addView(chipScroll, chipScrollLp);
        paintLookChips();
        sheet.addView(hint("Filter diterapkan pada foto dan video setelah pengambilan."));

        sheet.addView(sectionTitle("KUALITAS VIDEO"));
        sheet.addView(segmented(new String[]{"HD 720p", "FHD 1080p"}, settings.videoQuality(), i -> {
            settings.setVideoQuality(i);
            if (videoMode && recording == null) bindUseCases();
        }), segmentParams());
        sheet.addView(hint("FHD lebih tajam tetapi pemrosesan lebih lama dan tidak semua HP lama kuat."));

        sheet.addView(sectionTitle("GARIS BANTU"));
        sheet.addView(segmented(new String[]{"Mati", "Grid 3×3"}, settings.grid() ? 1 : 0, i -> {
            settings.setGrid(i == 1);
            gridView.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        }), segmentParams());

        sheet.addView(sectionTitle("GALERI & EDITOR"));
        TextView studio = label("BUKA RK STUDIO", 14, Color.WHITE, true);
        studio.setGravity(Gravity.CENTER);
        studio.setBackground(roundedDrawable(Color.rgb(58, 58, 60), dp(10)));
        studio.setPadding(dp(12), dp(12), dp(12), dp(12));
        studio.setOnClickListener(v -> {
            hideSettings();
            startActivity(new Intent(MainActivity.this, MediaStudioActivity.class));
        });
        sheet.addView(studio, new LinearLayout.LayoutParams(-1, dp(48)));
        sheet.addView(hint("Editor: AI Potret foto, enhancement malam, filter, hapus area objek, rotasi, dan proses stabilizer video."));

        TextView footer = hint("RK Camera AI v2.2 • hasil pemrosesan dibuat di perangkat.");
        footer.setGravity(Gravity.CENTER);
        sheet.addView(footer);

        settingsOverlay.addView(sheetScroll, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(settingsOverlay, new FrameLayout.LayoutParams(-1, -1));
    }

    private String stabilizerHint(int mode) {
        if (mode == VideoStabilizer.OFF) return "Tanpa stabilisasi dan tanpa pemotongan layar.";
        if (mode == VideoStabilizer.MAX) return "Paling stabil: potongan layar ±30%, getaran halus ikut diredam. Proses lebih lama.";
        return "Seimbang: potongan layar ±17%, cocok untuk dipegang tangan.";
    }

    private TextView sectionTitle(String text) {
        TextView t = label(text, 11, Color.rgb(142, 142, 147), true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(18), 0, dp(6));
        return t;
    }

    private TextView hint(String text) {
        TextView t = label(text, 12, Color.rgb(142, 142, 147), false);
        t.setGravity(Gravity.START);
        t.setPadding(dp(4), dp(6), dp(4), 0);
        return t;
    }

    private LinearLayout.LayoutParams segmentParams() {
        return new LinearLayout.LayoutParams(-1, dp(40));
    }

    private LinearLayout segmented(String[] options, int selected, SegmentListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(roundedDrawable(Color.rgb(44, 44, 46), dp(10)));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        final TextView[] cells = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            TextView t = label(options[i], 13, Color.WHITE, false);
            t.setGravity(Gravity.CENTER);
            cells[i] = t;
            final int index = i;
            t.setOnClickListener(v -> {
                paintSegments(cells, index);
                listener.onSelected(index);
            });
            row.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        paintSegments(cells, selected);
        return row;
    }

    private void paintSegments(TextView[] cells, int selected) {
        for (int i = 0; i < cells.length; i++) {
            boolean on = i == selected;
            cells[i].setBackground(on ? roundedDrawable(Color.rgb(99, 99, 102), dp(8)) : null);
            cells[i].setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void paintLookChips() {
        int selected = settings.lookIndex();
        for (int i = 0; i < lookChips.size(); i++) {
            TextView c = lookChips.get(i);
            boolean on = i == selected;
            c.setBackground(pillDrawable(on ? YELLOW : Color.rgb(58, 58, 60), Color.TRANSPARENT));
            c.setTextColor(on ? Color.BLACK : Color.WHITE);
        }
    }

    private void showSettings() {
        settingsOverlay.setVisibility(View.VISIBLE);
        sheetScroll.setTranslationY(root.getHeight());
        sheetScroll.animate().translationY(0f).setDuration(220).start();
    }

    private void hideSettings() {
        if (settingsOverlay.getVisibility() != View.VISIBLE) return;
        sheetScroll.animate().translationY(root.getHeight()).setDuration(200)
                .withEndAction(() -> settingsOverlay.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------------ helper tampilan

    private TextView label(String text, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable circleDrawable(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable roundedDrawable(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private GradientDrawable pillDrawable(int color, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(18));
        g.setColor(color);
        if (strokeColor != Color.TRANSPARENT) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setStatus(String message) {
        if (statusText != null) statusText.setText(message);
    }

    /** Pesan singkat; aman dipanggil dari callback di thread mana pun. */
    private void toast(String message) {
        if (message == null || message.trim().isEmpty()) return;
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private String readableError(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return e.getClass().getSimpleName();
        if (m.length() > 120) m = m.substring(0, 120);
        return m;
    }

    private String stamp() {
        return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
    }

    // ------------------------------------------------------------------ izin & kamera

    private boolean hasPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT > 28 || ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissionsNow() {
        if (Build.VERSION.SDK_INT <= 28) {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE});
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        }
    }

    private void startCamera() {
        if (!hasPermissions()) { requestPermissionsNow(); return; }
        setStatus("Menyiapkan kamera…");
        if (!thumbLoaded) { thumbLoaded = true; loadLatestThumb(); }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
            } catch (ExecutionException | InterruptedException e) {
                setStatus("Kamera gagal dibuka. Periksa izin kamera.");
                toast("Kamera gagal dibuka: " + readableError(e));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases() {
        if (cameraProvider == null) return;
        CameraSelector selector = new CameraSelector.Builder()
                .requireLensFacing(frontCamera ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK)
                .build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        try {
            cameraProvider.unbindAll();
            if (videoMode) {
                List<CameraInfo> lensInfos = selector.filter(cameraProvider.getAvailableCameraInfos());
                if (lensInfos.isEmpty()) throw new IllegalStateException("Kamera yang dipilih tidak tersedia");
                List<Quality> supported = QualitySelector.getSupportedQualities(lensInfos.get(0));
                if (supported == null || supported.isEmpty()) {
                    videoMode = false;
                    setModeUi();
                    applyPreviewLayout();
                    bindPhotoMode(selector, preview);
                    afterBind();
                    toast("Mode foto tetap aktif, tetapi kamera ini tidak melaporkan kualitas video yang didukung.");
                    return;
                }
                // HD dulu untuk HP lama; FHD bila dipilih di pengaturan. HIGHEST menjamin ada profil yang terpakai.
                Quality first = settings.videoQuality() == 1 ? Quality.FHD : Quality.HD;
                Quality second = settings.videoQuality() == 1 ? Quality.HD : Quality.FHD;
                List<Quality> preferences = Arrays.asList(first, second, Quality.SD, Quality.HIGHEST);
                QualitySelector qualitySelector = QualitySelector.fromOrderedList(preferences);
                Recorder recorder = new Recorder.Builder().setQualitySelector(qualitySelector).build();
                videoCapture = VideoCapture.withOutput(recorder);
                videoCapture.setTargetRotation(captureRotation);
                imageCapture = null;
                camera = cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
                setStatus(stabilizerStatus());
            } else {
                bindPhotoMode(selector, preview);
            }
            afterBind();
        } catch (Exception e) {
            // Jangan biarkan layar hitam bila kombinasi stream/profil video gagal.
            try { cameraProvider.unbindAll(); } catch (Exception ignored) { }
            videoCapture = null;
            if (videoMode) {
                videoMode = false;
                setModeUi();
                applyPreviewLayout();
                try {
                    Preview photoPreview = new Preview.Builder().build();
                    photoPreview.setSurfaceProvider(previewView.getSurfaceProvider());
                    bindPhotoMode(selector, photoPreview);
                    afterBind();
                    toast("Kamera tetap dibuka dalam mode foto. Video gagal diinisialisasi: " + readableError(e));
                } catch (Exception photoError) {
                    setStatus("Kamera tidak dapat dibuka. Coba tutup aplikasi lain yang memakai kamera.");
                    toast("Gagal membuka kamera: " + readableError(photoError));
                }
            } else {
                setStatus("Kamera tidak dapat dibuka. Coba tutup aplikasi lain yang memakai kamera.");
                toast("Gagal membuka kamera: " + readableError(e));
            }
        }
    }

    private void bindPhotoMode(CameraSelector selector, Preview preview) {
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(95)
                .setTargetRotation(captureRotation)
                .build();
        videoCapture = null;
        camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture);
        setStatus("Foto siap • ketuk layar untuk fokus, cubit untuk zoom");
    }

    private String stabilizerStatus() {
        int m = settings.stabilizer();
        String name = m == VideoStabilizer.MAX ? "Maks" : (m == VideoStabilizer.STANDARD ? "Standar" : "Mati");
        return "Video siap • stabilizer " + name + (settings.horizonLock() && !frontCamera ? " • horizon terkunci" : "");
    }

    /** Dipanggil setelah kamera berhasil di-bind: baca batas zoom hardware dan reset zoom. */
    private void afterBind() {
        hwMin = 1f;
        hwMax = 1f;
        try {
            ZoomState zs = camera.getCameraInfo().getZoomState().getValue();
            if (zs != null) { hwMin = zs.getMinZoomRatio(); hwMax = zs.getMaxZoomRatio(); }
        } catch (Exception ignored) { }
        observeZoom();
        buildZoomChips();
        zoomTarget = 1f;
        softZoom = 1f;
        previewView.setScaleX(1f);
        previewView.setScaleY(1f);
        setZoomTarget(1f);
        updateFlashForCurrentMode();
    }

    private void observeZoom() {
        if (zoomLive != null && zoomObserver != null) zoomLive.removeObserver(zoomObserver);
        try {
            zoomLive = camera.getCameraInfo().getZoomState();
            zoomObserver = zs -> {
                if (zs == null) return;
                if (Math.abs(zs.getMinZoomRatio() - hwMin) > 0.001f || Math.abs(zs.getMaxZoomRatio() - hwMax) > 0.001f) {
                    hwMin = zs.getMinZoomRatio();
                    hwMax = zs.getMaxZoomRatio();
                    buildZoomChips();
                }
            };
            zoomLive.observe(this, zoomObserver);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ flash

    private void cycleFlash() {
        flashMode = (flashMode + 1) % 3;
        updateFlashForCurrentMode();
    }

    private void updateFlashForCurrentMode() {
        int icon = flashMode == 0 ? IconView.FLASH_AUTO : (flashMode == 1 ? IconView.FLASH : IconView.FLASH_OFF);
        flashButton.setIcon(icon);
        flashButton.setColor(flashMode == 1 ? YELLOW : Color.WHITE);
        if (camera == null) return;
        boolean available = false;
        try { available = camera.getCameraInfo().hasFlashUnit(); } catch (Exception ignored) { }
        flashButton.setEnabled(available);
        flashButton.setAlpha(available ? 1f : 0.35f);
        if (!available) return;
        try {
            if (videoMode) {
                camera.getCameraControl().enableTorch(flashMode == 1);
            } else if (imageCapture != null) {
                int fm = flashMode == 0 ? ImageCapture.FLASH_MODE_AUTO
                        : (flashMode == 1 ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
                imageCapture.setFlashMode(fm);
            }
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ zoom

    private void buildZoomChips() {
        if (zoomRow == null) return;
        zoomRow.removeAllViews();
        zoomChips.clear();
        zoomChipValues.clear();
        ArrayList<Float> values = new ArrayList<>();
        if (hwMin < 0.95f) values.add(hwMin);
        values.add(1f);
        values.add(2f);
        values.add(5f);
        values.add(10f);
        values.add(30f);
        values.add(100f);
        for (final float v : values) {
            TextView chip = label("", 12, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.leftMargin = dp(2);
            lp.rightMargin = dp(2);
            chip.setOnClickListener(x -> animateZoomTo(v));
            zoomRow.addView(chip, lp);
            zoomChips.add(chip);
            zoomChipValues.add(v);
        }
        updateZoomChips();
    }

    private void updateZoomChips() {
        if (zoomChips.isEmpty()) return;
        int selected = 0;
        for (int i = 0; i < zoomChipValues.size(); i++) {
            if (zoomTarget + 0.02f >= zoomChipValues.get(i)) selected = i;
        }
        for (int i = 0; i < zoomChips.size(); i++) {
            TextView c = zoomChips.get(i);
            if (i == selected) {
                String text = formatZoom(zoomTarget) + "×";
                c.setText(text);
                c.setTextSize(text.length() > 4 ? 10 : 12);
                c.setTextColor(YELLOW);
                c.setBackground(circleDrawable(Color.argb(190, 0, 0, 0)));
            } else {
                c.setText(formatZoom(zoomChipValues.get(i)));
                c.setTextSize(11);
                c.setTextColor(Color.WHITE);
                c.setBackground(null);
            }
        }
    }

    private String formatZoom(float z) {
        if (z < 1f) {
            String s = String.format(Locale.US, "%.1f", z);
            return s.startsWith("0") ? s.substring(1) : s;
        }
        if (z >= 10f) return String.valueOf(Math.round(z));
        float r = Math.round(z * 10f) / 10f;
        if (r == Math.floor(r)) return String.valueOf((int) r);
        return String.format(Locale.US, "%.1f", r);
    }

    private void animateZoomTo(float target) {
        if (zoomAnimator != null) zoomAnimator.cancel();
        zoomAnimator = ValueAnimator.ofFloat(zoomTarget, target);
        zoomAnimator.setDuration(220);
        zoomAnimator.addUpdateListener(a -> setZoomTarget((Float) a.getAnimatedValue()));
        zoomAnimator.start();
    }

    /** Zoom sampai batas hardware lewat kamera, sisanya lewat software (preview diperbesar, hasil di-crop). */
    private void setZoomTarget(float z) {
        float low = Math.min(1f, hwMin);
        zoomTarget = Math.max(low, Math.min(MAX_ZOOM, z));
        float hw = Math.max(hwMin, Math.min(hwMax, zoomTarget));
        softZoom = hw <= 0f ? 1f : Math.max(1f, zoomTarget / hw);
        if (camera != null) {
            try { camera.getCameraControl().setZoomRatio(hw); } catch (Exception ignored) { }
        }
        previewView.setScaleX(softZoom);
        previewView.setScaleY(softZoom);
        if (recording != null && recStartNs != 0L) {
            zoomTimeline.add(new double[]{(SystemClock.elapsedRealtimeNanos() - recStartNs) / 1000.0, softZoom});
        }
        updateZoomChips();
    }

    // ------------------------------------------------------------------ fokus

    private void focusAt(float x, float y) {
        showFocusBox(x, y);
        if (camera == null || previewView == null) return;
        try {
            float w = previewView.getWidth(), h = previewView.getHeight();
            // Preview diperbesar dari tengah saat zoom software; petakan balik ke koordinat asli.
            float lx = (x - w / 2f) / softZoom + w / 2f;
            float ly = (y - h / 2f) / softZoom + h / 2f;
            androidx.camera.core.MeteringPointFactory factory = previewView.getMeteringPointFactory();
            MeteringPoint point = factory.createPoint(lx, ly);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
        } catch (Exception ignored) { }
    }

    private void showFocusBox(float x, float y) {
        mainHandler.removeCallbacks(hideFocusRunnable);
        focusBox.animate().cancel();
        focusBox.setX(x - dp(38));
        focusBox.setY(y - dp(38));
        focusBox.setAlpha(1f);
        focusBox.setScaleX(1.35f);
        focusBox.setScaleY(1.35f);
        focusBox.setVisibility(View.VISIBLE);
        focusBox.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        mainHandler.postDelayed(hideFocusRunnable, 1100);
    }

    // ------------------------------------------------------------------ foto

    private void takePhoto() {
        if (imageCapture == null) {
            toast("Mode foto sedang disiapkan. Coba lagi.");
            return;
        }
        String id = stamp();
        final IPhoneLook look = IPhoneLook.ALL[settings.lookIndex()];
        final float soft = softZoom;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "RKCamera_RAW_" + id + ".jpg");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RK Camera AI");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build();
        setStatus("Mengambil foto…");
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults result) {
                Uri rawUri = result.getSavedUri();
                if (rawUri == null) {
                    setStatus("Foto dibuat, tetapi lokasi file tidak diketahui.");
                    toast("Tidak dapat menemukan file foto hasil kamera.");
                    return;
                }
                lastSavedUri = rawUri;
                lastSavedMime = "image/jpeg";
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(rawUri, ready, null, null);
                }
                loadThumb(rawUri, false);
                setStatus("Foto tersimpan • mengolah warna " + look.name + "…");
                enhancePhoto(rawUri, id, look, soft);
            }
            @Override public void onError(@NonNull ImageCaptureException e) {
                setStatus("Foto gagal diambil");
                toast("Gagal mengambil foto: " + readableError(e));
            }
        });
    }

    /** Zoom software (crop tengah + perbesar + pertajam) lalu warna bergaya iPhone. Foto asli tetap aman bila gagal. */
    private void enhancePhoto(Uri rawUri, String id, IPhoneLook look, float soft) {
        processingExecutor.execute(() -> {
            File temp = new File(getCacheDir(), "tone_" + id + ".jpg");
            Bitmap bitmap = null;
            boolean ok = false;
            String orientation = null;
            try (InputStream exifIn = getContentResolver().openInputStream(rawUri)) {
                if (exifIn != null) orientation = new ExifInterface(exifIn).getAttribute(ExifInterface.TAG_ORIENTATION);
            } catch (Exception ignored) { }
            try (InputStream input = getContentResolver().openInputStream(rawUri)) {
                if (input == null) throw new IllegalStateException("Tidak dapat membuka hasil foto");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                options.inMutable = true;
                bitmap = BitmapFactory.decodeStream(input, null, options);
                if (bitmap == null) throw new IllegalStateException("Format foto tidak dapat diproses");
                if (soft > 1.02f) {
                    Bitmap zoomed = softwareZoom(bitmap, soft);
                    if (zoomed != null && zoomed != bitmap) {
                        bitmap.recycle();
                        bitmap = zoomed;
                    }
                }
                int width = bitmap.getWidth();
                int[] row = new int[width];
                float[] px = new float[3];
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1);
                    for (int x = 0; x < width; x++) {
                        int c = row[x];
                        look.apply(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f, px);
                        row[x] = Color.argb(Color.alpha(c),
                                Math.round(px[0] * 255f), Math.round(px[1] * 255f), Math.round(px[2] * 255f));
                    }
                    bitmap.setPixels(row, 0, width, 0, y, width, 1);
                }
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw new IllegalStateException("JPEG tidak dapat ditulis");
                }
                // Decode bitmap membuang EXIF; salin orientasi agar foto tidak miring.
                if (orientation != null) {
                    try {
                        ExifInterface outExif = new ExifInterface(temp.getAbsolutePath());
                        outExif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation);
                        outExif.saveAttributes();
                    } catch (Exception ignored) { }
                }
                Uri finalUri = publishFile(temp, "RKCamera_" + id + ".jpg", "image/jpeg", true);
                if (finalUri == null) throw new IllegalStateException("Hasil olahan tidak dapat disimpan");
                getContentResolver().delete(rawUri, null, null);
                lastSavedUri = finalUri;
                lastSavedMime = "image/jpeg";
                ok = true;
            } catch (OutOfMemoryError oom) {
                // HP lama: pertahankan foto asli daripada crash.
            } catch (Exception ignored) {
                // Foto asli tetap tersimpan bila ada masalah pemrosesan.
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                if (temp.exists()) temp.delete();
            }
            final boolean completed = ok;
            final Uri shown = lastSavedUri;
            runOnUiThread(() -> {
                if (completed) {
                    setStatus("Foto tersimpan • warna " + look.name + (soft > 1.02f ? " • zoom digital" : ""));
                    loadThumb(shown, false);
                } else {
                    setStatus("Foto asli tersimpan • pemrosesan warna dilewati");
                    toast("Foto asli tetap aman; pemrosesan warna dilewati pada foto ini.");
                }
            });
        });
    }

    /** Crop bagian tengah sebesar 1/zoom lalu perbesar dengan Lanczos dan pertajam (unsharp mask). */
    private Bitmap softwareZoom(Bitmap src, float zoom) {
        int w = src.getWidth(), h = src.getHeight();
        int cw = Math.max(16, Math.round(w / zoom));
        int ch = Math.max(16, Math.round(h / zoom));
        int x = Math.max(0, (w - cw) / 2);
        int y = Math.max(0, (h - ch) / 2);
        Bitmap crop = Bitmap.createBitmap(src, x, y, Math.min(cw, w - x), Math.min(ch, h - y));
        float k = Math.min(1f, 3264f / Math.max(w, h));
        int outW = Math.max(1, Math.round(w * k));
        int outH = Math.max(1, Math.round(h * k));
        if (!OpenCVLoader.initLocal()) {
            Bitmap scaled = Bitmap.createScaledBitmap(crop, outW, outH, true);
            if (scaled != crop) crop.recycle();
            return scaled;
        }
        Mat in = new Mat(), up = new Mat(), blur = new Mat();
        try {
            Utils.bitmapToMat(crop, in);
            Imgproc.resize(in, up, new Size(outW, outH), 0, 0, Imgproc.INTER_LANCZOS4);
            double sigma = Math.min(3.0, 1.0 + Math.log(Math.max(1.0, zoom)) * 0.4);
            Imgproc.GaussianBlur(up, blur, new Size(0, 0), sigma);
            Core.addWeighted(up, 1.6, blur, -0.6, 0, up);
            Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(up, out);
            return out;
        } finally {
            in.release(); up.release(); blur.release();
            if (!crop.isRecycled()) crop.recycle();
        }
    }

    // ------------------------------------------------------------------ video

    private void toggleRecording() {
        if (videoCapture == null) {
            toast("Video tidak tersedia pada konfigurasi kamera ini. Foto masih dapat digunakan.");
            return;
        }
        if (recording != null) {
            recording.stop();
            setStatus("Menyelesaikan video…");
            return;
        }
        pendingVideoStamp = stamp();
        recordingLook = IPhoneLook.ALL[settings.lookIndex()];
        recStab = settings.stabilizer();
        recHorizon = settings.horizonLock() && !frontCamera;
        recStartNs = 0L;
        rollTimeline.clear();
        zoomTimeline.clear();
        if (recHorizon) startRollSensor();

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "RKCamera_" + pendingVideoStamp + ".mp4");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RK Camera AI");
        MediaStoreOutputOptions output = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pending = pending.withAudioEnabled();
        }
        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    recStartNs = SystemClock.elapsedRealtimeNanos();
                    recStartMs = SystemClock.elapsedRealtime();
                    zoomTimeline.add(new double[]{0.0, softZoom});
                    timerText.setText("● 0:00");
                    timerText.setVisibility(View.VISIBLE);
                    mainHandler.removeCallbacks(timerTick);
                    mainHandler.postDelayed(timerTick, 500);
                    setModeUi();
                    setStatus("Merekam • " + stabilizerStatus().replace("Video siap • ", ""));
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    recording = null;
                    mainHandler.removeCallbacks(timerTick);
                    timerText.setVisibility(View.GONE);
                    stopRollSensor();
                    setModeUi();
                    if (fin.hasError()) {
                        recStartNs = 0L;
                        setStatus("Rekaman gagal diselesaikan");
                        toast("Video gagal disimpan. Kode: " + fin.getError());
                    } else {
                        Uri rawVideo = fin.getOutputResults().getOutputUri();
                        if (rawVideo == null) {
                            recStartNs = 0L;
                            setStatus("Video tersimpan, tetapi file tidak ditemukan untuk diproses.");
                            return;
                        }
                        lastSavedUri = rawVideo;
                        lastSavedMime = "video/mp4";
                        loadThumb(rawVideo, true);
                        boolean usesSoftZoom = false;
                        for (double[] z : zoomTimeline) { if (z[1] > 1.001) { usesSoftZoom = true; break; } }
                        ExportPlan plan = new ExportPlan(recStab, recHorizon, recordingLook,
                                new ArrayList<>(rollTimeline),
                                usesSoftZoom ? new ArrayList<>(zoomTimeline) : null,
                                settings.videoQuality());
                        recStartNs = 0L;
                        postProcessVideo(rawVideo, pendingVideoStamp == null ? stamp() : pendingVideoStamp, plan);
                    }
                }
            });
            setModeUi();
        } catch (Exception e) {
            recording = null;
            recStartNs = 0L;
            stopRollSensor();
            setModeUi();
            setStatus("Tidak dapat memulai rekaman");
            toast("Gagal memulai video: " + readableError(e));
        }
    }

    private void startRollSensor() {
        if (sensorManager == null || rollSensorActive) return;
        Sensor s = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        gravityIsAccel = false;
        if (s == null) {
            s = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gravityIsAccel = true;
        }
        if (s == null) {
            recHorizon = false;
            toast("HP ini tidak punya sensor gravitasi, kunci horizon tidak tersedia.");
            return;
        }
        lpInit = false;
        rollSensorActive = sensorManager.registerListener(rollListener, s, SensorManager.SENSOR_DELAY_GAME);
    }

    private void stopRollSensor() {
        if (sensorManager != null && rollSensorActive) {
            sensorManager.unregisterListener(rollListener);
            rollSensorActive = false;
        }
    }

    /** Sisi terpanjang video asli, dipakai untuk memilih bitrate hasil. */
    private int videoLongSide(Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            String w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            int wi = w == null ? 0 : Integer.parseInt(w);
            int hi = h == null ? 0 : Integer.parseInt(h);
            return Math.max(wi, hi);
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private void postProcessVideo(Uri rawVideo, String id, ExportPlan plan) {
        if (isProcessingVideo) {
            setStatus("Video asli tersimpan • proses video lain masih berjalan");
            return;
        }
        isProcessingVideo = true;
        if (plan.stabMode == VideoStabilizer.OFF) {
            setStatus("Video direkam • menerapkan warna " + plan.look.name + "…");
        } else {
            setStatus("Video direkam • menganalisis gerakan (" + (plan.stabMode == VideoStabilizer.MAX ? "Maks" : "Standar") + ")…");
        }
        processingExecutor.execute(() -> {
            VideoStabilizer.MotionPath path = null;
            String note = null;
            if (plan.stabMode != VideoStabilizer.OFF) {
                try {
                    path = VideoStabilizer.analyze(this, rawVideo, plan.stabMode);
                } catch (Exception | OutOfMemoryError e) {
                    note = "Stabilisasi dilewati pada klip ini (" + e.getClass().getSimpleName() + "). Warna tetap diterapkan.";
                }
            }
            float aspect = path != null ? path.width / (float) path.height : VideoStabilizer.frameAspect(this, rawVideo);
            int longSide = videoLongSide(rawVideo);
            final int bitrate = longSide >= 1700 ? 16_000_000 : 10_000_000;
            final VideoStabilizer.MotionPath finalPath = path;
            final float finalAspect = aspect;
            final String finalNote = note;
            runOnUiThread(() -> {
                if (finalNote != null) toast(finalNote);
                startVideoExport(rawVideo, id, plan, finalPath, finalAspect, bitrate);
            });
        });
    }

    private void startVideoExport(Uri rawVideo, String id, ExportPlan plan, VideoStabilizer.MotionPath path,
                                  float aspect, int bitrate) {
        File output = new File(getCacheDir(), "RKCamera_stable_" + id + ".mp4");
        if (output.exists()) output.delete();
        setStatus("Merender video hasil…");

        int effectiveMode = path != null ? plan.stabMode : VideoStabilizer.OFF;
        boolean hasRoll = plan.horizon && plan.rollTimeline != null && plan.rollTimeline.size() >= 2;
        List<androidx.media3.common.Effect> videoEffects = new ArrayList<>();
        if (effectiveMode != VideoStabilizer.OFF || hasRoll || plan.zoomTimeline != null) {
            MatrixTransformation geometry = VideoStabilizer.transformation(path, effectiveMode,
                    hasRoll ? plan.rollTimeline : null, plan.zoomTimeline, aspect);
            videoEffects.add(geometry);
        }
        // Warna bergaya iPhone: 3D LUT dari fungsi yang sama dengan foto.
        try {
            videoEffects.add(SingleColorLut.createFromCube(plan.look.buildCube(33)));
        } catch (Exception ignored) {
            // Tanpa LUT, video tetap diproses dengan warna asli kamera.
        }
        Effects effects = new Effects(Collections.emptyList(), videoEffects);
        EditedMediaItem editedItem = new EditedMediaItem.Builder(MediaItem.fromUri(rawVideo))
                .setEffects(effects)
                .build();
        DefaultEncoderFactory encoderFactory = new DefaultEncoderFactory.Builder(getApplicationContext())
                .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                .build();
        activeTransformer = new Transformer.Builder(getApplicationContext())
                .setEncoderFactory(encoderFactory)
                .addListener(new Transformer.Listener() {
                    @Override public void onCompleted(Composition composition, ExportResult exportResult) {
                        processingExecutor.execute(() -> {
                            boolean published = false;
                            Uri stableUri = null;
                            try {
                                stableUri = publishFile(output, "RKCamera_STABLE_" + id + ".mp4", "video/mp4", false);
                                published = stableUri != null;
                                if (published) getContentResolver().delete(rawVideo, null, null);
                            } catch (Exception ignored) { }
                            final boolean done = published;
                            final Uri resultUri = stableUri;
                            if (output.exists()) output.delete();
                            runOnUiThread(() -> {
                                isProcessingVideo = false;
                                activeTransformer = null;
                                if (done) {
                                    lastSavedUri = resultUri;
                                    lastSavedMime = "video/mp4";
                                    loadThumb(resultUri, true);
                                    setStatus("Video tersimpan di Galeri");
                                    toast("Selesai. Video hasil telah disimpan di Galeri.");
                                } else {
                                    setStatus("Video asli tetap tersimpan • gagal menyalin hasil olahan");
                                    toast("Video asli tidak dihapus. Hasil olahan gagal disimpan.");
                                }
                            });
                        });
                    }
                    @Override public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                        isProcessingVideo = false;
                        if (output.exists()) output.delete();
                        runOnUiThread(() -> {
                            activeTransformer = null;
                            setStatus("Video asli tersimpan • encoder tidak mendukung pemrosesan ini");
                            toast("Video asli aman. Pemrosesan gagal pada perangkat ini.");
                        });
                    }
                }).build();
        try {
            activeTransformer.start(editedItem, output.getAbsolutePath());
        } catch (Exception e) {
            isProcessingVideo = false;
            if (output.exists()) output.delete();
            setStatus("Video asli tersimpan • ekspor gagal");
            toast("Video asli aman; tidak dapat merender hasil pada perangkat ini.");
        }
    }

    // ------------------------------------------------------------------ penyimpanan & galeri

    /** Salin file hasil olahan ke galeri publik. Sumber dibiarkan utuh bila gagal. */
    private Uri publishFile(File source, String displayName, String mimeType, boolean image) throws Exception {
        if (source == null || !source.exists() || source.length() == 0) return null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, image ? Environment.DIRECTORY_DCIM + "/RK Camera AI" : Environment.DIRECTORY_MOVIES + "/RK Camera AI");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        Uri collection = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        Uri result = getContentResolver().insert(collection, values);
        if (result == null) return null;
        boolean successful = false;
        try (InputStream in = new FileInputStream(source); OutputStream out = getContentResolver().openOutputStream(result, "w")) {
            if (out == null) throw new IllegalStateException("Tidak dapat membuka tujuan penyimpanan");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            successful = true;
        } finally {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.MediaColumns.IS_PENDING, successful ? 0 : 1);
                getContentResolver().update(result, finish, null, null);
            }
            if (!successful) getContentResolver().delete(result, null, null);
        }
        return successful ? result : null;
    }

    private void loadLatestThumb() {
        thumbExecutor.execute(() -> {
            Uri bestUri = null;
            boolean bestVideo = false;
            long bestTime = -1L;
            for (int k = 0; k < 2; k++) {
                Uri collection = k == 0 ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                try (Cursor c = getContentResolver().query(collection,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED},
                        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{"RKCamera_%"},
                        MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                    if (c != null && c.moveToFirst()) {
                        long t = c.getLong(1);
                        if (t > bestTime) {
                            bestTime = t;
                            bestUri = ContentUris.withAppendedId(collection, c.getLong(0));
                            bestVideo = k == 1;
                        }
                    }
                } catch (Exception ignored) { }
            }
            if (bestUri != null) {
                if (lastSavedUri == null) {
                    lastSavedUri = bestUri;
                    lastSavedMime = bestVideo ? "video/mp4" : "image/jpeg";
                }
                loadThumb(bestUri, bestVideo);
            }
        });
    }

    /** Muat thumbnail kecil untuk tombol galeri (di thread terpisah). */
    private void loadThumb(Uri uri, boolean video) {
        if (uri == null) return;
        thumbExecutor.execute(() -> {
            Bitmap bmp = null;
            try {
                if (video) {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try {
                        r.setDataSource(this, uri);
                        bmp = r.getFrameAtTime(0);
                    } finally {
                        try { r.release(); } catch (Exception ignored) { }
                    }
                    if (bmp != null) {
                        float k = Math.min(1f, 256f / Math.max(bmp.getWidth(), bmp.getHeight()));
                        if (k < 1f) {
                            Bitmap small = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)),
                                    Math.max(1, Math.round(bmp.getHeight() * k)), true);
                            if (small != bmp) bmp.recycle();
                            bmp = small;
                        }
                    }
                } else {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in != null) BitmapFactory.decodeStream(in, null, bounds);
                    }
                    int sample = 1;
                    while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2;
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = sample;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in != null) bmp = BitmapFactory.decodeStream(in, null, opts);
                    }
                    int degrees = 0;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in != null) {
                            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                            if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                            else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                            else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
                        }
                    } catch (Exception ignored) { }
                    if (bmp != null && degrees != 0) {
                        Matrix m = new Matrix();
                        m.postRotate(degrees);
                        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        if (rotated != bmp) bmp.recycle();
                        bmp = rotated;
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                bmp = null;
            }
            final Bitmap result = bmp;
            runOnUiThread(() -> {
                if (result != null && thumbView != null) thumbView.setImageBitmap(result);
            });
        });
    }

    private void openLastMediaOrPicker() {
        // Galeri/editor internal menjadi satu pintu untuk melihat dan mengolah hasil kamera.
        try {
            startActivity(new Intent(this, MediaStudioActivity.class));
        } catch (Exception e) {
            toast("Studio media belum dapat dibuka: " + readableError(e));
        }
    }

    @Override protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopRollSensor();
        boolean wasRecording = recording != null;
        if (recording != null) {
            try { recording.stop(); } catch (Exception ignored) { }
            recording = null;
        }
        thumbExecutor.shutdown();
        // Biarkan worker hidup bila masih ada finalisasi rekaman atau ekspor yang berjalan.
        if (!isProcessingVideo && !wasRecording) processingExecutor.shutdown();
        super.onDestroy();
    }
}
