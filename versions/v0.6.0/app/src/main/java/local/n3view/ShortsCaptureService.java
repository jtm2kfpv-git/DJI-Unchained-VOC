package local.n3view;

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
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

/** Experimental, consent-gated 9:16 screen capture of the clipped Shorts viewport. */
public final class ShortsCaptureService extends Service {
    private static final String CHANNEL_ID = "shorts_recording";
    private static final int NOTIFICATION_ID = 600;
    private static final String ACTION_START = "local.n3view.action.START_SHORTS";
    private static final String ACTION_STOP = "local.n3view.action.STOP_SHORTS";
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_PERMISSION_DATA = "permission_data";
    private static final String EXTRA_OUTPUT_URI = "output_uri";
    private static final String EXTRA_WIDTH = "width";
    private static final String EXTRA_HEIGHT = "height";
    private static final String EXTRA_DENSITY = "density";

    public enum State { IDLE, STARTING, RECORDING, STOPPING, ERROR }

    private static volatile State currentState = State.IDLE;
    private static volatile String currentMessage = "Shorts recorder idle";

    private MediaRecorder recorder;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor outputDescriptor;

    static Intent startIntent(Context context, int resultCode, Intent permissionData,
            Uri outputUri, int width, int height, int density) {
        return new Intent(context, ShortsCaptureService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PERMISSION_DATA, permissionData)
                .putExtra(EXTRA_OUTPUT_URI, outputUri.toString())
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height)
                .putExtra(EXTRA_DENSITY, density);
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
        startRecordingForeground();
        try {
            startCapture(intent);
        } catch (IOException | RuntimeException error) {
            currentState = State.ERROR;
            currentMessage = "Shorts recording failed: " + concise(error);
            releaseCapture(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
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
        if (resultCode != Activity.RESULT_OK || permissionData == null) {
            throw new IOException("Missing screen-capture consent token");
        }

        outputDescriptor = getContentResolver().openFileDescriptor(outputUri, "rw");
        if (outputDescriptor == null) {
            throw new IOException("Could not open selected MP4 file");
        }
        recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setOutputFile(outputDescriptor.getFileDescriptor());
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(width, height);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(10_000_000);
        recorder.prepare();

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
                recorder.getSurface(), null, null);
        recorder.start();
        currentState = State.RECORDING;
        currentMessage = "● REC SHORTS 720x1280 MP4";
    }

    private synchronized void stopCapture(String message) {
        if (!isActive()) {
            return;
        }
        currentState = State.STOPPING;
        currentMessage = "Finishing Shorts MP4 safely";
        boolean saved = releaseCapture(true);
        currentState = saved ? State.IDLE : State.ERROR;
        currentMessage = saved ? message : "Shorts MP4 could not be finalized";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private boolean releaseCapture(boolean stopRecorder) {
        boolean saved = true;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (recorder != null) {
            if (stopRecorder) {
                try {
                    recorder.stop();
                } catch (RuntimeException error) {
                    saved = false;
                }
            }
            recorder.reset();
            recorder.release();
            recorder = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (outputDescriptor != null) {
            try {
                outputDescriptor.close();
            } catch (IOException error) {
                saved = false;
            }
            outputDescriptor = null;
        }
        return saved;
    }

    @Override
    public void onDestroy() {
        if (isActive()) {
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
