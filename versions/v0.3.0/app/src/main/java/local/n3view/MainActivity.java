package local.n3view;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
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

import java.util.Locale;

import local.n3view.protocol.H264AnnexBAssembler;

public final class MainActivity extends Activity implements
        UsbAccessoryController.Listener,
        AvcSurfaceDecoder.Listener,
        SurfaceHolder.Callback {

    private static final String TAG = "N3LocalView";
    private static final String PREFERENCES = "viewer_preferences";
    private static final String PREF_DISPLAY_MODE = "display_mode";
    private static final String PREF_AUTO_RECONNECT = "auto_reconnect";

    private FrameLayout root;
    private SurfaceView surfaceView;
    private LinearLayout panel;
    private TextView identityView;
    private TextView connectionView;
    private TextView statusView;
    private TextView statsView;
    private Button displayModeButton;
    private Button autoReconnectButton;
    private UsbAccessoryController usb;
    private AvcSurfaceDecoder videoDecoder;
    private final H264AnnexBAssembler assembler = new H264AnnexBAssembler();
    private SharedPreferences preferences;
    private volatile DisplayGeometry.Mode displayMode;
    private boolean autoReconnect;
    private volatile int sourceWidth = 16;
    private volatile int sourceHeight = 9;
    private long previousStatsAt;
    private long previousVideoBytes;
    private long previousRenderedFrames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        preferences = getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        displayMode = readDisplayMode(preferences.getString(PREF_DISPLAY_MODE, null));
        autoReconnect = preferences.getBoolean(PREF_AUTO_RECONNECT, false);

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
    protected void onDestroy() {
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
        videoDecoder.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // MediaCodec renders directly to the Surface; its View controls presentation geometry.
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        videoDecoder.clearSurface();
    }

    @Override
    public void onAccessoryIdentity(String identity) {
        Log.i(TAG, "USB accessory: " + identity);
        runOnUiThread(() -> identityView.setText(identity));
    }

    @Override
    public void onTransportReset() {
        assembler.reset();
        if (videoDecoder != null) {
            videoDecoder.resetStream();
        }
        resetStatsBaseline(0, 0);
    }

    @Override
    public void onStatus(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> statusView.setText(message));
    }

    @Override
    public void onConnectionState(
            UsbAccessoryController.State state,
            int retryAttempt,
            long retryDelayMillis) {
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
        assembler.accept(bytes, videoDecoder::queue);
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

        String value = String.format(Locale.ROOT,
                "%dx%d  %s  %.2f Mbit/s  %.1f fps  packets=%d  dropped=%d  resync=%d",
                sourceWidth, sourceHeight, displayMode, megabitsPerSecond, framesPerSecond,
                videoPackets, decoderStats.dropped(), discardedBytes);
        runOnUiThread(() -> statsView.setText(value));
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

        TextView title = text("N3 LOCAL VIEW 0.3 - OFFLINE", 16, Color.WHITE);
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
