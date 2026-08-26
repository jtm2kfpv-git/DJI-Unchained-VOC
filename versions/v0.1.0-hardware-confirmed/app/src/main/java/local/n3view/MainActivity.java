package local.n3view;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
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
import android.util.Log;

import local.n3view.protocol.H264AnnexBAssembler;

import java.util.Locale;

public final class MainActivity extends Activity implements
        UsbAccessoryController.Listener,
        AvcSurfaceDecoder.Listener,
        SurfaceHolder.Callback {

    private static final String TAG = "N3LocalView";

    private TextView identityView;
    private TextView statusView;
    private TextView statsView;
    private UsbAccessoryController usb;
    private AvcSurfaceDecoder videoDecoder;
    private final H264AnnexBAssembler assembler = new H264AnnexBAssembler();
    private long statsStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        enterImmersiveMode();

        videoDecoder = new AvcSurfaceDecoder(this);
        usb = new UsbAccessoryController(this, this);
        usb.start(getIntent());
        statsStarted = SystemClock.elapsedRealtime();
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
        // MediaCodec renders directly to the Surface and adapts its crop/size.
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
        statsStarted = SystemClock.elapsedRealtime();
    }

    @Override
    public void onStatus(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> statusView.setText(message));
    }

    @Override
    public void onVideo(byte[] bytes) {
        assembler.accept(bytes, videoDecoder::queue);
    }

    @Override
    public void onStats(long videoBytes, long videoPackets, long discardedBytes) {
        long elapsedMs = Math.max(1, SystemClock.elapsedRealtime() - statsStarted);
        double megabitsPerSecond = videoBytes * 8.0 / elapsedMs / 1_000.0;
        String text = String.format(Locale.ROOT,
                "video %.2f Mbit/s  packets=%d  resync-discard=%d  %s",
                megabitsPerSecond, videoPackets, discardedBytes, videoDecoder.stats());
        runOnUiThread(() -> statsView.setText(text));
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

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        SurfaceView surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        panel.setBackgroundColor(0xC914222E);

        TextView title = text("N3 LOCAL VIEW - OFFLINE USB PROBE", 16, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        identityView = text("No Android USB accessory detected", 12, 0xFFB8C8D4);
        panel.addView(identityView);

        statusView = text("Ready. Connect Goggles N3, then press Connect.", 13, 0xFF64E6D9);
        panel.addView(statusView);

        statsView = text("No video packets yet", 11, 0xFFD5DEE5);
        panel.addView(statsView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button connect = new Button(this);
        connect.setText(R.string.connect);
        connect.setOnClickListener(v -> {
            statsStarted = SystemClock.elapsedRealtime();
            usb.connectFirst();
        });
        buttons.addView(connect);

        Button disconnect = new Button(this);
        disconnect.setText(R.string.disconnect);
        disconnect.setOnClickListener(v -> usb.disconnect());
        buttons.addView(disconnect);
        panel.addView(buttons);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        panelParams.setMargins(dp(12), dp(12), dp(12), dp(12));
        root.addView(panel, panelParams);

        root.setOnClickListener(v -> enterImmersiveMode());
        setContentView(root);
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
