package com.dtdc.soprotest;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.app.Activity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int SOPRO_VID = 0xEB1A;
    private static final int SOPRO_PID = 0x2821;
    private static final String ACTION_USB_PERMISSION =
            "com.dtdc.soprotest.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice targetDevice;
    private UsbDeviceConnection connection;

    private TextView deviceStatus;
    private TextView logView;
    private final StringBuilder log = new StringBuilder();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = getUsbDeviceExtra(intent);
                boolean granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false);

                if (granted && device != null) {
                    append("✓ USB permission granted");
                    targetDevice = device;
                    inspectAndOpen(device);
                } else {
                    append("✗ USB permission denied");
                    setStatus("Permission denied");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDeviceExtra(intent);
                if (device != null && targetDevice != null &&
                        device.getDeviceId() == targetDevice.getDeviceId()) {
                    append("! Camera detached");
                    closeConnection();
                    targetDevice = null;
                    setStatus("Camera disconnected");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                append("USB device attached");
                scanDevices(false);
            }
        }
    };

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(
                    UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        deviceStatus = findViewById(R.id.deviceStatus);
        logView = findViewById(R.id.logView);
        Button btnConnect = findViewById(R.id.btnConnect);
        Button btnRefresh = findViewById(R.id.btnRefresh);
        Button btnCopy = findViewById(R.id.btnCopy);
        Button btnSave = findViewById(R.id.btnSave);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        append("Sopro Camera Test v0.1");
        append(String.format(Locale.US,
                "Target: VID=%04X PID=%04X", SOPRO_VID, SOPRO_PID));
        append("Waiting for camera...");

        btnRefresh.setOnClickListener(v -> scanDevices(false));
        btnConnect.setOnClickListener(v -> scanDevices(true));
        btnCopy.setOnClickListener(v -> copyLog());
        btnSave.setOnClickListener(v -> saveLog());

        scanDevices(false);
    }

    private void scanDevices(boolean requestPermission) {
        append("");
        append("--- USB scan ---");

        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();

        if (devices.isEmpty()) {
            append("No USB devices visible to Android");
            setStatus("No USB device detected");
            return;
        }

        append("Visible USB devices: " + devices.size());

        targetDevice = null;

        for (UsbDevice device : devices.values()) {
            append(String.format(Locale.US,
                    "Device: %s | VID=%04X PID=%04X | class=%d | interfaces=%d",
                    device.getDeviceName(),
                    device.getVendorId(),
                    device.getProductId(),
                    device.getDeviceClass(),
                    device.getInterfaceCount()));

            if (device.getVendorId() == SOPRO_VID &&
                    device.getProductId() == SOPRO_PID) {
                targetDevice = device;
                append("✓ Target EB1A:2821 detected");
            }
        }

        if (targetDevice == null) {
            append("✗ Target EB1A:2821 not found");
            setStatus("Camera not found");
            return;
        }

        setStatus("Sopro/eMPIA camera detected");

        dumpDescriptors(targetDevice);

        if (!requestPermission) {
            if (usbManager.hasPermission(targetDevice)) {
                append("✓ Android already has USB permission");
            } else {
                append("○ Press CONNECT CAMERA to request USB permission");
            }
            return;
        }

        if (usbManager.hasPermission(targetDevice)) {
            append("✓ USB permission already granted");
            inspectAndOpen(targetDevice);
        } else {
            append("Requesting USB permission...");
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
            permissionIntent.setPackage(getPackageName());

            PendingIntent pi = PendingIntent.getBroadcast(
                    this,
                    0,
                    permissionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            PendingIntent.FLAG_MUTABLE);

            usbManager.requestPermission(targetDevice, pi);
        }
    }

    private void dumpDescriptors(UsbDevice device) {
        append("--- Interfaces / endpoints ---");

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);

            append(String.format(Locale.US,
                    "IF[%d] id=%d class=%d subclass=%d protocol=%d endpoints=%d",
                    i,
                    intf.getId(),
                    intf.getInterfaceClass(),
                    intf.getInterfaceSubclass(),
                    intf.getInterfaceProtocol(),
                    intf.getEndpointCount()));

            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);

                append(String.format(Locale.US,
                        "  EP[%d] addr=0x%02X dir=%s type=%s maxPacket=%d interval=%d",
                        e,
                        ep.getAddress(),
                        directionName(ep.getDirection()),
                        typeName(ep.getType()),
                        ep.getMaxPacketSize(),
                        ep.getInterval()));
            }
        }
    }

    private void inspectAndOpen(UsbDevice device) {
        closeConnection();

        connection = usbManager.openDevice(device);

        if (connection == null) {
            append("✗ usbManager.openDevice() returned null");
            setStatus("Could not open camera");
            return;
        }

        append("✓ UsbDeviceConnection opened");

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            boolean claimed = false;

            try {
                claimed = connection.claimInterface(intf, true);
            } catch (Exception ex) {
                append("IF[" + i + "] claim exception: " + ex.getMessage());
            }

            append("IF[" + i + "] claimInterface(force=true) = " + claimed);

            if (claimed) {
                connection.releaseInterface(intf);
            }
        }

        append("✓ v0.1 diagnostic pass complete");
        append("Next build will use this log to implement EM28xx video streaming.");
        setStatus("Camera detected and USB structure read");
    }

    private String directionName(int direction) {
        return direction == UsbConstants.USB_DIR_IN ? "IN" : "OUT";
    }

    private String typeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "ISO";
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "INT";
            default:
                return "UNKNOWN(" + type + ")";
        }
    }

    private void copyLog() {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(
                ClipData.newPlainText("Sopro USB Log", log.toString()));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void saveLog() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) {
                throw new IllegalStateException("Documents directory unavailable");
            }

            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Could not create log directory");
            }

            String stamp = new SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.US).format(new Date());

            File file = new File(dir,
                    "sopro_usb_log_" + stamp + ".txt");

            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(log.toString().getBytes(StandardCharsets.UTF_8));
            }

            append("Log saved: " + file.getAbsolutePath());
            Toast.makeText(this,
                    "Log saved in app Documents folder",
                    Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            append("✗ Save log failed: " + e);
            Toast.makeText(this,
                    "Save failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void append(String text) {
        log.append(text).append('\n');
        logView.setText(log.toString());
    }

    private void setStatus(String text) {
        deviceStatus.setText(text);
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {}
            connection = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeConnection();
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {}
    }
}
