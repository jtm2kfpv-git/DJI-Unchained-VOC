package local.n3view;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

import local.n3view.protocol.H264AnnexBAssembler;

public final class MainActivity extends Activity implements
        UsbAccessoryController.Listener,
        AvcSurfaceDecoder.Listener,
        H264Recorder.Listener,
        SurfaceHolder.Callback {

    private static final String TAG = "N3LocalView";
    private static final String PREFERENCES = "viewer_preferences";
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_AUTO_RECONNECT = "auto_reconnect";
    private static final int CREATE_DIAGNOSTICS_REQUEST = 40;
    private static final int CREATE_RECORDING_REQUEST = 41;

    private FrameLayout root;
    private SurfaceView surfaceView;
    private LinearLayout panel;
    private TextView identityView;
    private TextView connectionView;
    private TextView statusView;
    private TextView statsView;
    private TextView healthView;
    private TextView recordingView;
    private Button displayModeButton;
    private Button autoReconnectButton;
    private Button recordingButton;
    private UsbAccessoryController usb;
    private AvcSurfaceDecoder videoDecoder;
    private H264Recorder recorder;
    private final H264AnnexBAssembler assembler = new H264AnnexBAssembler();
    private final StreamWatchdog watchdog = new StreamWatchdog();
    private SharedPreferences preferences;
    private volatile DisplayGeometry.Mode displayMode;
    private volatile boolean autoReconnect;
    private volatile int sourceWidth = 16;
    private volatile int sourceHeight = 9;
    private long previousStatsAt;
    private long previousVideoBytes;
    private long previousRenderedFrames;
    private volatile long latestVideoPackets;
    private volatile long latestRenderedFrames;
    private volatile boolean surfaceReady;
    private volatile boolean streamActive;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        displayMode = readDisplayMode(preferences.getString(PREF_DISPLAY_MODE, null));
        autoReconnect = preferences.getBoolean(PREF_AUTO_RECONNECT, false);
        recorder = new H264Recorder(this);

        buildUi();
        enterImmersiveMode();

        videoDecoder = new AvcSurfaceDecoder(this);
        usb = new UsbAccessoryController(this, this);
        usb.setAutoReconnect(autoReconnect);
        usb.start(getIntent());
        resetStatsBaseline(0, 0);
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
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
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
    }

    @Override
    public void onTransportReset() {
        if (recorder != null) {
            recorder.stop("USB transport reset; recording closed safely");
        }
        latestVideoPackets = 0;
        latestRenderedFrames = 0;
        streamActive = false;
        watchdog.setActive(false, SystemClock.elapsedRealtime(), 0, 0);
        assembler.reset();
        if (videoDecoder != null) {
            videoDecoder.resetStream();
        }
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

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(true);
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateSurfaceLayout());

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setClickable(true);
        surfaceView.setContentDescription("Video display; tap to show or hide controls");
        surfaceView.setOnClickListener(view -> {
            panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            enterImmersiveMode();
        });
        FrameLayout.LayoutParams surfaceParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER);
        root.addView(surfaceView, surfaceParams);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackgroundColor(0xC914222E);

        TextView title = text("N3 LOCAL VIEW 0.5 - OFFLINE", 16, Color.WHITE);
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
        String reconnectState = getString(autoReconnect ? R.string.state_on : R.string.state_off);
        autoReconnectButton.setText(getString(R.string.auto_reconnect, reconnectState));
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
                .putExtra(Intent.EXTRA_TITLE, "n3-local-view-0.5-diagnostics.txt");
        startActivityForResult(intent, CREATE_DIAGNOSTICS_REQUEST);
    }

    private void toggleRecording() {
        H264Recorder.Snapshot snapshot = recorder.snapshot();
        if (snapshot.active()) {
            recorder.stop("Stopped by user");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/h264")
                .putExtra(Intent.EXTRA_TITLE, "n3-recording.h264");
        startActivityForResult(intent, CREATE_RECORDING_REQUEST);
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
        recordingButton.setText(snapshot.active()
                ? R.string.stop_recording : R.string.start_recording);
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
        return "N3 Local View 0.5 diagnostics\n"
                + "generated=" + Instant.now() + "\n"
                + "connection=" + connectionState + "\n"
                + "accessory=" + lastIdentity + "\n"
                + "status=" + lastStatus + "\n"
                + "stats=" + lastStats + "\n"
                + "last_packet_ms=" + health.packetAgeMillis() + "\n"
                + "last_frame_ms=" + health.frameAgeMillis() + "\n"
                + "recovery_actions=" + health.recoveryActions() + "\n"
                + "auto_reconnect=" + autoReconnect + "\n"
                + "display_mode=" + displayMode + "\n"
                + "recording_state=" + recording.state() + "\n"
                + "recording_bytes=" + recording.bytesWritten() + "\n"
                + "recording_access_units=" + recording.accessUnitsWritten() + "\n"
                + "recording_dropped_units=" + recording.droppedUnits() + "\n"
                + "recording_message=" + recording.message() + "\n"
                + "privacy=no serial, account, location or network data collected\n";
    }

    private void updateSurfaceLayout() {
        if (root == null || surfaceView == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        DisplayGeometry.Size size = DisplayGeometry.calculate(
                root.getWidth(), root.getHeight(), sourceWidth, sourceHeight, displayMode);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (params.width != size.width() || params.height != size.height()
                || params.gravity != Gravity.CENTER) {
            params.width = size.width();
            params.height = size.height();
            params.gravity = Gravity.CENTER;
            surfaceView.setLayoutParams(params);
        }
        panel.bringToFront();
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
