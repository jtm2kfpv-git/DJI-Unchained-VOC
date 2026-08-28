package local.n3view.voc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import local.n3view.voc.protocol.H264AnnexBAssembler;

public final class MainActivity extends Activity implements
        UsbAccessoryController.Listener,
        AvcSurfaceDecoder.Listener,
        H264Recorder.Listener,
        SurfaceHolder.Callback {

    private static final String TAG = "N3LocalView";
    private static final String PREFERENCES = "viewer_preferences";
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_SOURCE_ASPECT = "source_aspect";
    private static final String PREF_AUTO_RECONNECT = "auto_reconnect";
    private static final String PREF_KEEP_AWAKE = "keep_awake";
    private static final String PREF_SHORTS_CROP = "shorts_crop";
    private static final int CREATE_DIAGNOSTICS_REQUEST = 40;
    private static final int CREATE_RECORDING_REQUEST = 41;
    private static final int CREATE_SHORTS_REQUEST = 42;
    private static final int SCREEN_CAPTURE_REQUEST = 43;

    private FrameLayout root;
    private FrameLayout videoViewport;
    private ClickableSurfaceView surfaceView;
    private ImageView logoBackdrop;
    private LinearLayout panel;
    private TextView identityView;
    private TextView connectionView;
    private TextView statusView;
    private TextView statsView;
    private TextView healthView;
    private TextView recordingView;
    private TextView setupView;
    private Button displayModeButton;
    private Button aspectButton;
    private Button autoReconnectButton;
    private Button keepAwakeButton;
    private Button recordingButton;
    private UsbAccessoryController usb;
    private AvcSurfaceDecoder videoDecoder;
    private H264Recorder recorder;
    private final H264AnnexBAssembler assembler = new H264AnnexBAssembler();
    private final StreamWatchdog watchdog = new StreamWatchdog();
    private SharedPreferences preferences;
    private volatile DisplayGeometry.Mode displayMode;
    private volatile DisplayGeometry.SourceAspect sourceAspect;
    private volatile boolean autoReconnect;
    private volatile boolean keepAwake;
    private volatile float shortsCrop;
    private float touchStartX;
    private float touchStartCrop;
    private boolean touchMoved;
    private volatile int sourceWidth = 16;
    private volatile int sourceHeight = 9;
    private long previousStatsAt;
    private long previousVideoBytes;
    private long previousRenderedFrames;
    private volatile long latestVideoPackets;
    private volatile long latestRenderedFrames;
    private volatile boolean surfaceReady;
    private volatile boolean streamActive;
    private volatile long lastRenderedFrameAt;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable backdropWatch = new Runnable() {
        @Override
        public void run() {
            updateBackdropVisibility();
            updateShortsCaptureUi();
            if (!destroyed) {
                uiHandler.postDelayed(this, 500);
            }
        }
    };
    private volatile UsbAccessoryController.State connectionState =
            UsbAccessoryController.State.DISCONNECTED;
    private volatile StreamWatchdog.Evaluation latestHealth =
            new StreamWatchdog.Evaluation(StreamWatchdog.Action.NONE, 0, 0, 0);
    private volatile String lastIdentity = "No Android USB accessory detected";
    private volatile String lastStatus = "Ready";
    private volatile String lastStats = "No video packets yet";
    private volatile H264Recorder.Snapshot recordingSnapshot = new H264Recorder.Snapshot(
            H264Recorder.State.IDLE, 0, 0, 0, 0, "Not recording");
    private volatile boolean destroyed;
    private Uri pendingShortsUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        displayMode = readDisplayMode(preferences.getString(PREF_DISPLAY_MODE, null));
        sourceAspect = readSourceAspect(preferences.getString(PREF_SOURCE_ASPECT, null));
        autoReconnect = preferences.getBoolean(PREF_AUTO_RECONNECT, false);
        keepAwake = preferences.getBoolean(PREF_KEEP_AWAKE, true);
        shortsCrop = preferences.getFloat(PREF_SHORTS_CROP, 0f);
        applyKeepAwake();
        recorder = new H264Recorder(this);

        buildUi();
        enterImmersiveMode();

        videoDecoder = new AvcSurfaceDecoder(this);
        usb = new UsbAccessoryController(this, this);
        usb.setAutoReconnect(autoReconnect);
        usb.start(getIntent());
        resetStatsBaseline(0, 0);
        uiHandler.post(backdropWatch);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (usb != null) {
            usb.handleIntent(intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_DIAGNOSTICS_REQUEST
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            writeDiagnostics(data.getData());
        } else if (requestCode == CREATE_RECORDING_REQUEST
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            startRecording(data.getData());
        } else if (requestCode == CREATE_SHORTS_REQUEST
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            pendingShortsUri = data.getData();
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
        } else if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && pendingShortsUri != null) {
                startShortsCapture(resultCode, data, pendingShortsUri);
            } else {
                onStatus("Shorts recording permission was not granted");
            }
            pendingShortsUri = null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        uiHandler.removeCallbacks(backdropWatch);
        stopShortsCapture();
        if (recorder != null) {
            recorder.close();
        }
        if (usb != null) {
            usb.close();
        }
        if (videoDecoder != null) {
            videoDecoder.close();
        }
        assembler.reset();
        super.onDestroy();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        watchdog.resetBaseline(
                SystemClock.elapsedRealtime(), latestVideoPackets, latestRenderedFrames);
        videoDecoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // MediaCodec renders directly to the Surface; its View controls presentation geometry.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        videoDecoder.clearSurface();
    }

    @Override
    public void onAccessoryIdentity(String identity) {
        lastIdentity = identity;
        Log.i(TAG, "USB accessory: " + identity);
        runOnUiThread(() -> identityView.setText(identity));
        runOnUiThread(this::updateSetupUi);
    }

    @Override
    public void onTransportReset() {
        if (recorder != null) {
            recorder.stop("USB transport reset; recording closed safely");
        }
        latestVideoPackets = 0;
        latestRenderedFrames = 0;
        lastRenderedFrameAt = 0;
        streamActive = false;
        watchdog.setActive(false, SystemClock.elapsedRealtime(), 0, 0);
        assembler.reset();
        if (videoDecoder != null) {
            videoDecoder.resetStream();
        }
        runOnUiThread(this::updateBackdropVisibility);
        resetStatsBaseline(0, 0);
    }

    @Override
    public void onStatus(String message) {
        lastStatus = message;
        Log.i(TAG, message);
        runOnUiThread(() -> statusView.setText(message));
    }

    @Override
    public void onConnectionState(
            UsbAccessoryController.State state,
            int retryAttempt,
            long retryDelayMillis) {
        connectionState = state;
        streamActive = state == UsbAccessoryController.State.CONNECTED
                || state == UsbAccessoryController.State.STREAMING;
        watchdog.setActive(streamActive, SystemClock.elapsedRealtime(),
                latestVideoPackets, latestRenderedFrames);
        String value = switch (state) {
            case DISCONNECTED -> "Connection: disconnected";
            case WAITING_PERMISSION -> "Connection: waiting for USB permission";
            case CONNECTING -> "Connection: opening USB accessory";
            case CONNECTED -> "Connection: USB open; waiting for video";
            case STREAMING -> "Connection: streaming";
            case RETRY_WAIT -> String.format(Locale.ROOT,
                    "Connection: retry %d in %.1f s",
                    retryAttempt, retryDelayMillis / 1_000.0);
        };
        runOnUiThread(() -> connectionView.setText(value));
        runOnUiThread(this::updateSetupUi);
    }

    @Override
    public void onVideo(byte[] bytes) {
        assembler.accept(bytes, unit -> {
            videoDecoder.queue(unit);
            recorder.accept(unit);
        });
    }

    @Override
    public void onRecordingChanged(H264Recorder.Snapshot snapshot) {
        recordingSnapshot = snapshot;
        if (destroyed) {
            return;
        }
        if (snapshot.state() == H264Recorder.State.ERROR) {
            onStatus(snapshot.message());
        }
        runOnUiThread(() -> updateRecordingUi(snapshot));
    }

    @Override
    public synchronized void onStats(long videoBytes, long videoPackets, long discardedBytes) {
        AvcSurfaceDecoder.Stats decoderStats = videoDecoder.stats();
        long now = SystemClock.elapsedRealtime();
        long elapsedMs = Math.max(1, now - previousStatsAt);
        long byteDelta = Math.max(0, videoBytes - previousVideoBytes);
        long renderedDelta = Math.max(0, decoderStats.rendered() - previousRenderedFrames);
        double megabitsPerSecond = byteDelta * 8.0 / elapsedMs / 1_000.0;
        double framesPerSecond = renderedDelta * 1_000.0 / elapsedMs;

        previousStatsAt = now;
        previousVideoBytes = videoBytes;
        previousRenderedFrames = decoderStats.rendered();
        latestVideoPackets = videoPackets;
        latestRenderedFrames = decoderStats.rendered();

        StreamWatchdog.Evaluation health = watchdog.evaluate(
                now, videoPackets, decoderStats.rendered(), autoReconnect, surfaceReady);
        latestHealth = health;

        String value = String.format(Locale.ROOT,
                "%dx%d  %s  %.2f Mbit/s  %.1f fps  packets=%d  dropped=%d  resync=%d",
                sourceWidth, sourceHeight, displayMode, megabitsPerSecond, framesPerSecond,
                videoPackets, decoderStats.dropped(), discardedBytes);
        lastStats = value;
        String healthText = streamActive
                ? String.format(Locale.ROOT,
                        "last packet %.1f s  last frame %.1f s  recovery actions=%d",
                        health.packetAgeMillis() / 1_000.0,
                        health.frameAgeMillis() / 1_000.0,
                        health.recoveryActions())
                : "Stream health: inactive";
        runOnUiThread(() -> {
            statsView.setText(value);
            healthView.setText(healthText);
            updateSetupUi();
            updateRecordingUi(recorder.snapshot());
            performWatchdogAction(health.action());
        });
    }

    @Override
    public void onFailure(String message, Throwable error) {
        Log.e(TAG, message, error);
        onStatus(message + ": " + concise(error));
    }

    @Override
    public void onDecoderStatus(String message) {
        onStatus(message);
    }

    @Override
    public void onDecoderFailure(String message, Throwable error) {
        Log.e(TAG, message, error);
        onStatus(message + ": " + concise(error));
    }

    @Override
    public void onVideoFormat(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        runOnUiThread(() -> {
            sourceWidth = width;
            sourceHeight = height;
            updateSurfaceLayout();
        });
    }

    @Override
    public void onFrameRendered(long renderedFrames) {
        latestRenderedFrames = renderedFrames;
        lastRenderedFrameAt = SystemClock.elapsedRealtime();
        if (!destroyed) {
            runOnUiThread(() -> {
                updateBackdropVisibility();
                updateSetupUi();
            });
        }
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(true);
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateSurfaceLayout());

        videoViewport = new FrameLayout(this);
        videoViewport.setBackgroundColor(Color.BLACK);
        videoViewport.setClipChildren(true);
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(videoViewport, viewportParams);

        surfaceView = new ClickableSurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setClickable(true);
        surfaceView.setContentDescription("Video display; tap to show or hide controls");
        surfaceView.setGestureHandler(this::handleVideoTouch);
        surfaceView.setOnClickListener(view -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            enterImmersiveMode();
        });
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        videoViewport.addView(surfaceView, surfaceParams);

        logoBackdrop = new ImageView(this);
        logoBackdrop.setImageResource(R.drawable.n3_logo_v3_background);
        logoBackdrop.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        logoBackdrop.setBackgroundColor(Color.BLACK);
        logoBackdrop.setContentDescription("DJI Unchained VOC Logo v3; waiting for video input");
        root.addView(logoBackdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackgroundColor(0xC914222E);

        TextView title = text(
                "DJI UNCHAINED VOC " + BuildConfig.VERSION_NAME + " - OFFLINE",
                16,
                Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        identityView = text("No Android USB accessory detected", 12, 0xFFB8C8D4);
        panel.addView(identityView);

        connectionView = text("Connection: disconnected", 12, 0xFFFFCC66);
        panel.addView(connectionView);

        statusView = text("Ready. Connect Goggles N3, then press Connect.", 13, 0xFF64E6D9);
        panel.addView(statusView);

        statsView = text("No video packets yet", 11, 0xFFD5DEE5);
        panel.addView(statsView);

        healthView = text("Stream health: inactive", 11, 0xFFFFCC66);
        panel.addView(healthView);

        recordingView = text("Recording: idle", 11, 0xFFB8C8D4);
        panel.addView(recordingView);

        setupView = text("Setup: ○ USB  ○ packets  ○ decoder  ○ frame", 11, 0xFFB8C8D4);
        panel.addView(setupView);

        LinearLayout connectionButtons = new LinearLayout(this);
        connectionButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button connect = new Button(this);
        connect.setText(R.string.connect);
        connect.setOnClickListener(view -> {
            resetStatsBaseline(0, 0);
            usb.connectFirst();
        });
        connectionButtons.addView(connect);

        Button disconnect = new Button(this);
        disconnect.setText(R.string.disconnect);
        disconnect.setOnClickListener(view -> usb.disconnect());
        connectionButtons.addView(disconnect);
        panel.addView(connectionButtons);

        LinearLayout recoveryButtons = new LinearLayout(this);
        recoveryButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button recover = new Button(this);
        recover.setText(R.string.recover_now);
        recover.setOnClickListener(view -> {
            watchdog.resetBaseline(SystemClock.elapsedRealtime(),
                    latestVideoPackets, latestRenderedFrames);
            usb.recoverNow();
        });
        recoveryButtons.addView(recover);
        panel.addView(recoveryButtons);

        LinearLayout mediaButtons = new LinearLayout(this);
        mediaButtons.setOrientation(LinearLayout.HORIZONTAL);
        recordingButton = new Button(this);
        recordingButton.setText(R.string.start_recording);
        recordingButton.setOnClickListener(view -> toggleRecording());
        mediaButtons.addView(recordingButton);

        Button exportDiagnostics = new Button(this);
        exportDiagnostics.setText(R.string.export_diagnostics);
        exportDiagnostics.setOnClickListener(view -> requestDiagnosticsExport());
        mediaButtons.addView(exportDiagnostics);
        panel.addView(mediaButtons);

        LinearLayout optionButtons = new LinearLayout(this);
        optionButtons.setOrientation(LinearLayout.HORIZONTAL);

        displayModeButton = new Button(this);
        displayModeButton.setOnClickListener(view -> {
            displayMode = displayMode.next();
            preferences.edit().putString(PREF_DISPLAY_MODE, displayMode.name()).apply();
            updateOptionButtons();
            updateSurfaceLayout();
        });
        optionButtons.addView(displayModeButton);

        aspectButton = new Button(this);
        aspectButton.setOnClickListener(view -> {
            sourceAspect = sourceAspect.next();
            preferences.edit().putString(PREF_SOURCE_ASPECT, sourceAspect.name()).apply();
            if (sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16) {
                onStatus("Shorts preview: rotate the device portrait; outer image is blocked");
            }
            updateOptionButtons();
            updateSurfaceLayout();
        });
        optionButtons.addView(aspectButton);

        autoReconnectButton = new Button(this);
        autoReconnectButton.setOnClickListener(view -> {
            autoReconnect = !autoReconnect;
            watchdog.resetBaseline(SystemClock.elapsedRealtime(),
                    latestVideoPackets, latestRenderedFrames);
            preferences.edit().putBoolean(PREF_AUTO_RECONNECT, autoReconnect).apply();
            usb.setAutoReconnect(autoReconnect);
            updateOptionButtons();
        });
        optionButtons.addView(autoReconnectButton);
        panel.addView(optionButtons);

        LinearLayout powerButtons = new LinearLayout(this);
        powerButtons.setOrientation(LinearLayout.HORIZONTAL);
        keepAwakeButton = new Button(this);
        keepAwakeButton.setOnClickListener(view -> {
            keepAwake = !keepAwake;
            preferences.edit().putBoolean(PREF_KEEP_AWAKE, keepAwake).apply();
            applyKeepAwake();
            updateOptionButtons();
        });
        powerButtons.addView(keepAwakeButton);
        panel.addView(powerButtons);

        TextView hint = text("Tap the video to hide/show this panel", 11, 0xFFB8C8D4);
        panel.addView(hint);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        panelParams.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(panel, panelParams);

        updateOptionButtons();
        setContentView(root);
    }

    private void updateOptionButtons() {
        displayModeButton.setText(getString(R.string.display_mode, displayMode.name()));
        displayModeButton.setEnabled(sourceAspect != DisplayGeometry.SourceAspect.SHORTS_9_16);
        aspectButton.setText(getString(R.string.source_aspect, sourceAspect.label()));
        String reconnectState = getString(autoReconnect ? R.string.state_on : R.string.state_off);
        autoReconnectButton.setText(getString(R.string.auto_reconnect, reconnectState));
        String awakeState = getString(keepAwake ? R.string.state_on : R.string.state_off);
        keepAwakeButton.setText(getString(R.string.keep_awake, awakeState));
        updateShortsCaptureUi();
    }

    private void performWatchdogAction(StreamWatchdog.Action action) {
        if (action == StreamWatchdog.Action.NONE
                || !autoReconnect || !streamActive || !surfaceReady) {
            return;
        }
        switch (action) {
            case RESEND_KEEPALIVE -> {
                if (usb.resendKeepalive()) {
                    onStatus("Stream stalled: resent the proven N3 keepalive packets");
                }
            }
            case RESET_DECODER -> {
                videoDecoder.resetStream();
                latestRenderedFrames = 0;
                resetStatsBaseline(previousVideoBytes, 0);
                onStatus("Stream still stalled: restarted the local H.264 decoder");
            }
            case REOPEN_USB -> usb.recoverStalledStream();
            case NONE -> {
                // Handled by the guard above.
            }
        }
    }

    private void requestDiagnosticsExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE,
                        "dji-unchained-voc-" + BuildConfig.VERSION_NAME + "-diagnostics.txt");
        startActivityForResult(intent, CREATE_DIAGNOSTICS_REQUEST);
    }

    private void toggleRecording() {
        if (sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16) {
            toggleShortsCapture();
            return;
        }
        H264Recorder.Snapshot snapshot = recorder.snapshot();
        if (snapshot.active()) {
            recorder.stop("Stopped by user");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/h264")
                .putExtra(Intent.EXTRA_TITLE, recordingFileName());
        startActivityForResult(intent, CREATE_RECORDING_REQUEST);
    }

    private void toggleShortsCapture() {
        if (ShortsCaptureService.isActive()) {
            stopShortsCapture();
            panel.setVisibility(View.VISIBLE);
            onStatus("Finishing 9:16 Shorts MP4");
            return;
        }
        if (allocatableBytes() < 500L * 1024 * 1024) {
            onStatus("Shorts recording blocked: less than 500 MB usable local storage");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_TITLE, shortsFileName());
        startActivityForResult(intent, CREATE_SHORTS_REQUEST);
    }

    private void startShortsCapture(int resultCode, Intent permissionData, Uri outputUri) {
        panel.setVisibility(View.GONE);
        Intent service = ShortsCaptureService.startIntent(
                this, resultCode, permissionData, outputUri, 720, 1280,
                getResources().getDisplayMetrics().densityDpi);
        startForegroundService(service);
        onStatus("Starting experimental 720x1280 Shorts recording");
    }

    private void stopShortsCapture() {
        if (ShortsCaptureService.isActive()) {
            startService(ShortsCaptureService.stopIntent(this));
        }
    }

    private void updateShortsCaptureUi() {
        if (recordingButton == null || recordingView == null) {
            return;
        }
        ShortsCaptureService.State state = ShortsCaptureService.state();
        if (state == ShortsCaptureService.State.IDLE) {
            if (sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16
                    && !recorder.snapshot().active()) {
                recordingButton.setText(R.string.start_shorts_recording);
            }
            return;
        }
        recordingButton.setText(state == ShortsCaptureService.State.STOPPING
                ? R.string.finishing_recording : R.string.stop_shorts_recording);
        recordingView.setText(ShortsCaptureService.message());
        recordingView.setTextColor(state == ShortsCaptureService.State.ERROR
                ? 0xFFFF5252 : 0xFFFFCC66);
    }

    private void startRecording(Uri uri) {
        try {
            OutputStream output = getContentResolver().openOutputStream(uri, "w");
            if (output == null) {
                onStatus("Could not open the selected recording file");
                return;
            }
            if (!recorder.start(output)) {
                output.close();
                onStatus("Recorder is still closing the previous file");
            } else {
                onStatus("Recording armed; waiting for the next H.264 keyframe");
            }
        } catch (IOException error) {
            onFailure("Could not create recording", error);
        }
    }

    private void updateRecordingUi(H264Recorder.Snapshot snapshot) {
        recordingSnapshot = snapshot;
        long elapsedMillis = snapshot.startedAtMillis() == 0
                ? 0
                : Math.max(0, System.nanoTime() / 1_000_000 - snapshot.startedAtMillis());
        String value;
        int color;
        switch (snapshot.state()) {
            case WAITING_FOR_KEYFRAME -> {
                value = "REC armed: waiting for SPS/PPS and keyframe";
                color = 0xFFFFCC66;
            }
            case RECORDING -> {
                value = String.format(Locale.ROOT,
                        "● REC %s  %.1f MB  access units=%d",
                        formatDuration(elapsedMillis),
                        snapshot.bytesWritten() / 1_000_000.0,
                        snapshot.accessUnitsWritten());
                color = 0xFFFF5252;
            }
            case STOPPING -> {
                value = "Recording: finishing file safely";
                color = 0xFFFFCC66;
            }
            case ERROR -> {
                value = snapshot.message();
                color = 0xFFFF5252;
            }
            case IDLE -> {
                value = snapshot.bytesWritten() > 0
                        ? String.format(Locale.ROOT, "Recording saved: %.1f MB",
                                snapshot.bytesWritten() / 1_000_000.0)
                        : "Recording: idle";
                color = 0xFFB8C8D4;
            }
            default -> throw new IllegalStateException("Unhandled recorder state");
        }
        recordingView.setText(value);
        recordingView.setTextColor(color);
        if (!ShortsCaptureService.isActive()) {
            recordingButton.setText(snapshot.active()
                    ? R.string.stop_recording
                    : sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16
                            ? R.string.start_shorts_recording : R.string.start_recording);
        }
    }

    private static String formatDuration(long elapsedMillis) {
        long totalSeconds = elapsedMillis / 1_000;
        return String.format(Locale.ROOT, "%02d:%02d",
                totalSeconds / 60, totalSeconds % 60);
    }

    private void writeDiagnostics(Uri uri) {
        String report = buildDiagnostics();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                onStatus("Could not open the selected diagnostics file");
                return;
            }
            output.write(report.getBytes(StandardCharsets.UTF_8));
            output.flush();
            onStatus("Diagnostics exported to the selected file");
        } catch (IOException error) {
            onFailure("Could not export diagnostics", error);
        }
    }

    private String buildDiagnostics() {
        StreamWatchdog.Evaluation health = latestHealth;
        H264Recorder.Snapshot recording = recordingSnapshot;
        return "DJI Unchained VOC " + BuildConfig.VERSION_NAME + " diagnostics\n"
                + "generated=" + Instant.now() + "\n"
                + "android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "connection=" + connectionState + "\n"
                + "accessory=" + lastIdentity + "\n"
                + "status=" + lastStatus + "\n"
                + "stats=" + lastStats + "\n"
                + "last_packet_ms=" + health.packetAgeMillis() + "\n"
                + "last_frame_ms=" + health.frameAgeMillis() + "\n"
                + "recovery_actions=" + health.recoveryActions() + "\n"
                + "auto_reconnect=" + autoReconnect + "\n"
                + "display_mode=" + displayMode + "\n"
                + "source_aspect=" + sourceAspect + "\n"
                + "keep_awake=" + keepAwake + "\n"
                + "decoder=" + videoDecoder.decoderName() + "\n"
                + "allocatable_bytes=" + allocatableBytes() + "\n"
                + "recording_state=" + recording.state() + "\n"
                + "recording_bytes=" + recording.bytesWritten() + "\n"
                + "recording_access_units=" + recording.accessUnitsWritten() + "\n"
                + "recording_dropped_units=" + recording.droppedUnits() + "\n"
                + "recording_message=" + recording.message() + "\n"
                + "privacy=no serial, account, location or network data collected\n";
    }

    private void updateSurfaceLayout() {
        if (root == null || videoViewport == null || surfaceView == null
                || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        boolean shorts = sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16;
        DisplayGeometry.Size viewportSize = shorts
                ? DisplayGeometry.shortsViewport(root.getWidth(), root.getHeight())
                : new DisplayGeometry.Size(root.getWidth(), root.getHeight());
        FrameLayout.LayoutParams viewportParams =
                (FrameLayout.LayoutParams) videoViewport.getLayoutParams();
        viewportParams.width = viewportSize.width();
        viewportParams.height = viewportSize.height();
        viewportParams.gravity = Gravity.CENTER;
        videoViewport.setLayoutParams(viewportParams);

        DisplayGeometry.Size effectiveSource = shorts
                ? new DisplayGeometry.Size(Math.max(1, sourceWidth), Math.max(1, sourceHeight))
                : DisplayGeometry.sourceSize(sourceWidth, sourceHeight, sourceAspect);
        DisplayGeometry.Mode effectiveMode = shorts ? DisplayGeometry.Mode.FILL : displayMode;
        DisplayGeometry.Size size = DisplayGeometry.calculate(
                viewportSize.width(), viewportSize.height(),
                effectiveSource.width(), effectiveSource.height(), effectiveMode);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (params.width != size.width() || params.height != size.height()
                || params.gravity != Gravity.CENTER) {
            params.width = size.width();
            params.height = size.height();
            params.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(params);
        }
        if (shorts) {
            float maximum = Math.max(0f, (size.width() - viewportSize.width()) / 2f);
            surfaceView.setTranslationX(shortsCrop * maximum);
        } else {
            surfaceView.setTranslationX(0f);
        }
        panel.bringToFront();
    }

    private boolean handleVideoTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchStartX = event.getRawX();
            touchStartCrop = shortsCrop;
            touchMoved = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                && sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16) {
            float maximum = Math.max(1f,
                    (surfaceView.getWidth() - videoViewport.getWidth()) / 2f);
            float delta = (event.getRawX() - touchStartX) / maximum;
            shortsCrop = Math.max(-1f, Math.min(1f, touchStartCrop + delta));
            touchMoved |= Math.abs(event.getRawX() - touchStartX) > dp(8);
            surfaceView.setTranslationX(shortsCrop * maximum);
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (touchMoved) {
                preferences.edit().putFloat(PREF_SHORTS_CROP, shortsCrop).apply();
                onStatus(String.format(Locale.ROOT,
                        "Shorts crop position: %+.0f%%", shortsCrop * 100));
            }
            enterImmersiveMode();
            return touchMoved;
        }
        return event.getActionMasked() == MotionEvent.ACTION_CANCEL;
    }

    private void updateBackdropVisibility() {
        if (logoBackdrop == null) {
            return;
        }
        long age = lastRenderedFrameAt == 0
                ? Long.MAX_VALUE
                : SystemClock.elapsedRealtime() - lastRenderedFrameAt;
        boolean show = !streamActive || age > 3_000;
        logoBackdrop.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            logoBackdrop.bringToFront();
            panel.bringToFront();
        }
    }

    private void updateSetupUi() {
        if (setupView == null) {
            return;
        }
        boolean accessory = !lastIdentity.startsWith("No Android");
        boolean usbOpen = connectionState == UsbAccessoryController.State.CONNECTED
                || connectionState == UsbAccessoryController.State.STREAMING;
        boolean packets = latestVideoPackets > 0;
        boolean frame = latestRenderedFrames > 0 && lastRenderedFrameAt > 0;
        setupView.setText(String.format(Locale.ROOT,
                "Setup: %s accessory  %s USB  %s packets  %s frame",
                mark(accessory), mark(usbOpen), mark(packets), mark(frame)));
        setupView.setTextColor(frame ? 0xFF64E6D9 : 0xFFFFCC66);
    }

    private static String mark(boolean complete) {
        return complete ? "✓" : "○";
    }

    private void applyKeepAwake() {
        if (keepAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private long allocatableBytes() {
        StorageManager storage = getSystemService(StorageManager.class);
        try {
            return storage.getAllocatableBytes(StorageManager.UUID_DEFAULT);
        } catch (IOException | SecurityException error) {
            return 0;
        }
    }

    private String recordingFileName() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        String suffix = sourceAspect == DisplayGeometry.SourceAspect.SHORTS_9_16
                ? "-shorts-master" : "";
        return "dji-unchained-voc-" + timestamp + suffix + ".h264";
    }

    private String shortsFileName() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        return "dji-unchained-voc-shorts-" + timestamp + "-720x1280.mp4";
    }

    private synchronized void resetStatsBaseline(long videoBytes, long renderedFrames) {
        previousStatsAt = SystemClock.elapsedRealtime();
        previousVideoBytes = videoBytes;
        previousRenderedFrames = renderedFrames;
    }

    private static DisplayGeometry.Mode readDisplayMode(String stored) {
        if (stored == null) {
            return DisplayGeometry.Mode.FIT;
        }
        try {
            return DisplayGeometry.Mode.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return DisplayGeometry.Mode.FIT;
        }
    }

    private static DisplayGeometry.SourceAspect readSourceAspect(String stored) {
        if (stored == null) {
            return DisplayGeometry.SourceAspect.AUTO;
        }
        try {
            return DisplayGeometry.SourceAspect.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return DisplayGeometry.SourceAspect.AUTO;
        }
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : " - " + message);
    }
}
