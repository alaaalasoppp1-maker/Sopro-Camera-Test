package com.dtdc.soprotest;

import android.app.Activity;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final String ACTION_USB_PERMISSION = "com.dtdc.soprotest.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice targetDevice;
    private UsbDeviceConnection connection;
    private UsbInterface videoAlt0;
    private UsbEndpoint bulk84;
    private UsbEndpoint int81;

    private TextView deviceStatus;
    private TextView logView;
    private ScrollView logScroll;
    private final StringBuilder log = new StringBuilder();

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = getUsbDeviceExtra(intent);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted && device != null) {
                    append("✓ USB permission granted");
                    targetDevice = device;
                    openCamera();
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
        if (Build.VERSION.SDK_INT >= 33)
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        deviceStatus = findViewById(R.id.deviceStatus);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);

        Button btnConnect = findViewById(R.id.btnConnect);
        Button btnRefresh = findViewById(R.id.btnRefresh);
        Button btnProbe = findViewById(R.id.btnProbe);
        Button btnBulk = findViewById(R.id.btnBulk);
        Button btnButton = findViewById(R.id.btnButton);
        Button btnCopy = findViewById(R.id.btnCopy);
        Button btnSave = findViewById(R.id.btnSave);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(usbReceiver, filter);

        append("Sopro Camera Test v0.2");
        append(String.format(Locale.US, "Target: VID=%04X PID=%04X", SOPRO_VID, SOPRO_PID));

        btnRefresh.setOnClickListener(v -> scanDevices(false));
        btnConnect.setOnClickListener(v -> scanDevices(true));
        btnProbe.setOnClickListener(v -> new Thread(this::probeRegisters).start());
        btnBulk.setOnClickListener(v -> new Thread(this::testBulk).start());
        btnButton.setOnClickListener(v -> new Thread(this::testPhysicalButton).start());
        btnCopy.setOnClickListener(v -> copyLog());
        btnSave.setOnClickListener(v -> saveLog());

        scanDevices(false);
    }

    private void scanDevices(boolean requestPermission) {
        append("");
        append("--- USB scan ---");
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        targetDevice = null;

        for (UsbDevice device : devices.values()) {
            append(String.format(Locale.US,
                    "Device %s | VID=%04X PID=%04X | interfaces=%d",
                    device.getDeviceName(), device.getVendorId(),
                    device.getProductId(), device.getInterfaceCount()));

            if (device.getVendorId() == SOPRO_VID && device.getProductId() == SOPRO_PID) {
                targetDevice = device;
                append("✓ Target EB1A:2821 detected");
            }
        }

        if (targetDevice == null) {
            append("✗ Target camera not found");
            setStatus("Camera not found");
            return;
        }

        setStatus("Camera detected");

        if (!requestPermission) {
            append(usbManager.hasPermission(targetDevice)
                    ? "✓ USB permission already granted"
                    : "○ Press CONNECT CAMERA");
            return;
        }

        if (usbManager.hasPermission(targetDevice)) {
            append("✓ USB permission already granted");
            openCamera();
        } else {
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
            permissionIntent.setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, permissionIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            append("Requesting USB permission...");
            usbManager.requestPermission(targetDevice, pi);
        }
    }

    private synchronized void openCamera() {
        closeConnection();
        if (targetDevice == null) return;

        connection = usbManager.openDevice(targetDevice);
        if (connection == null) {
            append("✗ openDevice() failed");
            setStatus("Open failed");
            return;
        }
        append("✓ UsbDeviceConnection opened");

        videoAlt0 = null;
        bulk84 = null;
        int81 = null;

        // Android exposes alternate settings as separate UsbInterface objects.
        // The first interface with id 0 and ISO maxPacket 0 is alt 0.
        for (int i = 0; i < targetDevice.getInterfaceCount(); i++) {
            UsbInterface intf = targetDevice.getInterface(i);
            if (intf.getId() != 0) continue;

            UsbEndpoint iso = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getAddress() == 0x82) iso = ep;
            }
            if (iso != null && iso.getMaxPacketSize() == 0) {
                videoAlt0 = intf;
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    if (ep.getAddress() == 0x84 && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK)
                        bulk84 = ep;
                    if (ep.getAddress() == 0x81 && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)
                        int81 = ep;
                }
                break;
            }
        }

        if (videoAlt0 == null) {
            append("✗ Could not locate video alternate setting 0");
            setStatus("Video interface not found");
            return;
        }

        boolean claimed = connection.claimInterface(videoAlt0, true);
        append("Video IF0 alt0 claim = " + claimed);

        if (claimed) {
            boolean set = connection.setInterface(videoAlt0);
            append("setInterface(video alt0) = " + set);
        }

        append("Bulk 0x84 = " + (bulk84 != null ? "FOUND" : "not found"));
        append("Interrupt 0x81 = " + (int81 != null ? "FOUND" : "not found"));
        setStatus("Connected — ready for tests");
    }

    // Linux em28xx uses vendor IN request type 0xC0, request 0x00,
    // value 0, index = register number for standard register reads.
    private Integer readReg(int reg) {
        if (connection == null) return null;
        byte[] b = new byte[1];
        int r = connection.controlTransfer(0xC0, 0x00, 0x0000, reg, b, 1, 1000);
        if (r == 1) return b[0] & 0xff;
        return null;
    }

    private void probeRegisters() {
        if (!ensureConnected()) return;

        append("");
        append("--- READ-ONLY EM28xx register probe ---");

        int[] regs = {
                0x00, // CHIPCFG
                0x01, // CHIPCFG2
                0x06, // I2C clock/config
                0x08, // GPIO control
                0x09, // GPIO state
                0x0A, // CHIPID
                0x0C, // USB suspend / snapshot button
                0x0E, // audio source
                0x0F, // XCLK
                0x10, // VINMODE
                0x11, // VINCTRL
                0x12, // VINENABLE
                0x14, 0x20, 0x21, 0x22, 0x25, 0x27
        };

        for (int reg : regs) {
            Integer v = readReg(reg);
            if (v == null)
                append(String.format(Locale.US, "REG 0x%02X -> READ FAILED", reg));
            else
                append(String.format(Locale.US, "REG 0x%02X = 0x%02X (%d)", reg, v, v));
        }

        Integer chip = readReg(0x0A);
        if (chip != null) {
            append("CHIPID decimal = " + chip);
            if (chip == 18) append("✓ CHIPID 18 matches EM2820 family ID");
            else if (chip == 17) append("✓ CHIPID 17 matches EM2710 family ID");
            else append("ℹ CHIPID recorded; do not infer exact board yet");
        }

        Integer snap = readReg(0x0C);
        if (snap != null)
            append("Snapshot bit (0x20) currently = " + (((snap & 0x20) != 0) ? "SET" : "clear"));

        append("--- Probe complete ---");
    }

    private void testBulk() {
        if (!ensureConnected()) return;
        if (bulk84 == null) {
            append("✗ Bulk endpoint 0x84 unavailable");
            return;
        }

        append("");
        append("--- BULK 0x84 test: 3 seconds ---");
        byte[] buf = new byte[16384];
        long end = System.currentTimeMillis() + 3000;
        long total = 0;
        int packets = 0;
        int timeouts = 0;
        String firstBytes = null;

        while (System.currentTimeMillis() < end) {
            int n = connection.bulkTransfer(bulk84, buf, buf.length, 120);
            if (n > 0) {
                total += n;
                packets++;
                if (firstBytes == null) firstBytes = hexPrefix(buf, n, 32);
            } else {
                timeouts++;
            }
        }

        append("Bulk reads with data = " + packets);
        append("Bulk bytes received = " + total);
        append("Timeout/empty reads = " + timeouts);
        if (firstBytes != null) append("First bytes: " + firstBytes);

        if (total > 0)
            append("✓ Data exists on 0x84 before custom video initialization");
        else
            append("○ No bulk data yet — likely requires camera/video initialization or uses ISO 0x82");

        append("--- Bulk test complete ---");
    }

    private void testPhysicalButton() {
        if (!ensureConnected()) return;

        append("");
        append("--- Physical button test: PRESS CAMERA BUTTON NOW (10 sec) ---");
        Integer initial = readReg(0x0C);
        append("Initial REG 0x0C = " + (initial == null ? "read failed" :
                String.format(Locale.US, "0x%02X", initial)));

        long end = System.currentTimeMillis() + 10000;
        boolean snapshotSeen = false;
        boolean interruptSeen = false;
        byte[] one = new byte[1];

        while (System.currentTimeMillis() < end) {
            Integer v = readReg(0x0C);
            if (v != null && (v & 0x20) != 0) {
                if (!snapshotSeen)
                    append(String.format(Locale.US, "✓ Snapshot bit detected! REG 0x0C=0x%02X", v));
                snapshotSeen = true;
            }

            if (int81 != null) {
                int n = connection.bulkTransfer(int81, one, 1, 40);
                if (n > 0) {
                    append(String.format(Locale.US,
                            "✓ Interrupt 0x81 event: 0x%02X", one[0] & 0xff));
                    interruptSeen = true;
                }
            }

            try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        }

        append("Snapshot register event = " + snapshotSeen);
        append("Interrupt endpoint event = " + interruptSeen);
        if (!snapshotSeen && !interruptSeen)
            append("○ No button event observed in this test");
        append("--- Button test complete ---");
    }

    private boolean ensureConnected() {
        if (connection == null || targetDevice == null) {
            append("✗ Not connected. Press CONNECT CAMERA first.");
            return false;
        }
        return true;
    }

    private String hexPrefix(byte[] data, int len, int max) {
        int n = Math.min(len, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", data[i] & 0xff));
        }
        if (len > n) sb.append(" ...");
        return sb.toString();
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Sopro USB Log", log.toString()));
        Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show();
    }

    private void saveLog() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) throw new IllegalStateException("Documents unavailable");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "sopro_v02_" + stamp + ".txt");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(log.toString().getBytes(StandardCharsets.UTF_8));
            }
            append("Log saved: " + file.getAbsolutePath());
        } catch (Exception e) {
            append("✗ Save failed: " + e);
        }
    }

    private void append(String text) {
        runOnUiThread(() -> {
            log.append(text).append('\n');
            logView.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> deviceStatus.setText(text));
    }

    private synchronized void closeConnection() {
        if (connection != null) {
            try {
                if (videoAlt0 != null) connection.releaseInterface(videoAlt0);
            } catch (Exception ignored) {}
            try { connection.close(); } catch (Exception ignored) {}
        }
        connection = null;
        videoAlt0 = null;
        bulk84 = null;
        int81 = null;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        closeConnection();
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
    }
}
