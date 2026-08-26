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
import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

final class UsbAccessoryController implements Closeable {
    interface Listener extends N3Session.Listener {
        void onAccessoryIdentity(String identity);
        void onTransportReset();
    }

    private final Activity activity;
    private final UsbManager usbManager;
    private final Listener listener;
    private final String permissionAction;
    private boolean receiverRegistered;
    private boolean autoReconnect;
    private boolean permissionRequestPending;
    private ParcelFileDescriptor descriptor;
    private N3Session session;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbAccessory accessory = accessoryExtra(intent);
            if (permissionAction.equals(action)) {
                permissionRequestPending = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && accessory != null) {
                    open(accessory);
                } else {
                    listener.onStatus("USB permission denied");
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action) && accessory != null) {
                describe(accessory);
                if (autoReconnect && isExpectedAccessory(accessory)) {
                    connectAccessory(accessory);
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                listener.onStatus("USB accessory detached");
                disconnect();
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

        UsbAccessory launched = accessoryExtra(launchIntent);
        if (launched != null) {
            describe(launched);
            if (autoReconnect && isExpectedAccessory(launched)) {
                connectAccessory(launched);
            }
        } else {
            refreshIdentity();
        }
    }

    void handleIntent(Intent intent) {
        UsbAccessory accessory = accessoryExtra(intent);
        if (accessory != null) {
            describe(accessory);
            if (autoReconnect && isExpectedAccessory(accessory)) {
                connectAccessory(accessory);
            }
        }
    }

    void setAutoReconnect(boolean enabled) {
        autoReconnect = enabled;
        if (enabled && session == null && receiverRegistered) {
            connectFirst();
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
        UsbAccessory accessory = firstExpectedAccessory();
        if (accessory == null) {
            listener.onStatus("No DJI/logiclink accessory. Power N3, establish the aircraft link, then connect USB-C.");
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
        if (session != null || permissionRequestPending) {
            return;
        }
        if (usbManager.hasPermission(accessory)) {
            open(accessory);
            return;
        }
        Intent intent = new Intent(permissionAction).setPackage(activity.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                activity, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        permissionRequestPending = true;
        listener.onStatus("Requesting USB accessory permission");
        usbManager.requestPermission(accessory, pendingIntent);
    }

    void disconnect() {
        permissionRequestPending = false;
        boolean hadTransport = session != null || descriptor != null;
        if (session != null) {
            session.close();
            session = null;
        }
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
            descriptor = null;
        }
        if (hadTransport) {
            listener.onTransportReset();
        }
        listener.onStatus("Disconnected");
    }

    private void open(UsbAccessory accessory) {
        disconnect();
        descriptor = usbManager.openAccessory(accessory);
        if (descriptor == null) {
            listener.onStatus("Android could not open the USB accessory");
            return;
        }
        FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
        FileOutputStream output = new FileOutputStream(descriptor.getFileDescriptor());
        session = new N3Session(input, output, listener);
        session.start();
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
        return (manufacturer != null && manufacturer.toLowerCase(java.util.Locale.ROOT).contains("dji"))
                || (model != null && model.toLowerCase(java.util.Locale.ROOT).contains("logiclink"));
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
        disconnect();
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver);
            receiverRegistered = false;
        }
    }
}
