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
import android.widget.ProgressBar;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    private TextView deviceStatus, logView, progressText;
    private ScrollView logScroll;
    private ProgressBar progress;
    private final StringBuilder log = new StringBuilder();
    private final AtomicBoolean testRunning = new AtomicBoolean(false);

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
                append("! USB device detached");
                closeConnection();
                targetDevice = null;
                setStatus("Camera disconnected");
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
        progress = findViewById(R.id.progress);
        progressText = findViewById(R.id.progressText);

        Button btnRunAll = findViewById(R.id.btnRunAll);
        Button btnConnect = findViewById(R.id.btnConnect);
        Button btnDescriptor = findViewById(R.id.btnDescriptor);
        Button btnProbe = findViewById(R.id.btnProbe);
        Button btnI2cCommon = findViewById(R.id.btnI2cCommon);
        Button btnI2cFull = findViewById(R.id.btnI2cFull);
        Button btnAlt = findViewById(R.id.btnAlt);
        Button btnBulk = findViewById(R.id.btnBulk);
        Button btnButton = findViewById(R.id.btnButton);
        Button btnRefresh = findViewById(R.id.btnRefresh);
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

        append("Sopro Camera Test v0.4 — One Shot Diagnostics");
        append(String.format(Locale.US, "Target VID=%04X PID=%04X", SOPRO_VID, SOPRO_PID));
        append("Known bridge: EM28xx, CHIPID expected 0x12 / 18");

        btnRunAll.setOnClickListener(v -> runAllTests());
        btnConnect.setOnClickListener(v -> scanDevices(true));
        btnDescriptor.setOnClickListener(v -> new Thread(this::dumpRawDescriptors).start());
        btnProbe.setOnClickListener(v -> new Thread(this::probeRegistersFull).start());
        btnI2cCommon.setOnClickListener(v -> new Thread(this::probeCommonI2c).start());
        btnI2cFull.setOnClickListener(v -> new Thread(this::fullI2cScan).start());
        btnAlt.setOnClickListener(v -> new Thread(this::testAltSettings).start());
        btnBulk.setOnClickListener(v -> new Thread(this::testBulk).start());
        btnButton.setOnClickListener(v -> new Thread(this::testPhysicalButton).start());
        btnRefresh.setOnClickListener(v -> scanDevices(false));
        btnCopy.setOnClickListener(v -> copyLog());
        btnSave.setOnClickListener(v -> saveLog());

        scanDevices(false);
    }

    private void runAllTests() {
        if (!testRunning.compareAndSet(false, true)) {
            Toast.makeText(this, "Tests are already running", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                setProgress(2, "Connecting camera...");
                if (!connectForAutomation()) {
                    append("✗ RUN ALL stopped: camera could not be opened.");
                    setProgress(0, "Connection failed");
                    return;
                }

                append("");
                append("================================");
                append("RUN ALL TESTS STARTED");
                append("================================");

                setProgress(10, "Reading raw USB descriptors...");
                dumpRawDescriptors();

                setProgress(25, "Dumping EM28xx registers...");
                probeRegistersFull();

                setProgress(42, "Probing common I2C addresses...");
                probeCommonI2c();

                setProgress(57, "Scanning complete I2C bus...");
                fullI2cScan();

                setProgress(70, "Testing video alternate settings...");
                testAltSettings();

                setProgress(80, "Testing bulk endpoint 0x84...");
                testBulk();

                setProgress(88, "PRESS PHYSICAL CAMERA BUTTON NOW — test runs for 10 seconds");
                testPhysicalButton();

                setProgress(100, "All tests completed — COPY LOG and send it");
                append("");
                append("================================");
                append("✓ RUN ALL TESTS COMPLETE");
                append("Copy the complete log and send it back.");
                append("================================");
            } finally {
                testRunning.set(false);
            }
        }).start();
    }

    private boolean connectForAutomation() {
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        targetDevice = null;
        for (UsbDevice d : devices.values()) {
            if (d.getVendorId() == SOPRO_VID && d.getProductId() == SOPRO_PID) {
                targetDevice = d;
                break;
            }
        }
        if (targetDevice == null) {
            append("✗ EB1A:2821 not found.");
            return false;
        }
        if (!usbManager.hasPermission(targetDevice)) {
            append("✗ USB permission is not granted yet.");
            append("Press CONNECT once, accept Android permission, then press RUN ALL TESTS.");
            return false;
        }
        return openCamera();
    }

    private void scanDevices(boolean requestPermission) {
        append("");
        append("--- USB scan ---");
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        targetDevice = null;
        append("Visible devices = " + devices.size());

        for (UsbDevice device : devices.values()) {
            append(String.format(Locale.US,
                    "%s | VID=%04X PID=%04X class=%d interfaces=%d",
                    device.getDeviceName(), device.getVendorId(), device.getProductId(),
                    device.getDeviceClass(), device.getInterfaceCount()));

            if (device.getVendorId() == SOPRO_VID && device.getProductId() == SOPRO_PID) {
                targetDevice = device;
                append("✓ Target EB1A:2821 detected");
            }
        }

        if (targetDevice == null) {
            setStatus("Camera not found");
            return;
        }

        if (!requestPermission) {
            setStatus(usbManager.hasPermission(targetDevice) ?
                    "Camera detected — permission OK" : "Camera detected — permission required");
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

    private synchronized boolean openCamera() {
        closeConnection();
        if (targetDevice == null) return false;

        connection = usbManager.openDevice(targetDevice);
        if (connection == null) {
            append("✗ openDevice() failed");
            return false;
        }
        append("✓ UsbDeviceConnection opened");

        videoAlt0 = null;
        bulk84 = null;
        int81 = null;

        for (int i = 0; i < targetDevice.getInterfaceCount(); i++) {
            UsbInterface intf = targetDevice.getInterface(i);
            if (intf.getId() != 0) continue;

            UsbEndpoint iso82 = null;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getAddress() == 0x82) iso82 = ep;
            }

            if (iso82 != null && iso82.getMaxPacketSize() == 0) {
                videoAlt0 = intf;
                for (int e = 0; e < intf.getEndpointCount(); e++) {
                    UsbEndpoint ep = intf.getEndpoint(e);
                    if (ep.getAddress() == 0x84 &&
                            ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) bulk84 = ep;
                    if (ep.getAddress() == 0x81 &&
                            ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) int81 = ep;
                }
                break;
            }
        }

        if (videoAlt0 == null) {
            append("✗ Video interface alt0 not found");
            return false;
        }

        boolean claimed = connection.claimInterface(videoAlt0, true);
        append("Video IF0 alt0 claim = " + claimed);
        if (!claimed) return false;

        boolean selected = connection.setInterface(videoAlt0);
        append("setInterface(video alt0) = " + selected);
        append("Bulk 0x84 = " + (bulk84 != null ? "FOUND" : "not found"));
        append("Interrupt 0x81 = " + (int81 != null ? "FOUND" : "not found"));

        setStatus("Connected — ready");
        return true;
    }

    private Integer readReg(int reg) {
        if (connection == null) return null;
        byte[] b = new byte[1];
        int r = connection.controlTransfer(0xC0, 0x00, 0x0000, reg, b, 1, 700);
        return r == 1 ? (b[0] & 0xff) : null;
    }

    private void dumpRawDescriptors() {
        if (!ensureConnected()) return;
        append("");
        append("--- RAW USB DESCRIPTORS ---");

        byte[] d = connection.getRawDescriptors();
        if (d == null || d.length == 0) {
            append("✗ getRawDescriptors returned no data");
            return;
        }

        append("Raw descriptor bytes = " + d.length);
        for (int i = 0; i < d.length; i += 16) {
            int n = Math.min(16, d.length - i);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%04X: ", i));
            for (int j = 0; j < n; j++)
                sb.append(String.format(Locale.US, "%02X ", d[i+j] & 0xff));
            append(sb.toString());
        }

        append("--- Descriptor summary ---");
        for (int i = 0; i < targetDevice.getInterfaceCount(); i++) {
            UsbInterface intf = targetDevice.getInterface(i);
            append(String.format(Locale.US,
                    "IFobj[%02d] id=%d class=%d sub=%d proto=%d eps=%d",
                    i, intf.getId(), intf.getInterfaceClass(),
                    intf.getInterfaceSubclass(), intf.getInterfaceProtocol(),
                    intf.getEndpointCount()));
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                append(String.format(Locale.US,
                        "  EP 0x%02X %s %s max=%d interval=%d",
                        ep.getAddress(),
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT",
                        typeName(ep.getType()), ep.getMaxPacketSize(), ep.getInterval()));
            }
        }
        append("--- Raw descriptors complete ---");
    }

    private void probeRegistersFull() {
        if (!ensureConnected()) return;
        append("");
        append("--- EM28XX REGISTER DUMP 0x00..0x3F ---");

        int success = 0;
        for (int reg = 0x00; reg <= 0x3F; reg++) {
            Integer v = readReg(reg);
            if (v != null) {
                success++;
                append(String.format(Locale.US, "REG 0x%02X = 0x%02X (%d)", reg, v, v));
            } else {
                append(String.format(Locale.US, "REG 0x%02X = READ FAILED", reg));
            }
        }

        Integer chip = readReg(0x0A);
        append("Readable registers = " + success + "/64");
        if (chip != null) {
            append(String.format(Locale.US, "CHIPID = 0x%02X / %d", chip, chip));
            if (chip == 0x12) append("✓ EM2820-family CHIPID confirmed");
        }
        append("--- Register dump complete ---");
    }

    private static class I2cResult {
        final boolean ok;
        final int value;
        final int status;
        final int usbRet;
        I2cResult(boolean ok, int value, int status, int usbRet) {
            this.ok = ok; this.value = value; this.status = status; this.usbRet = usbRet;
        }
    }

    private I2cResult i2cReadByte(int addr7) {
        if (connection == null) return new I2cResult(false, -1, -1, -1);
        byte[] b = new byte[1];
        int r = connection.controlTransfer(0xC0, 0x02, 0x0000, addr7, b, 1, 350);
        Integer status = readReg(0x05);
        int st = status == null ? -1 : status;
        boolean ok = (r == 1 && st == 0x00);
        return new I2cResult(ok, r == 1 ? (b[0] & 0xff) : -1, st, r);
    }

    private void probeCommonI2c() {
        if (!ensureConnected()) return;
        append("");
        append("--- COMMON I2C PROBE ---");

        int[] addrs = {0x20,0x21,0x24,0x25,0x30,0x40,0x43,0x48,0x4A,0x50,
                0x58,0x5A,0x5C,0x5D,0x60,0x61,0x62,0x63};

        int found = 0;
        for (int addr : addrs) {
            I2cResult x = i2cReadByte(addr);
            if (x.ok) {
                found++;
                append(String.format(Locale.US,
                        "✓ I2C 0x%02X ACK firstByte=0x%02X status=0x%02X",
                        addr, x.value, x.status));
            } else {
                append(String.format(Locale.US,
                        "· I2C 0x%02X no ACK usbRet=%d status=%s",
                        addr, x.usbRet,
                        x.status >= 0 ? String.format(Locale.US,"0x%02X",x.status) : "N/A"));
            }
            sleepMs(20);
        }
        append("Common I2C devices found = " + found);
        append("--- Common I2C probe complete ---");
    }

    private void fullI2cScan() {
        if (!ensureConnected()) return;
        append("");
        append("--- FULL I2C SCAN 0x08..0x77 ---");
        int found = 0;
        StringBuilder hits = new StringBuilder();

        for (int addr = 0x08; addr <= 0x77; addr++) {
            I2cResult x = i2cReadByte(addr);
            if (x.ok) {
                found++;
                if (hits.length() > 0) hits.append(", ");
                hits.append(String.format(Locale.US, "0x%02X", addr));
                append(String.format(Locale.US,
                        "✓ I2C 0x%02X ACK firstByte=0x%02X",
                        addr, x.value));
            }
            sleepMs(10);
        }

        append("Devices found = " + found);
        append("ACK addresses = " + (hits.length() == 0 ? "(none)" : hits.toString()));
        append("--- Full I2C scan complete ---");
    }

    private void testAltSettings() {
        if (!ensureConnected()) return;
        append("");
        append("--- VIDEO ALTERNATE SETTINGS TEST ---");
        append("Read-only selection test; no video-register writes.");

        UsbInterface restore = videoAlt0;
        for (int i = 0; i < targetDevice.getInterfaceCount(); i++) {
            UsbInterface intf = targetDevice.getInterface(i);
            if (intf.getId() != 0) continue;

            int isoMax = -1;
            for (int e = 0; e < intf.getEndpointCount(); e++) {
                UsbEndpoint ep = intf.getEndpoint(e);
                if (ep.getAddress() == 0x82 &&
                        ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC) {
                    isoMax = ep.getMaxPacketSize();
                }
            }

            boolean selected = false;
            try {
                selected = connection.setInterface(intf);
            } catch (Exception ex) {
                append("ALT object " + i + " exception: " + ex.getMessage());
            }
            append(String.format(Locale.US,
                    "IF object %d / id=%d / ISO 0x82 maxPacket=%d / setInterface=%s",
                    i, intf.getId(), isoMax, selected));
            sleepMs(40);
        }

        if (restore != null) {
            try {
                boolean r = connection.setInterface(restore);
                append("Restored video alt0 = " + r);
            } catch (Exception ex) {
                append("Restore alt0 exception: " + ex.getMessage());
            }
        }
        append("--- Alternate settings test complete ---");
    }

    private void testBulk() {
        if (!ensureConnected()) return;
        if (bulk84 == null) {
            append("✗ Bulk 0x84 unavailable");
            return;
        }

        append("");
        append("--- BULK 0x84 TEST — 3 seconds ---");
        byte[] buf = new byte[16384];
        long end = System.currentTimeMillis() + 3000;
        long total = 0;
        int reads = 0;
        int empty = 0;
        String first = null;

        while (System.currentTimeMillis() < end) {
            int n = connection.bulkTransfer(bulk84, buf, buf.length, 120);
            if (n > 0) {
                reads++; total += n;
                if (first == null) first = hexPrefix(buf, n, 48);
            } else empty++;
        }

        append("Reads with data = " + reads);
        append("Bytes = " + total);
        append("Empty/timeouts = " + empty);
        if (first != null) append("First bytes = " + first);
        append(total > 0 ? "✓ Bulk data detected" :
                "○ No bulk data before board/video initialization");
        append("--- Bulk test complete ---");
    }

    private void testPhysicalButton() {
        if (!ensureConnected()) return;

        append("");
        append("--- PHYSICAL BUTTON TEST — 10 seconds ---");
        append(">>> PRESS THE CAMERA BUTTON SEVERAL TIMES NOW <<<");

        Integer initial = readReg(0x0C);
        append("Initial REG 0x0C = " +
                (initial == null ? "READ FAILED" : String.format(Locale.US,"0x%02X",initial)));

        long end = System.currentTimeMillis() + 10000;
        boolean regSeen = false;
        boolean intSeen = false;
        byte[] one = new byte[1];
        int secondsLogged = -1;

        while (System.currentTimeMillis() < end) {
            int left = (int)Math.ceil((end - System.currentTimeMillis()) / 1000.0);
            if (left != secondsLogged) {
                secondsLogged = left;
                setProgress(88 + Math.min(10, 10-left), "PRESS CAMERA BUTTON — " + Math.max(0,left) + "s");
            }

            Integer v = readReg(0x0C);
            if (v != null && (v & 0x20) != 0) {
                if (!regSeen)
                    append(String.format(Locale.US,
                            "✓ Snapshot bit detected: REG0C=0x%02X", v));
                regSeen = true;
            }

            if (int81 != null) {
                int n = connection.bulkTransfer(int81, one, 1, 35);
                if (n > 0) {
                    append(String.format(Locale.US,
                            "✓ Interrupt 0x81 event = 0x%02X", one[0] & 0xff));
                    intSeen = true;
                }
            }

            sleepMs(55);
        }

        append("Snapshot register event = " + regSeen);
        append("Interrupt event = " + intSeen);
        if (!regSeen && !intSeen)
            append("○ No button event observed before device-specific initialization.");
        append("--- Physical button test complete ---");
    }

    private boolean ensureConnected() {
        if (connection == null || targetDevice == null) {
            append("✗ Not connected. Press CONNECT first.");
            return false;
        }
        return true;
    }

    private String typeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "CONTROL";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "ISO";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "BULK";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "INT";
            default: return "UNKNOWN(" + type + ")";
        }
    }

    private String hexPrefix(byte[] data, int len, int max) {
        int n = Math.min(len, max);
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<n; i++) {
            if (i>0) sb.append(' ');
            sb.append(String.format(Locale.US,"%02X",data[i] & 0xff));
        }
        if (len > n) sb.append(" ...");
        return sb.toString();
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Sopro diagnostic log", log.toString()));
        Toast.makeText(this, "Complete log copied", Toast.LENGTH_SHORT).show();
    }

    private void saveLog() {
        try {
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            if (dir == null) throw new IllegalStateException("Documents unavailable");
            if (!dir.exists()) dir.mkdirs();

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "sopro_all_tests_" + stamp + ".txt");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(log.toString().getBytes(StandardCharsets.UTF_8));
            }
            append("Log saved: " + file.getAbsolutePath());
            Toast.makeText(this, "Log saved", Toast.LENGTH_SHORT).show();
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

    private void setProgress(int value, String text) {
        runOnUiThread(() -> {
            progress.setProgress(Math.max(0, Math.min(100, value)));
            progressText.setText(text);
        });
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
