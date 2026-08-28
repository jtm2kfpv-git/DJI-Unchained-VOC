package local.n3view.voc;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Locale;

/** Experimental, consent-gated 9:16 screen capture of the clipped Shorts viewport. */
public final class ShortsCaptureService extends Service {
    private static final String CHANNEL_ID = "shorts_recording";
    private static final int NOTIFICATION_ID = 600;
    private static final String ACTION_START = "local.n3view.voc.action.START_SHORTS";
    private static final String ACTION_STOP = "local.n3view.voc.action.STOP_SHORTS";
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_PERMISSION_DATA = "permission_data";
    private static final String EXTRA_OUTPUT_URI = "output_uri";
    private static final String EXTRA_WIDTH = "width";
    private static final String EXTRA_HEIGHT = "height";
    private static final String EXTRA_DENSITY = "density";
    private static final String EXTRA_FPS = "fps";

    public enum State { IDLE, STARTING, RECORDING, STOPPING, ERROR }

    private static volatile State currentState = State.IDLE;
    private static volatile String currentMessage = "Shorts recorder idle";
    private static volatile int currentWidth;
    private static volatile int currentHeight;
    private static volatile int currentFps;
    private static volatile long startedAtMillis;
    private static volatile long currentEncodedFrames;
    private static volatile double currentActualFps;
    private static volatile long currentFirstPresentationUs = -1;
    private static volatile long currentLastPresentationUs = -1;
    private static volatile long currentTimestampCorrections;

    record Snapshot(
            State state,
            String message,
            int width,
            int height,
            int fps,
            long startedAtMillis,
            long encodedFrames,
            double actualFps,
            long firstPresentationUs,
            long lastPresentationUs,
            long timestampCorrections) {
    }

    private DirectMp4Encoder encoder;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private String requestedFinishMessage = "Shorts MP4 saved";

