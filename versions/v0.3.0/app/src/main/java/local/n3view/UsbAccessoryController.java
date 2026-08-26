package local.n3view;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class UsbAccessoryController implements Closeable {
    enum State {
        DISCONNECTED,
        WAITING_PERMISSION,
        CONNECTING,
        CONNECTED,
        STREAMING,
        RETRY_WAIT
    }

    interface Listener extends N3Session.Listener {
        void onAccessoryIdentity(String identity);
        void onTransportReset();
        void onConnectionState(State state, int retryAttempt, long retryDelayMillis);
    }

    private final Activity activity;
    private final UsbManager usbManager;
    private final Listener listener;
    private final String permissionAction;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ReconnectPolicy reconnectPolicy = new ReconnectPolicy();
    private final AtomicLong connectionGeneration = new AtomicLong();
    private boolean receiverRegistered;
    private boolean autoReconnect;
    private boolean permissionRequestPending;
    private boolean retryScheduled;
    private boolean manualDisconnect;
    private boolean closed;
    private ParcelFileDescriptor descriptor;
    private N3Session session;

    private final Runnable retryTask = () -> {
        retryScheduled = false;
        if (!closed && autoReconnect && !manualDisconnect && session == null) {
            connectFirstInternal(true);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbAccessory accessory = accessoryExtra(intent);
            if (permissionAction.equals(action)) {
                permissionRequestPending = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && accessory != null && !manualDisconnect) {
                    open(accessory);
                } else {
                    updateState(State.DISCONNECTED, 0, 0);
                    listener.onStatus("USB permission denied or connection cancelled");
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action) && accessory != null) {
                describe(accessory);
                if (autoReconnect && isExpectedAccessory(accessory)) {
                    manualDisconnect = false;
                    reconnectPolicy.reset();
                    connectAccessory(accessory);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)
                    && (accessory == null || isExpectedAccessory(accessory))) {
                cancelRetry();
                closeTransport();
                updateState(State.DISCONNECTED, 0, 0);
                listener.onStatus("USB accessory detached; waiting for reattachment");
            }
        }
    };

    UsbAccessoryController(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        permissionAction = activity.getPackageName() + ".USB_PERMISSION";
    }

    void start(Intent launchIntent) {
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
        updateState(State.DISCONNECTED, 0, 0);

        UsbAccessory launched = accessoryExtra(launchIntent);
        if (launched != null) {
            describe(launched);
            if (autoReconnect && isExpectedAccessory(launched)) {
                manualDisconnect = false;
                connectAccessory(launched);
            }
        } else {
            refreshIdentity();
            if (autoReconnect) {
                manualDisconnect = false;
                connectFirstInternal(false);
            }
        }
    }

    void handleIntent(Intent intent) {
        UsbAccessory accessory = accessoryExtra(intent);
        if (accessory != null) {
            describe(accessory);
            if (autoReconnect && isExpectedAccessory(accessory)) {
                manualDisconnect = false;
                reconnectPolicy.reset();
                connectAccessory(accessory);
            }
        }
    }

    void setAutoReconnect(boolean enabled) {
        autoReconnect = enabled;
        if (!enabled) {
            cancelRetry();
            if (session == null && !permissionRequestPending) {
                updateState(State.DISCONNECTED, 0, 0);
            }
        } else if (session == null && receiverRegistered && !permissionRequestPending) {
            manualDisconnect = false;
            reconnectPolicy.reset();
            connectFirstInternal(false);
        }
    }

    void refreshIdentity() {
        UsbAccessory accessory = firstExpectedAccessory();
        if (accessory == null) {
            UsbAccessory[] list = usbManager.getAccessoryList();
            if (list != null && list.length > 0) {
                describe(list[0]);
                listener.onStatus("USB accessory found, but it does not identify as DJI/logiclink");
            } else {
                listener.onAccessoryIdentity("No Android USB accessory detected");
            }
            return;
        }
        describe(accessory);
    }

    void connectFirst() {
        manualDisconnect = false;
        cancelRetry();
        reconnectPolicy.reset();
        connectFirstInternal(false);
    }

    private void connectFirstInternal(boolean retry) {
        if (closed || session != null || permissionRequestPending) {
            return;
        }
        UsbAccessory accessory = firstExpectedAccessory();
        if (accessory == null) {
            updateState(State.DISCONNECTED, 0, 0);
            listener.onStatus(retry
                    ? "Reconnect paused; waiting for the DJI/logiclink accessory"
                    : "No DJI/logiclink accessory. Power N3, establish the aircraft link, then connect USB-C.");
            return;
        }
        describe(accessory);
        connectAccessory(accessory);
    }

    private void connectAccessory(UsbAccessory accessory) {
        if (!isExpectedAccessory(accessory)) {
            listener.onStatus("Refusing to send N3 packets to a non-DJI accessory");
            return;
        }
        if (closed || session != null || permissionRequestPending) {
            return;
        }
        cancelRetry();
        if (usbManager.hasPermission(accessory)) {
            open(accessory);
            return;
        }
        Intent intent = new Intent(permissionAction).setPackage(activity.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                activity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        permissionRequestPending = true;
        updateState(State.WAITING_PERMISSION, 0, 0);
        listener.onStatus("Requesting USB accessory permission");
        usbManager.requestPermission(accessory, pendingIntent);
    }

    void disconnect() {
        manualDisconnect = true;
        permissionRequestPending = false;
        reconnectPolicy.reset();
        cancelRetry();
        closeTransport();
        updateState(State.DISCONNECTED, 0, 0);
        listener.onStatus("Disconnected manually");
    }

    private void open(UsbAccessory accessory) {
        cancelRetry();
        closeTransport();
        updateState(State.CONNECTING, 0, 0);
        descriptor = usbManager.openAccessory(accessory);
        if (descriptor == null) {
            updateState(State.DISCONNECTED, 0, 0);
            listener.onStatus("Android could not open the USB accessory");
            scheduleRetry();
            return;
        }

        long generation = connectionGeneration.incrementAndGet();
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
        FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
        session = new N3Session(input, output, sessionListener(generation));
        updateState(State.CONNECTED, 0, 0);
        session.start();
    }

    private N3Session.Listener sessionListener(long generation) {
        AtomicBoolean sawVideo = new AtomicBoolean();
        return new N3Session.Listener() {
            @Override
            public void onStatus(String message) {
                if (generation == connectionGeneration.get()) {
                    listener.onStatus(message);
                }
            }

            @Override
            public void onVideo(byte[] bytes) {
                boolean firstVideo;
                synchronized (UsbAccessoryController.this) {
                    if (generation != connectionGeneration.get()) {
                        return;
                    }
                    firstVideo = sawVideo.compareAndSet(false, true);
                    listener.onVideo(bytes);
                }
                if (firstVideo) {
                    mainHandler.post(() -> markStreaming(generation));
                }
            }

            @Override
            public void onStats(long videoBytes, long videoPackets, long discardedBytes) {
                synchronized (UsbAccessoryController.this) {
                    if (generation != connectionGeneration.get()) {
                        return;
                    }
                    listener.onStats(videoBytes, videoPackets, discardedBytes);
                }
            }

            @Override
            public void onFailure(String message, Throwable error) {
                synchronized (UsbAccessoryController.this) {
                    if (generation != connectionGeneration.get()) {
                        return;
                    }
                    listener.onFailure(message, error);
                }
                mainHandler.post(() -> handleSessionFailure(generation));
            }
        };
    }

    private void markStreaming(long generation) {
        if (generation == connectionGeneration.get() && session != null) {
            reconnectPolicy.reset();
            updateState(State.STREAMING, 0, 0);
        }
    }

    private void handleSessionFailure(long generation) {
        if (generation != connectionGeneration.get() || closed) {
            return;
        }
        closeTransport();
        updateState(State.DISCONNECTED, 0, 0);
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (closed || !autoReconnect || manualDisconnect || retryScheduled) {
            return;
        }
        if (firstExpectedAccessory() == null) {
            updateState(State.DISCONNECTED, 0, 0);
            listener.onStatus("Connection lost; waiting for the DJI accessory to be attached");
            return;
        }
        ReconnectPolicy.Attempt attempt = reconnectPolicy.nextAttempt();
        retryScheduled = true;
        updateState(State.RETRY_WAIT, attempt.number(), attempt.delayMillis());
        listener.onStatus(String.format(Locale.ROOT,
                "Connection lost; retry %d in %.1f s",
                attempt.number(), attempt.delayMillis() / 1_000.0));
        mainHandler.postDelayed(retryTask, attempt.delayMillis());
    }

    private void cancelRetry() {
        mainHandler.removeCallbacks(retryTask);
        retryScheduled = false;
    }

    private synchronized void closeTransport() {
        connectionGeneration.incrementAndGet();
        boolean hadTransport = session != null || descriptor != null;
        N3Session oldSession = session;
        ParcelFileDescriptor oldDescriptor = descriptor;
        session = null;
        descriptor = null;
        if (oldSession != null) {
            oldSession.close();
        }
        if (oldDescriptor != null) {
            try {
                oldDescriptor.close();
            } catch (IOException ignored) {
            }
        }
        if (hadTransport) {
            listener.onTransportReset();
        }
    }

    private void updateState(State state, int retryAttempt, long retryDelayMillis) {
        listener.onConnectionState(state, retryAttempt, retryDelayMillis);
    }

    private UsbAccessory firstExpectedAccessory() {
        UsbAccessory[] list = usbManager.getAccessoryList();
        if (list == null) {
            return null;
        }
        for (UsbAccessory accessory : list) {
            if (isExpectedAccessory(accessory)) {
                return accessory;
            }
        }
        return null;
    }

    private static boolean isExpectedAccessory(UsbAccessory accessory) {
        String manufacturer = accessory.getManufacturer();
        String model = accessory.getModel();
        return (manufacturer != null && manufacturer.toLowerCase(Locale.ROOT).contains("dji"))
                || (model != null && model.toLowerCase(Locale.ROOT).contains("logiclink"));
    }

    private void describe(UsbAccessory accessory) {
        String identity = "manufacturer=" + safe(accessory.getManufacturer())
                + "  model=" + safe(accessory.getModel())
                + "  version=" + safe(accessory.getVersion())
                + "  description=" + safe(accessory.getDescription());
        listener.onAccessoryIdentity(identity);
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "<not supplied>" : value;
    }

    @SuppressWarnings("deprecation")
    private static UsbAccessory accessoryExtra(Intent intent) {
        if (intent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }

    @Override
    public void close() {
        closed = true;
        manualDisconnect = true;
        permissionRequestPending = false;
        cancelRetry();
        closeTransport();
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}
