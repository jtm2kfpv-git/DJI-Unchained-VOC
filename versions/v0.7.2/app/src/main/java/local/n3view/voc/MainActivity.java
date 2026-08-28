package local.n3view.voc;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;
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
import android.widget.ScrollView;
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
        OriginalStreamRecorder.Listener,
        SurfaceHolder.Callback {

    private static final String TAG = "N3LocalView";
    private static final String PREFERENCES = "viewer_preferences";
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_SOURCE_ASPECT = "source_aspect";
    private static final String PREF_AUTO_RECONNECT = "auto_reconnect";
    private static final String PREF_KEEP_AWAKE = "keep_awake";
    private static final String PREF_SHORTS_CROP = "shorts_crop";
    private static final String PREF_OUTPUT_FORMAT = "output_format";
    private static final String PREF_CAPTURE_FPS = "capture_fps";
    private static final String PREF_ORIGINAL_CONTAINER = "original_container";
    private static final int CREATE_DIAGNOSTICS_REQUEST = 40;
    private static final int CREATE_SHORTS_REQUEST = 42;
    private static final int SCREEN_CAPTURE_REQUEST = 43;

    private FrameLayout root;
    private FrameLayout videoViewport;
    private ClickableSurfaceView surfaceView;
    private ImageView logoBackdrop;
    private ScrollView controlScroll;
    private LinearLayout panel;
    private LinearLayout advancedPanel;
    private TextView statusStripView;
    private TextView identityView;
    private TextView connectionView;
    private TextView statusView;
    private TextView statsView;
    private TextView healthView;
    private TextView recordingView;
    private TextView setupView;
    private Button displayModeButton;
    private Button aspectButton;
    private Button outputFormatButton;
    private Button frameRateButton;
    private Button advancedButton;
    private Button autoReconnectButton;
    private Button keepAwakeButton;
    private Button originalContainerButton;
    private Button recordingButton;
    private UsbAccessoryController usb;
    private AvcSurfaceDecoder videoDecoder;
    private OriginalStreamRecorder recorder;
    private final H264AnnexBAssembler assembler = new H264AnnexBAssembler();
    private final StreamWatchdog watchdog = new StreamWatchdog();
    private final DiagnosticTimeline timeline =
            new DiagnosticTimeline(200, SystemClock::elapsedRealtime);
    private final PerformanceTracker performanceTracker = new PerformanceTracker();
    private SharedPreferences preferences;
    private volatile DisplayGeometry.Mode displayMode;
    private volatile DisplayGeometry.SourceAspect sourceAspect;
    private volatile CaptureProfile.OutputFormat outputFormat;
    private volatile CaptureProfile.FrameRate captureFrameRate;
    private volatile OriginalStreamRecorder.Container originalContainer;
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
    private volatile long latestVideoBytes;
    private volatile long latestVideoPackets;
    private volatile long latestDiscardedBytes;
    private volatile long latestRenderedFrames;
    private volatile long latestQueuedFrames;
    private volatile long latestDecoderDrops;
    private volatile boolean surfaceReady;
    private volatile boolean streamActive;
    private volatile long lastRenderedFrameAt;
    private volatile long lastFrameUiAt;
    private volatile long selectedDestinationAvailableBytes = -1;
    private volatile int transportResetCount;
    private volatile int keepaliveResendCount;
    private volatile int decoderResetCount;
    private volatile int usbReopenCount;
    private volatile int surfaceLossesWhileRecording;
    private volatile OriginalStreamRecorder.State lastRecordedTimelineState =
            OriginalStreamRecorder.State.IDLE;
    private final long sessionStartedAt = SystemClock.elapsedRealtime();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable backdropWatch = new Runnable() {
        @Override
        public void run() {
            updateBackdropVisibility();
            updateShortsCaptureUi();
            updateStatusStrip(performanceTracker.snapshot().fps1Second());
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
    private volatile OriginalStreamRecorder.Snapshot recordingSnapshot =
            new OriginalStreamRecorder.Snapshot(
                    OriginalStreamRecorder.State.IDLE,
                    OriginalStreamRecorder.Container.LOSSLESS_MP4,
                    0, 0, 0, 0, -1, -1, 0, 0, "Not recording");
    private volatile boolean destroyed;
    private Uri pendingShortsUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        displayMode = readDisplayMode(preferences.getString(PREF_DISPLAY_MODE, null));
        sourceAspect = readSourceAspect(preferences.getString(PREF_SOURCE_ASPECT, null));
        outputFormat = readOutputFormat(preferences.getString(PREF_OUTPUT_FORMAT, null));
        captureFrameRate = readCaptureFrameRate(preferences.getString(PREF_CAPTURE_FPS, null));
        originalContainer = readOriginalContainer(
                preferences.getString(PREF_ORIGINAL_CONTAINER, null));
        autoReconnect = preferences.getBoolean(PREF_AUTO_RECONNECT, false);
        keepAwake = preferences.getBoolean(PREF_KEEP_AWAKE, true);
        shortsCrop = preferences.getFloat(PREF_SHORTS_CROP, 0f);
        applyKeepAwake();
        recorder = new OriginalStreamRecorder(getContentResolver(), this);
        timeline.add("app", "created " + BuildConfig.VERSION_NAME);

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
    protected void onStart() {
        super.onStart();
        timeline.add("lifecycle", "foreground start");
    }

    @Override
    protected void onResume() {
        super.onResume();
        timeline.add("lifecycle", "resumed");
    }

    @Override
    protected void onPause() {
        timeline.add("lifecycle", ShortsCaptureService.isActive()
                ? "paused while legacy Shorts capture active" : "paused");
        super.onPause();
    }

    @Override
    protected void onStop() {
        timeline.add("lifecycle", "background stop");
        super.onStop();
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
        } else if (requestCode == CREATE_SHORTS_REQUEST
                && resultCode == RESULT_OK && data != null && data.getData() != null) {
            pendingShortsUri = data.getData();
            selectedDestinationAvailableBytes = destinationAvailableBytes(pendingShortsUri);
            timeline.add("storage", "selected Shorts destination available_bytes="
                    + selectedDestinationAvailableBytes);
            if (selectedDestinationAvailableBytes >= 0
                    && selectedDestinationAvailableBytes < 500L * 1024L * 1024L) {
                onStatus("Shorts recording blocked: selected destination has less than 500 MB");
                pendingShortsUri = null;
                return;
            }
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
        } else if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null && pendingShortsUri != null) {
                startShortsCapture(resultCode, data, pendingShortsUri);
            } else {
                timeline.add("shorts_recording", "screen-capture consent canceled");
                onStatus("Shorts recording permission was not granted");
            }
            pendingShortsUri = null;
        }
    }

    @Override
    protected void onDestroy() {
        timeline.add("app", "destroyed");
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
        timeline.add("surface", "created " + holder.getSurfaceFrame().width()
                + "x" + holder.getSurfaceFrame().height());
        watchdog.resetBaseline(
                SystemClock.elapsedRealtime(), latestVideoPackets, latestRenderedFrames);
        videoDecoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        timeline.add("surface", "changed " + width + "x" + height + " format=" + format);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (recorder != null && recorder.snapshot().active()) {
            surfaceLossesWhileRecording++;
            timeline.add("surface", "destroyed during original recording #"
                    + surfaceLossesWhileRecording);
        }
        timeline.add("surface", "destroyed");
        videoDecoder.clearSurface();
    }

    @Override
    public void onAccessoryIdentity(String identity) {
        lastIdentity = identity;
        timeline.add("usb", "accessory identified: " + identity);
        Log.i(TAG, "USB accessory: " + identity);
        runOnUiThread(() -> identityView.setText(identity));
        runOnUiThread(() -> {
            updateSetupUi();
            updateStatusStrip(performanceTracker.snapshot().fps1Second());
        });
    }

    @Override
    public void onTransportReset() {
        transportResetCount++;
        timeline.add("usb", "transport reset #" + transportResetCount);
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
        timeline.add("status", message);
        Log.i(TAG, message);
        runOnUiThread(() -> statusView.setText(message));
    }

    @Override
    public void onConnectionState(
            UsbAccessoryController.State state,
            int retryAttempt,
            long retryDelayMillis) {
        connectionState = state;
        timeline.add("connection", state + " retry=" + retryAttempt
                + " delay_ms=" + retryDelayMillis);
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
        runOnUiThread(() -> {
            updateSetupUi();
            updateStatusStrip(performanceTracker.snapshot().fps1Second());
        });
    }

    @Override
    public void onVideo(byte[] bytes) {
        assembler.accept(bytes, unit -> {
            videoDecoder.queue(unit);
            recorder.accept(unit);
        });
    }

    @Override
    public void onRecordingChanged(OriginalStreamRecorder.Snapshot snapshot) {
        recordingSnapshot = snapshot;
        if (snapshot.state() != lastRecordedTimelineState) {
            lastRecordedTimelineState = snapshot.state();
            timeline.add("original_recording", snapshot.state() + ": " + snapshot.message());
        }
        if (destroyed) {
            return;
        }
        if (snapshot.state() == OriginalStreamRecorder.State.ERROR) {
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
        latestVideoBytes = videoBytes;
        latestVideoPackets = videoPackets;
        latestDiscardedBytes = discardedBytes;
        latestRenderedFrames = decoderStats.rendered();
        latestQueuedFrames = decoderStats.queued();
        latestDecoderDrops = decoderStats.dropped();
        performanceTracker.addSample(now, videoBytes, decoderStats.rendered());

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
            updateStatusStrip(framesPerSecond);
            healthView.setText(healthText);
            updateSetupUi();
            updateRecordingUi(recorder.snapshot());
            performWatchdogAction(health.action());
        });
    }

    @Override
    public void onFailure(String message, Throwable error) {
        timeline.add("failure", message + ": " + concise(error));
        Log.e(TAG, message, error);
        onStatus(message + ": " + concise(error));
    }

    @Override
    public void onDecoderStatus(String message) {
        onStatus(message);
    }

    @Override
    public void onDecoderFailure(String message, Throwable error) {
        timeline.add("decoder_failure", message + ": " + concise(error));
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
            timeline.add("video_format", width + "x" + height);
            updateSurfaceLayout();
        });
    }

    @Override
    public void onFrameRendered(long renderedFrames) {
        latestRenderedFrames = renderedFrames;
        long now = SystemClock.elapsedRealtime();
        lastRenderedFrameAt = now;
        performanceTracker.onFrame(now);
        if (!destroyed && now - lastFrameUiAt >= 250) {
            lastFrameUiAt = now;
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
                oldLeft, oldTop, oldRight, oldBottom) -> {
            updateSurfaceLayout();
            updateControlPanelLayout();
            updateBackdropLayout();
        });

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
            controlScroll.setVisibility(controlScroll.getVisibility() == View.VISIBLE
                    ? View.GONE : View.VISIBLE);
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
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        controlScroll = new ScrollView(this);
        controlScroll.setFillViewport(false);
        controlScroll.setClipToPadding(false);
        controlScroll.setVerticalScrollBarEnabled(true);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable panelBackground = new GradientDrawable();
        panelBackground.setColor(0xED101820);
        panelBackground.setCornerRadius(dp(18));
        panelBackground.setStroke(dp(1), 0x665DD9D0);
        panel.setBackground(panelBackground);
        controlScroll.addView(panel, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        advancedPanel = new LinearLayout(this);
        advancedPanel.setOrientation(LinearLayout.VERTICAL);
        advancedPanel.setVisibility(View.GONE);

        TextView title = text(
                "DJI UNCHAINED VOC " + BuildConfig.VERSION_NAME,
                16,
                Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        statusStripView = text("USB ○   VIDEO ○   waiting for stream", 13, 0xFFFFCC66);
        statusStripView.setPadding(0, dp(4), 0, dp(4));
        statusStripView.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(statusStripView);

        identityView = text("No Android USB accessory detected", 12, 0xFFB8C8D4);
        advancedPanel.addView(identityView);

        connectionView = text("Connection: disconnected", 12, 0xFFFFCC66);
        advancedPanel.addView(connectionView);

        statusView = text("Ready. Connect Goggles N3, then press Connect.", 13, 0xFF64E6D9);
        panel.addView(statusView);

        statsView = text("No video packets yet", 11, 0xFFD5DEE5);
        advancedPanel.addView(statsView);

        healthView = text("Stream health: inactive", 11, 0xFFFFCC66);
        advancedPanel.addView(healthView);

        recordingView = text("Recording: idle", 11, 0xFFB8C8D4);
        panel.addView(recordingView);

        setupView = text("Setup: ○ USB  ○ packets  ○ decoder  ○ frame", 11, 0xFFB8C8D4);
        advancedPanel.addView(setupView);

        LinearLayout connectionButtons = new LinearLayout(this);
        connectionButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button connect = new Button(this);
        connect.setText(R.string.connect);
        configureControlButton(connect);
        connect.setOnClickListener(view -> {
            resetStatsBaseline(0, 0);
            usb.connectFirst();
        });
        connectionButtons.addView(connect, weightedButtonParams());

        Button recover = new Button(this);
        recover.setText(R.string.recover_now);
        configureControlButton(recover);
        recover.setOnClickListener(view -> {
            watchdog.resetBaseline(SystemClock.elapsedRealtime(),
                    latestVideoPackets, latestRenderedFrames);
            timeline.add("control", "manual recovery requested");
            usb.recoverNow();
        });
        connectionButtons.addView(recover, weightedButtonParams());

        Button disconnect = new Button(this);
        disconnect.setText(R.string.disconnect);
        configureControlButton(disconnect);
        disconnect.setOnClickListener(view -> usb.disconnect());
        connectionButtons.addView(disconnect, weightedButtonParams());
        panel.addView(connectionButtons);

        recordingButton = new Button(this);
        recordingButton.setText(R.string.record_original);
        configureControlButton(recordingButton);
        recordingButton.setMinHeight(dp(58));
        recordingButton.setTextSize(15);
        recordingButton.setOnClickListener(view -> toggleRecording());
        panel.addView(recordingButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button exportDiagnostics = new Button(this);
        exportDiagnostics.setText(R.string.export_diagnostics);
        configureControlButton(exportDiagnostics);
        exportDiagnostics.setOnClickListener(view -> requestDiagnosticsExport());
        advancedPanel.addView(exportDiagnostics);

        originalContainerButton = new Button(this);
        configureControlButton(originalContainerButton);
        originalContainerButton.setOnClickListener(view -> {
            originalContainer = originalContainer.next();
            preferences.edit().putString(
                    PREF_ORIGINAL_CONTAINER, originalContainer.name()).apply();
            timeline.add("control", "original container=" + originalContainer);
            updateOptionButtons();
        });
        advancedPanel.addView(originalContainerButton);

        LinearLayout optionButtons = new LinearLayout(this);
        optionButtons.setOrientation(LinearLayout.HORIZONTAL);

        displayModeButton = new Button(this);
        configureControlButton(displayModeButton);
        displayModeButton.setOnClickListener(view -> {
            displayMode = displayMode.next();
            preferences.edit().putString(PREF_DISPLAY_MODE, displayMode.name()).apply();
            updateOptionButtons();
            updateSurfaceLayout();
        });
        optionButtons.addView(displayModeButton, weightedButtonParams());

        aspectButton = new Button(this);
        configureControlButton(aspectButton);
        aspectButton.setOnClickListener(view -> {
            sourceAspect = sourceAspect.next();
            preferences.edit().putString(PREF_SOURCE_ASPECT, sourceAspect.name()).apply();
            timeline.add("control", "source aspect=" + sourceAspect);
            updateOptionButtons();
            updateSurfaceLayout();
        });
        optionButtons.addView(aspectButton, weightedButtonParams());
        panel.addView(optionButtons);

        LinearLayout outputButtons = new LinearLayout(this);
        outputButtons.setOrientation(LinearLayout.HORIZONTAL);

        outputFormatButton = new Button(this);
        configureControlButton(outputFormatButton);
        outputFormatButton.setOnClickListener(view -> {
            outputFormat = outputFormat.next();
            preferences.edit().putString(PREF_OUTPUT_FORMAT, outputFormat.name()).apply();
            timeline.add("control", "output format=" + outputFormat);
            if (outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16) {
                onStatus("9:16 output selected: rotate portrait and drag video to position crop");
            }
            updateOptionButtons();
            updateSurfaceLayout();
        });
        outputButtons.addView(outputFormatButton, weightedButtonParams());

        frameRateButton = new Button(this);
        configureControlButton(frameRateButton);
        frameRateButton.setOnClickListener(view -> {
            captureFrameRate = captureFrameRate.next();
            preferences.edit().putString(PREF_CAPTURE_FPS, captureFrameRate.name()).apply();
            timeline.add("control", "record fps=" + captureFrameRate.framesPerSecond());
            updateOptionButtons();
        });
        outputButtons.addView(frameRateButton, weightedButtonParams());
        panel.addView(outputButtons);

        autoReconnectButton = new Button(this);
        configureControlButton(autoReconnectButton);
        autoReconnectButton.setOnClickListener(view -> {
            autoReconnect = !autoReconnect;
            watchdog.resetBaseline(SystemClock.elapsedRealtime(),
                    latestVideoPackets, latestRenderedFrames);
            preferences.edit().putBoolean(PREF_AUTO_RECONNECT, autoReconnect).apply();
            usb.setAutoReconnect(autoReconnect);
            updateOptionButtons();
        });
        advancedPanel.addView(autoReconnectButton);

        LinearLayout powerButtons = new LinearLayout(this);
        powerButtons.setOrientation(LinearLayout.HORIZONTAL);
        keepAwakeButton = new Button(this);
        configureControlButton(keepAwakeButton);
        keepAwakeButton.setOnClickListener(view -> {
            keepAwake = !keepAwake;
            preferences.edit().putBoolean(PREF_KEEP_AWAKE, keepAwake).apply();
            applyKeepAwake();
            updateOptionButtons();
        });
        powerButtons.addView(keepAwakeButton, weightedButtonParams());
        advancedPanel.addView(powerButtons);

        TextView hint = text("Tap the video to hide/show this panel", 11, 0xFFB8C8D4);
        advancedPanel.addView(hint);

        advancedButton = new Button(this);
        configureControlButton(advancedButton);
        advancedButton.setText(R.string.advanced_settings);
        advancedButton.setOnClickListener(view -> {
            boolean show = advancedPanel.getVisibility() != View.VISIBLE;
            advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedButton.setText(show ? R.string.hide_advanced : R.string.advanced_settings);
            timeline.add("control", show ? "advanced shown" : "advanced hidden");
        });
        panel.addView(advancedButton);
        panel.addView(advancedPanel);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panelParams.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(controlScroll, panelParams);

        updateOptionButtons();
        setContentView(root);
    }

    private void updateOptionButtons() {
        displayModeButton.setText(getString(R.string.display_mode, displayMode.name()));
        aspectButton.setText(getString(R.string.source_aspect, sourceAspect.label()));
        outputFormatButton.setText(getString(
                R.string.output_format, outputFormat.label()));
        boolean shorts = outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16;
        frameRateButton.setText(shorts
                ? getString(R.string.capture_fps, captureFrameRate.framesPerSecond())
                : getString(R.string.source_fps));
        originalContainerButton.setText(getString(
                R.string.original_container, originalContainer.label()));
        outputFormatButton.setBackgroundTintList(ColorStateList.valueOf(
                shorts ? 0xFF6A1B9A : 0xFF176B64));
        updateRecordingControlLock();
        String reconnectState = getString(autoReconnect ? R.string.state_on : R.string.state_off);
        autoReconnectButton.setText(getString(R.string.auto_reconnect, reconnectState));
        String awakeState = getString(keepAwake ? R.string.state_on : R.string.state_off);
        keepAwakeButton.setText(getString(R.string.keep_awake, awakeState));
        updateShortsCaptureUi();
    }

    private void updateRecordingControlLock() {
        if (displayModeButton == null || aspectButton == null || outputFormatButton == null
                || frameRateButton == null || originalContainerButton == null
                || recordingButton == null) {
            return;
        }
        boolean shorts = outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16;
        boolean recordingLocked = recorder.snapshot().active() || ShortsCaptureService.isActive();
        displayModeButton.setEnabled(!shorts && !recordingLocked);
        aspectButton.setEnabled(!recordingLocked);
        outputFormatButton.setEnabled(!recordingLocked);
        frameRateButton.setEnabled(shorts && !recordingLocked);
        originalContainerButton.setEnabled(!shorts && !recordingLocked);
        recordingButton.setBackgroundTintList(ColorStateList.valueOf(
                recordingLocked ? 0xFFC62828 : 0xFF176B64));
    }

    private void updateStatusStrip(double currentFps) {
        if (statusStripView == null) {
            return;
        }
        boolean usbOpen = connectionState == UsbAccessoryController.State.CONNECTED
                || connectionState == UsbAccessoryController.State.STREAMING;
        boolean video = latestRenderedFrames > 0
                && SystemClock.elapsedRealtime() - lastRenderedFrameAt < 3_000;
        statusStripView.setText(String.format(Locale.ROOT,
                "USB %s   VIDEO %s   %dx%d   %.0f FPS",
                mark(usbOpen), mark(video), sourceWidth, sourceHeight, currentFps));
        statusStripView.setTextColor(video ? 0xFF64E6D9 : 0xFFFFCC66);
    }

    private void updateControlPanelLayout() {
        if (root == null || controlScroll == null || root.getWidth() <= 0
                || root.getHeight() <= 0) {
            return;
        }
        boolean portrait = root.getHeight() >= root.getWidth();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) controlScroll.getLayoutParams();
        params.width = portrait
                ? Math.max(dp(280), root.getWidth() - dp(24))
                : Math.min(dp(600), Math.max(dp(360), root.getWidth() * 55 / 100));
        params.height = portrait
                ? Math.min(dp(540), Math.max(dp(300), root.getHeight() * 48 / 100))
                : Math.min(dp(520), Math.max(dp(280), root.getHeight() - dp(24)));
        params.gravity = portrait
                ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
                : Gravity.BOTTOM | Gravity.START;
        controlScroll.setLayoutParams(params);
    }

    private void updateBackdropLayout() {
        if (root == null || logoBackdrop == null
                || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) logoBackdrop.getLayoutParams();
        params.width = FrameLayout.LayoutParams.MATCH_PARENT;
        params.height = BackdropGeometry.topArtworkHeight(
                root.getWidth(), root.getHeight());
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        logoBackdrop.setLayoutParams(params);
    }

    private void configureControlButton(Button button) {
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setTextSize(12);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private void performWatchdogAction(StreamWatchdog.Action action) {
        if (action == StreamWatchdog.Action.NONE
                || !autoReconnect || !streamActive || !surfaceReady) {
            return;
        }
        switch (action) {
            case RESEND_KEEPALIVE -> {
                if (usb.resendKeepalive()) {
                    keepaliveResendCount++;
                    timeline.add("recovery", "keepalive resent #" + keepaliveResendCount);
                    onStatus("Stream stalled: resent the proven N3 keepalive packets");
                }
            }
            case RESET_DECODER -> {
                decoderResetCount++;
                timeline.add("recovery", "decoder reset #" + decoderResetCount);
                videoDecoder.resetStream();
                latestRenderedFrames = 0;
                resetStatsBaseline(previousVideoBytes, 0);
                onStatus("Stream still stalled: restarted the local H.264 decoder");
            }
            case REOPEN_USB -> {
                usbReopenCount++;
                timeline.add("recovery", "USB reopen #" + usbReopenCount);
                usb.recoverStalledStream();
            }
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
        if (outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16) {
            toggleShortsCapture();
            return;
        }
        OriginalStreamRecorder.Snapshot snapshot = recorder.snapshot();
        if (snapshot.active()) {
            recorder.stop("Stopped by user");
            return;
        }
        startRecording();
    }

    private void toggleShortsCapture() {
        if (recorder.snapshot().active()) {
            onStatus("Stop original-stream recording before starting Shorts MP4");
            return;
        }
        if (ShortsCaptureService.isActive()) {
            stopShortsCapture();
            controlScroll.setVisibility(View.VISIBLE);
            onStatus("Finishing 9:16 Shorts MP4");
            return;
        }
        if (ShortsCaptureService.state() == ShortsCaptureService.State.ERROR) {
            ShortsCaptureService.resetError();
            timeline.add("shorts_recording", "error reset for retry");
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
        controlScroll.setVisibility(View.GONE);
        Intent service = ShortsCaptureService.startIntent(
                this, resultCode, permissionData, outputUri, 720, 1280,
                getResources().getDisplayMetrics().densityDpi,
                captureFrameRate.framesPerSecond());
        startForegroundService(service);
        timeline.add("shorts_recording", "start requested 720x1280 @ "
                + captureFrameRate.framesPerSecond() + " fps");
        onStatus("Starting experimental 720x1280 Shorts recording @ "
                + captureFrameRate.framesPerSecond() + " fps");
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
            recordingButton.setText(recorder.snapshot().active()
                    ? R.string.stop_recording
                    : outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16
                            ? R.string.start_shorts_recording : R.string.record_original);
            updateRecordingControlLock();
            return;
        }
        if (state == ShortsCaptureService.State.ERROR) {
            recordingButton.setText(R.string.retry_shorts_recording);
        } else {
            recordingButton.setText(state == ShortsCaptureService.State.STOPPING
                    ? R.string.finishing_recording : R.string.stop_shorts_recording);
        }
        recordingView.setText(ShortsCaptureService.message());
        recordingView.setTextColor(state == ShortsCaptureService.State.ERROR
                ? 0xFFFF5252 : 0xFFFFCC66);
        updateRecordingControlLock();
    }

    private void startRecording() {
        if (sourceWidth <= 0 || sourceHeight <= 0 || latestVideoPackets <= 0) {
            onStatus("Original-stream recording needs an active video signal");
            return;
        }
        if (allocatableBytes() < 500L * 1024 * 1024) {
            onStatus("Original-stream recording blocked: less than 500 MB usable storage");
            return;
        }
        Uri uri = null;
        try {
            uri = createOriginalRecordingDestination();
            if (uri == null) {
                onStatus("Could not create the recording in Movies/DJI Unchained VOC");
                return;
            }
            selectedDestinationAvailableBytes = destinationAvailableBytes(uri);
            timeline.add("storage", "automatic original destination available_bytes="
                    + selectedDestinationAvailableBytes);
            if (!recorder.start(
                    uri,
                    originalContainer,
                    sourceWidth,
                    sourceHeight)) {
                getContentResolver().delete(uri, null, null);
                onStatus("Recorder is still closing the previous file");
            } else {
                timeline.add("original_recording",
                        "original incoming stream armed as " + originalContainer);
                onStatus("Recording armed; waiting for the next H.264 keyframe");
            }
        } catch (RuntimeException error) {
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (RuntimeException ignored) {
                    // The original failure is reported below.
                }
            }
            onFailure("Could not create recording", error);
        }
    }

    private Uri createOriginalRecordingDestination() {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME,
                recordingFileName(originalContainer));
        values.put(MediaStore.Video.Media.MIME_TYPE, originalContainer.mimeType());
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/DJI Unchained VOC");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return getContentResolver().insert(collection, values);
    }

    private void updateRecordingUi(OriginalStreamRecorder.Snapshot snapshot) {
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
                         "● REC %s  %s  %.1f MB  access units=%d",
                         formatDuration(elapsedMillis),
                         snapshot.container().label(),
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
                    : outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16
                            ? R.string.start_shorts_recording : R.string.start_recording);
        }
        updateRecordingControlLock();
    }

    private static String formatDuration(long elapsedMillis) {
        long totalSeconds = elapsedMillis / 1_000;
        return String.format(Locale.ROOT, "%02d:%02d",
                totalSeconds / 60, totalSeconds % 60);
    }

    private void writeDiagnostics(Uri uri) {
        timeline.add("diagnostics", "export requested");
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
        OriginalStreamRecorder.Snapshot recording = recordingSnapshot;
        ShortsCaptureService.Snapshot shorts = ShortsCaptureService.snapshot();
        PerformanceTracker.Snapshot performance = performanceTracker.snapshot();
        BatteryManager battery = getSystemService(BatteryManager.class);
        PowerManager power = getSystemService(PowerManager.class);
        ActivityManager activity = getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        activity.getMemoryInfo(memory);
        Runtime runtime = Runtime.getRuntime();
        @SuppressWarnings("deprecation")
        float refreshRate = getWindowManager().getDefaultDisplay().getRefreshRate();
        String orientation = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT ? "PORTRAIT" : "LANDSCAPE";

        return "DJI Unchained VOC " + BuildConfig.VERSION_NAME + " diagnostics\n"
                + "diagnostic_schema=3\n"
                + "generated=" + Instant.now() + "\n"
                + "application_id=" + BuildConfig.APPLICATION_ID + "\n"
                + "debug_build=" + BuildConfig.DEBUG + "\n"
                + "session_uptime_ms="
                + Math.max(0, SystemClock.elapsedRealtime() - sessionStartedAt) + "\n"
                + "android=" + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "device=" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "orientation=" + orientation + "\n"
                + "display_refresh_hz=" + String.format(Locale.ROOT, "%.2f", refreshRate) + "\n"
                + "root_size=" + root.getWidth() + "x" + root.getHeight() + "\n"
                + "surface_size=" + surfaceView.getWidth() + "x" + surfaceView.getHeight() + "\n"
                + "connection=" + connectionState + "\n"
                + "accessory=" + lastIdentity + "\n"
                + "status=" + lastStatus + "\n"
                + "stats=" + lastStats + "\n"
                + "video_bytes=" + latestVideoBytes + "\n"
                + "video_packets=" + latestVideoPackets + "\n"
                + "parser_discarded_bytes=" + latestDiscardedBytes + "\n"
                + "decoder=" + videoDecoder.decoderName() + "\n"
                + "decoder_queued_frames=" + latestQueuedFrames + "\n"
                + "decoder_rendered_frames=" + latestRenderedFrames + "\n"
                + "decoder_dropped_frames=" + latestDecoderDrops + "\n"
                + "last_packet_ms=" + health.packetAgeMillis() + "\n"
                + "last_frame_ms=" + health.frameAgeMillis() + "\n"
                + "maximum_frame_gap_ms=" + performance.maximumFrameGapMillis() + "\n"
                + "bitrate_current_mbps=" + decimal(performance.currentMegabitsPerSecond()) + "\n"
                + "bitrate_average_mbps=" + decimal(performance.averageMegabitsPerSecond()) + "\n"
                + "bitrate_minimum_mbps=" + decimal(performance.minimumMegabitsPerSecond()) + "\n"
                + "bitrate_maximum_mbps=" + decimal(performance.maximumMegabitsPerSecond()) + "\n"
                + "fps_1s=" + decimal(performance.fps1Second()) + "\n"
                + "fps_5s=" + decimal(performance.fps5Seconds()) + "\n"
                + "fps_30s=" + decimal(performance.fps30Seconds()) + "\n"
                + "recovery_actions=" + health.recoveryActions() + "\n"
                + "transport_resets=" + transportResetCount + "\n"
                + "keepalive_resends=" + keepaliveResendCount + "\n"
                + "decoder_resets=" + decoderResetCount + "\n"
                + "usb_reopens=" + usbReopenCount + "\n"
                + "auto_reconnect=" + autoReconnect + "\n"
                + "display_mode=" + displayMode + "\n"
                + "source_aspect=" + sourceAspect + "\n"
                + "output_format=" + outputFormat + "\n"
                + "capture_fps=" + captureFrameRate.framesPerSecond() + "\n"
                + "original_container=" + originalContainer + "\n"
                + "shorts_crop=" + decimal(shortsCrop) + "\n"
                + "keep_awake=" + keepAwake + "\n"
                + "default_allocatable_bytes=" + allocatableBytes() + "\n"
                + "selected_destination_available_bytes="
                + selectedDestinationAvailableBytes + "\n"
                + "battery_percent="
                + battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + "\n"
                + "battery_charging=" + battery.isCharging() + "\n"
                + "power_save=" + power.isPowerSaveMode() + "\n"
                + "thermal_status=" + power.getCurrentThermalStatus() + "\n"
                + "memory_low=" + memory.lowMemory + "\n"
                + "memory_available_bytes=" + memory.availMem + "\n"
                + "memory_threshold_bytes=" + memory.threshold + "\n"
                + "app_heap_used_bytes="
                + (runtime.totalMemory() - runtime.freeMemory()) + "\n"
                + "original_recording_state=" + recording.state() + "\n"
                + "original_recording_container=" + recording.container() + "\n"
                + "original_recording_bytes=" + recording.bytesWritten() + "\n"
                + "original_recording_access_units=" + recording.accessUnitsWritten() + "\n"
                + "original_recording_dropped_units=" + recording.droppedUnits() + "\n"
                + "original_recording_first_pts_us=" + recording.firstPresentationUs() + "\n"
                + "original_recording_last_pts_us=" + recording.lastPresentationUs() + "\n"
                + "original_recording_timestamp_corrections="
                + recording.timestampCorrections() + "\n"
                + "original_recording_actual_fps="
                + decimal(recording.actualFramesPerSecond()) + "\n"
                + "surface_losses_while_original_recording="
                + surfaceLossesWhileRecording + "\n"
                + "original_recording_message=" + recording.message() + "\n"
                + "shorts_recording_state=" + shorts.state() + "\n"
                + "shorts_recording_message=" + shorts.message() + "\n"
                + "shorts_recording_size=" + shorts.width() + "x" + shorts.height() + "\n"
                + "shorts_recording_fps=" + shorts.fps() + "\n"
                + "shorts_recording_actual_fps=" + decimal(shorts.actualFps()) + "\n"
                + "shorts_recording_encoded_frames=" + shorts.encodedFrames() + "\n"
                + "shorts_recording_first_pts_us=" + shorts.firstPresentationUs() + "\n"
                + "shorts_recording_last_pts_us=" + shorts.lastPresentationUs() + "\n"
                + "shorts_recording_timestamp_corrections="
                + shorts.timestampCorrections() + "\n"
                + "shorts_recording_started_at_ms=" + shorts.startedAtMillis() + "\n"
                + "timeline_events=" + timeline.snapshot().size() + "\n"
                + "privacy=no serial, account, location, IP, MAC, network history, URI, or path collected\n"
                + "\n[event_timeline]\n"
                + timeline.export();
    }

    private void updateSurfaceLayout() {
        if (root == null || videoViewport == null || surfaceView == null
                || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        boolean shorts = outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16;
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
        controlScroll.bringToFront();
    }

    private boolean handleVideoTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchStartX = event.getRawX();
            touchStartCrop = shortsCrop;
            touchMoved = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                && outputFormat == CaptureProfile.OutputFormat.SHORTS_9_16) {
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
            controlScroll.bringToFront();
        }
    }

    private void updateSetupUi() {
        if (setupView == null) {
            return;
        }
        boolean accessory = !lastIdentity.startsWith("No Android");
        boolean usbOpen = connectionState == UsbAccessoryController.State.CONNECTED
                || connectionState == UsbAccessoryController.State.STREAMING;
        boolean packets = latestVideoPackets > 0
                && latestHealth.packetAgeMillis() < StreamWatchdog.STALL_MILLIS;
        boolean frame = latestRenderedFrames > 0 && lastRenderedFrameAt > 0
                && SystemClock.elapsedRealtime() - lastRenderedFrameAt
                < StreamWatchdog.STALL_MILLIS;
        setupView.setText(String.format(Locale.ROOT,
                "Live health: %s accessory  %s USB  %s packets  %s frame",
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

    private long destinationAvailableBytes(Uri uri) {
        try (ParcelFileDescriptor descriptor =
                     getContentResolver().openFileDescriptor(uri, "rw")) {
            if (descriptor == null) {
                return -1;
            }
            StructStatVfs stats = Os.fstatvfs(descriptor.getFileDescriptor());
            return Math.multiplyExact(stats.f_bavail, stats.f_frsize);
        } catch (IOException | ErrnoException | ArithmeticException | SecurityException error) {
            timeline.add("storage", "destination capacity unavailable: " + concise(error));
            return -1;
        }
    }

    private String recordingFileName(OriginalStreamRecorder.Container container) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        String extension = container == OriginalStreamRecorder.Container.LOSSLESS_MP4
                ? ".mp4" : ".h264";
        return "dji-unchained-voc-original-stream-" + timestamp + extension;
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

    private static CaptureProfile.OutputFormat readOutputFormat(String stored) {
        if (stored == null) {
            return CaptureProfile.OutputFormat.ORIGINAL_STREAM;
        }
        try {
            return CaptureProfile.OutputFormat.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return CaptureProfile.OutputFormat.ORIGINAL_STREAM;
        }
    }

    private static CaptureProfile.FrameRate readCaptureFrameRate(String stored) {
        if (stored == null) {
            return CaptureProfile.FrameRate.FPS_30;
        }
        try {
            return CaptureProfile.FrameRate.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return CaptureProfile.FrameRate.FPS_30;
        }
    }

    private static OriginalStreamRecorder.Container readOriginalContainer(String stored) {
        if (stored == null) {
            return OriginalStreamRecorder.Container.LOSSLESS_MP4;
        }
        try {
            return OriginalStreamRecorder.Container.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return OriginalStreamRecorder.Container.LOSSLESS_MP4;
        }
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
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