    static Intent startIntent(Context context, int resultCode, Intent permissionData,
            Uri outputUri, int width, int height, int density, int fps) {
        return new Intent(context, ShortsCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PERMISSION_DATA, permissionData)
                .putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_DENSITY, density)
                .putExtra(EXTRA_FPS, fps);
    }

    static Intent stopIntent(Context context) {
        return new Intent(context, ShortsCaptureService.class).setAction(ACTION_STOP);
    }

    static boolean isActive() {
        return currentState == State.STARTING || currentState == State.RECORDING
                || currentState == State.STOPPING;
    }

    static State state() {
        return currentState;
    }

    static String message() {
        return currentMessage;
    }

    static Snapshot snapshot() {
        return new Snapshot(
                currentState,
                currentMessage,
                currentWidth,
                currentHeight,
                currentFps,
                startedAtMillis,
                currentEncodedFrames,
                currentActualFps,
                currentFirstPresentationUs,
                currentLastPresentationUs,
                currentTimestampCorrections);
    }

    static void resetError() {
        if (currentState == State.ERROR) {
            currentState = State.IDLE;
            currentMessage = "Shorts recorder ready to retry";
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Shorts recording", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopCapture("Shorts MP4 saved");
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction()) || isActive()) {
            return START_NOT_STICKY;
        }
        currentState = State.STARTING;
        currentMessage = "Preparing 9:16 Shorts recorder";
        resetMetrics();
        startRecordingForeground();
        try {
            startCapture(intent);
        } catch (IOException | RuntimeException error) {
            failCapture("Shorts recording failed: " + concise(error));
        }
        return START_NOT_STICKY;
    }

    private void startRecordingForeground() {
        Intent stop = stopIntent(this);
        PendingIntent stopAction = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openAction = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_n3_viewer)
                .setContentTitle("DJI Unchained VOC — Shorts recording")
                .setContentText("Recording the visible 9:16 viewport")
                .setContentIntent(openAction)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop and save", stopAction).build())
                .build();
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @SuppressWarnings("deprecation")
    private void startCapture(Intent intent) throws IOException {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent permissionData = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_PERMISSION_DATA);
        Uri outputUri = Uri.parse(intent.getStringExtra(EXTRA_OUTPUT_URI));
        int width = Math.max(360, intent.getIntExtra(EXTRA_WIDTH, 720));
        int height = Math.max(640, intent.getIntExtra(EXTRA_HEIGHT, 1280));
        int density = Math.max(1, intent.getIntExtra(EXTRA_DENSITY, 320));
        int fps = intent.getIntExtra(EXTRA_FPS, 30);
        if (fps != 30 && fps != 60) {
            throw new IOException("Unsupported Shorts frame rate: " + fps);
        }
        if (resultCode != Activity.RESULT_OK || permissionData == null) {
            throw new IOException("Missing screen-capture consent token");
        }

        CaptureProfile.FrameRate frameRate = fps == 60
                ? CaptureProfile.FrameRate.FPS_60 : CaptureProfile.FrameRate.FPS_30;
        CaptureProfile profile = CaptureProfile.shorts(frameRate, 0f);
        encoder = new DirectMp4Encoder(
                getContentResolver(), outputUri, profile, new DirectMp4Encoder.Listener() {
                    @Override
                    public void onEncoderStarted(String codecName, CaptureProfile startedProfile) {
                        currentMessage = "Starting Shorts encoder " + codecName;
                    }

                    @Override
                    public void onEncoderProgress(long elapsedMillis, long encodedBytes) {
                        updateEncoderMetrics();
                        currentMessage = String.format(Locale.ROOT,
                                "● REC SHORTS %dx%d @ %d fps  actual %.1f fps",
                                currentWidth, currentHeight, currentFps, currentActualFps);
                    }

                    @Override
                    public void onEncoderFinished(
                            long elapsedMillis, long encodedBytes, String reason) {
                        if (currentState == State.ERROR) {
                            return;
                        }
                        updateEncoderMetrics();
                        finishCapture(true, requestedFinishMessage);
                    }

                    @Override
                    public void onEncoderFailed(String message, Throwable error) {
                        if (currentState == State.ERROR) {
                            return;
                        }
                        updateEncoderMetrics();
                        failCapture(message + ": " + concise(error));
                    }
                });
        encoder.start();

        MediaProjectionManager manager = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(resultCode, permissionData);
        if (projection == null) {
            throw new IOException("Android did not create a screen-capture session");
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopCapture("Android ended the Shorts capture");
            }
        }, new Handler(Looper.getMainLooper()));
        virtualDisplay = projection.createVirtualDisplay(
                "N3ShortsCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoder.inputSurface(), null, null);
        if (virtualDisplay == null) {
            throw new IOException("Android did not create the Shorts virtual display");
        }

        currentWidth = width;
        currentHeight = height;
        currentFps = fps;
        startedAtMillis = SystemClock.elapsedRealtime();
        currentState = State.RECORDING;
        currentMessage = "● REC SHORTS " + width + "x" + height + " @ " + fps + " fps";
    }

    private synchronized void stopCapture(String message) {
        if (currentState == State.STOPPING || !isActive()) {
            return;
        }
        currentState = State.STOPPING;
        currentMessage = "Finishing Shorts MP4 safely";
        requestedFinishMessage = message;
        releaseProjection();
        if (encoder != null) {
            encoder.stopSafely();
        } else {
            failCapture("Shorts encoder was unavailable while stopping");
        }
    }

    private synchronized void finishCapture(boolean saved, String message) {
        releaseProjection();
        encoder = null;
        currentState = saved ? State.IDLE : State.ERROR;
        currentMessage = saved ? message : "Shorts MP4 could not be finalized";
        if (saved) {
            startedAtMillis = 0;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private synchronized void failCapture(String message) {
        releaseProjection();
        if (encoder != null) {
            encoder.cancel();
            encoder = null;
        }
        currentState = State.ERROR;
        currentMessage = message;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releaseProjection() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            MediaProjection current = projection;
            projection = null;
            current.stop();
        }
    }

    private void updateEncoderMetrics() {
        DirectMp4Encoder current = encoder;
        if (current == null) {
            return;
        }
        currentEncodedFrames = current.encodedFrames();
        currentActualFps = current.actualFramesPerSecond();
        currentFirstPresentationUs = current.firstPresentationUs();
        currentLastPresentationUs = current.lastPresentationUs();
        currentTimestampCorrections = current.timestampCorrections();
    }

    private static void resetMetrics() {
        currentEncodedFrames = 0;
        currentActualFps = 0;
        currentFirstPresentationUs = -1;
        currentLastPresentationUs = -1;
        currentTimestampCorrections = 0;
    }

    @Override
    public void onDestroy() {
        if (currentState == State.STARTING || currentState == State.RECORDING) {
            stopCapture("Shorts MP4 saved because the recorder service stopped");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String concise(Throwable error) {
        String detail = error.getMessage();
        return error.getClass().getSimpleName() + (detail == null ? "" : " - " + detail);
    }
}
