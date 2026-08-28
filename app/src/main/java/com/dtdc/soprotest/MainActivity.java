package com.dtdc.soprotest;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.graphics.Bitmap;
import android.hardware.usb.*;
import android.os.*;
import android.provider.MediaStore;
import android.widget.*;
import java.io.OutputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    static { System.loadLibrary("soproiso"); }

    private static final int VID = 0xEB1A, PID = 0x2821;
    private static final String ACTION_USB_PERMISSION = "com.dtdc.soprotest.USB_PERMISSION";

    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection conn;
    private UsbInterface alt0, selectedAlt;
    private int selectedIsoPacketSize = 0;
    private int selectedAltObjectIndex = -1;
    private ImageView preview;
    private TextView logView, deviceStatus, previewStatus, fpsText, profileText;
    private ScrollView logScroll;
    private final StringBuilder log = new StringBuilder();
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private Thread streamThread;
    private FrameAssembler assembler = new FrameAssembler();
    private Bitmap latestBitmap, capturedBitmap;
    private long framesShown = 0, fpsStart = 0;
    private String currentProfile = "none";
    // 0=AUTO, 1=YUYV, 2=YVYU, 3=UYVY, 4=VYUY
    private int colorMode = 0;
    private Button btnColor;
    private Button btnSource, btnFieldMode, btnPolarity, btnSaturation, btnHue;
    // Live tuning state. These controls change the running decoder/renderer
    // without rebuilding another APK.
    private int sourceIndex = 2; // starts at current SAA7113 reg 0x02 = 0xC2
    private final int[] sourceValues = {0xC0, 0xC1, 0xC2, 0xC3};
    private int fieldMode = 0; // 0=alternate BOB, 1=top only, 2=bottom only, 3=weave
    private boolean invertFieldPolarity = false;
    private int saturationIndex = 2;
    private final int[] saturationValues = {0x00, 0x20, 0x40, 0x60, 0x7F};
    private int hueIndex = 0;
    private final int[] hueValues = {0x00, 0x10, 0x20, 0x40, 0x80, 0xC0, 0xE0, 0xF0};

    public native boolean nativeIsoStart(int fd, int endpoint, int packetSize, int packetCount, int urbCount);
    public native byte[] nativeIsoRead(int timeoutMs);
    public native void nativeIsoStop();
    public native int nativeLastErrno();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (ACTION_USB_PERMISSION.equals(i.getAction())) {
                UsbDevice d = getDevice(i);
                boolean ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (ok && d != null) { device = d; append("✓ USB permission granted"); openDevice(); }
                else append("✗ USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                stopCamera(); closeDevice(); deviceStatus.setText("Disconnected");
            }
        }
    };

    @SuppressWarnings("deprecation")
    private UsbDevice getDevice(Intent i) {
        if (Build.VERSION.SDK_INT >= 33)
            return i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        return i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);

        preview = findViewById(R.id.preview);
        logView = findViewById(R.id.logView);
        logScroll = findViewById(R.id.logScroll);
        deviceStatus = findViewById(R.id.deviceStatus);
        previewStatus = findViewById(R.id.previewStatus);
        fpsText = findViewById(R.id.fpsText);
        profileText = findViewById(R.id.profileText);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);

        findViewById(R.id.btnConnect).setOnClickListener(v -> connect());
        findViewById(R.id.btnStart).setOnClickListener(v -> startCamera(false));
        findViewById(R.id.btnStop).setOnClickListener(v -> stopCamera());
        findViewById(R.id.btnCapture).setOnClickListener(v -> captureFrame());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveCaptured());
        btnColor = findViewById(R.id.btnColor);
        btnColor.setOnClickListener(v -> cycleColorMode());

        btnSource = findViewById(R.id.btnSource);
        btnFieldMode = findViewById(R.id.btnFieldMode);
        btnPolarity = findViewById(R.id.btnPolarity);
        btnSaturation = findViewById(R.id.btnSaturation);
        btnHue = findViewById(R.id.btnHue);

        btnSource.setOnClickListener(v -> cycleSourceMode());
        btnFieldMode.setOnClickListener(v -> cycleFieldMode());
        btnPolarity.setOnClickListener(v -> toggleFieldPolarity());
        btnSaturation.setOnClickListener(v -> cycleSaturation());
        btnHue.setOnClickListener(v -> cycleHue());

        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());

        append("Sopro Camera Player v0.5.5");
        append("Target EB1A:2821 / EM2820-family");
        append("Includes corrected EM28xx I2C addressing + native ISO 0x82 engine.");
        scan();
    }

    private void scan() {
        device = null;
        for (UsbDevice d: usbManager.getDeviceList().values()) {
            if (d.getVendorId()==VID && d.getProductId()==PID) { device=d; break; }
        }
        if (device == null) {
            deviceStatus.setText("Camera not found");
            return;
        }
        deviceStatus.setText(usbManager.hasPermission(device) ?
                "Camera detected — permission OK" : "Camera detected — tap CONNECT");
        if (usbManager.hasPermission(device)) openDevice();
    }

    private void connect() {
        scan();
        if (device == null) return;
        if (usbManager.hasPermission(device)) { openDevice(); return; }
        Intent in = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, in,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        usbManager.requestPermission(device, pi);
    }

    private synchronized boolean openDevice() {
        closeDevice();
        if (device == null || !usbManager.hasPermission(device)) return false;
        conn = usbManager.openDevice(device);
        if (conn == null) { append("✗ openDevice failed"); return false; }

        alt0 = null;
        selectedAlt = null;
        selectedIsoPacketSize = 0;
        selectedAltObjectIndex = -1;

        int bestEffective = -1;
        for (int i=0;i<device.getInterfaceCount();i++) {
            UsbInterface in = device.getInterface(i);
            if (in.getId()!=0) continue;

            int rawMax = -1;
            for (int e=0;e<in.getEndpointCount();e++) {
                UsbEndpoint ep = in.getEndpoint(e);
                if (ep.getAddress()==0x82) rawMax = ep.getMaxPacketSize();
            }

            if (rawMax == 0) alt0 = in;

            if (rawMax > 0) {
                int payload = rawMax & 0x7FF;
                int mult = 1 + ((rawMax >> 11) & 0x3);
                int effective = payload * mult;
                append(String.format(Locale.US,
                        "ISO alt object %d raw=0x%04X payload=%d mult=%d effective=%d",
                        i, rawMax, payload, mult, effective));
                int required = (720 * 2 + 4) * 2;
                if (selectedAlt == null && effective >= required) {
                    selectedAlt = in;
                    selectedIsoPacketSize = effective;
                    selectedAltObjectIndex = i;
                }
                if (effective > bestEffective) bestEffective = effective;
            }
        }

        if (selectedAlt == null) {
            for (int i=0;i<device.getInterfaceCount();i++) {
                UsbInterface in = device.getInterface(i);
                if (in.getId()!=0) continue;
                for (int e=0;e<in.getEndpointCount();e++) {
                    UsbEndpoint ep=in.getEndpoint(e);
                    if (ep.getAddress()!=0x82 || ep.getMaxPacketSize()<=0) continue;
                    int raw=ep.getMaxPacketSize();
                    int payload=raw & 0x7FF;
                    int mult=1+((raw>>11)&0x3);
                    int effective=payload*mult;
                    if(effective==bestEffective){ selectedAlt=in; selectedIsoPacketSize=effective; selectedAltObjectIndex=i; }
                }
            }
        }

        if (alt0==null || selectedAlt==null || selectedIsoPacketSize<=0) {
            append("✗ Required video alternate settings not found");
            return false;
        }

        append("Selected ISO alt object="+selectedAltObjectIndex+
                " effectivePacket="+selectedIsoPacketSize+
                " (Linux-style minimum for 720 full-height is 2888)");
        boolean claimed = conn.claimInterface(alt0, true);
        boolean sel = claimed && conn.setInterface(alt0);
        append("✓ USB open; claim="+claimed+" alt0="+sel);
        deviceStatus.setText("Connected");
        return claimed && sel;
    }

    private void startCamera(boolean forceRetryProfiles) {
        if (streaming.get()) stopCamera();
        if (conn==null && !openDevice()) { connect(); return; }

        new Thread(() -> {
            append("");
            append("=== START CAMERA ===");
            previewMsg("Initializing camera...");

            List<String> found = new ArrayList<>();
            found.add("0x25");
            append("Using confirmed decoder I2C address: 0x25");

            List<VideoProfile> profiles = new ArrayList<>();
            // The camera produced complete stable fields at 720x480 in v0.5.3.
            // Configure that standard explicitly first, then keep one PAL fallback.
            profiles.add(new VideoProfile("Sopro NTSC 720x480", "SOPRO_NTSC", 720, 480));
            profiles.add(new VideoProfile("Sopro PAL 720x576", "SOPRO_PAL", 720, 576));

            boolean started = false;

            for (VideoProfile vp: profiles) {
                String p = vp.initProfile;
                if (streaming.get()) return;
                append("Trying profile: "+p);
                previewMsg("Trying "+p+"...");
                stopBridgeCapture();
                sleep(80);

                boolean init = applyProfile(p);
                if (!init) {
                    append("Profile init failed: "+p);
                    continue;
                }

                if (!conn.setInterface(selectedAlt)) {
                    append("✗ setInterface alt1 failed");
                    continue;
                }

                if (!configureBridgeGeometry(vp.width, vp.height)) {
                    append("✗ Bridge geometry configuration failed for "+vp.label);
                    continue;
                }

                currentProfile = vp.label;
                runOnUiThread(() -> profileText.setText(
                        "Profile: "+currentProfile+" / ISO altObj "+selectedAltObjectIndex+
                        " / "+selectedIsoPacketSize+" B"));
                assembler.configure(vp.width, vp.height);

                // Turn bridge capture on only after profile, geometry and alt selection.
                if (!startBridgeCapture()) {
                    append("✗ bridge capture start failed");
                    conn.setInterface(alt0);
                    continue;
                }

                int fd = conn.getFileDescriptor();
                boolean isoStarted = startIsoAdaptive(fd);
                append("Persistent ISO queue final result = "+isoStarted+" errno="+nativeLastErrno());
                if (!isoStarted) {
                    stopBridgeCapture();
                    conn.setInterface(alt0);
                    continue;
                }

                int useful = 0;
                long deadline = System.currentTimeMillis()+3200;
                while (System.currentTimeMillis()<deadline && useful<12) {
                    byte[] block = nativeIsoRead(700);
                    if (block!=null && block.length>500) {
                        useful++;
                        assembler.consumeContainer(block, false);
                    }
                }
                append("ISO probe useful queued URBs = "+useful+" errno="+nativeLastErrno());

                if (useful>0) {
                    started = true;
                    break;
                }

                nativeIsoStop();
                stopBridgeCapture();
                conn.setInterface(alt0);
                sleep(100);
            }

            if (!started) {
                previewMsg("No video data — see log");
                append("✗ No ISO video data from available profiles.");
                append("The app stayed in one APK and exhausted its built-in profiles.");
                return;
            }

            append("✓ ISO video data detected with "+currentProfile);
            previewMsg("Live — "+currentProfile);
            beginLiveLoop();
        }).start();
    }


    private boolean startIsoAdaptive(int fd) {
        // Android errno=12 is ENOMEM. Keep continuous ISO URBs, but automatically
        // reduce queue depth until this phone's usbfs accepts it.
        int[][] configs = {
                {24, 5},
                {16, 5},
                {16, 4},
                {12, 4},
                {8, 4},
                {8, 3},
                {8, 2},
                {4, 2}
        };

        for (int[] cfg : configs) {
            int packets = cfg[0];
            int urbs = cfg[1];

            nativeIsoStop();
            sleep(40);

            boolean ok = nativeIsoStart(
                    fd, 0x82, selectedIsoPacketSize, packets, urbs);

            append("ISO queue try packets="+packets+
                    " urbs="+urbs+
                    " approxBytes="+(selectedIsoPacketSize*packets*urbs)+
                    " result="+ok+
                    " errno="+nativeLastErrno());

            if (ok) {
                append("✓ ISO queue accepted: "+packets+" packets × "+urbs+" URBs");
                return true;
            }

            sleep(90);
        }

        return false;
    }

    private void beginLiveLoop() {
        if (!streaming.compareAndSet(false, true)) return;
        fpsStart = System.currentTimeMillis();
        framesShown = 0;

        streamThread = new Thread(() -> {
            while (streaming.get()) {
                byte[] block = nativeIsoRead(800);
                if (block == null) continue;
                assembler.consumeContainer(block, true);
            }
        }, "SoproIsoStream");
        streamThread.start();
    }

    private void stopCamera() {
        streaming.set(false);
        if (streamThread!=null) {
            try { streamThread.join(800); } catch (Exception ignored) {}
            streamThread=null;
        }
        nativeIsoStop();
        if (conn!=null) {
            stopBridgeCapture();
            try { if (alt0!=null) conn.setInterface(alt0); } catch(Exception ignored) {}
        }
        previewMsg("Stopped");
        runOnUiThread(() -> fpsText.setText("0 fps"));
    }

    // Correct Linux em28xx convention: 7-bit I2C client address is shifted left once
    // before being put into the USB request index.
    private List<String> correctedI2cScan() {
        List<String> hits = new ArrayList<>();
        int[] common = {0x21,0x25,0x30,0x40,0x43,0x50,0x58,0x5c,0x5d,0x60,0x61,0x62,0x63};
        for (int a: common) {
            byte[] b = new byte[1];
            int r = conn.controlTransfer(0xC0, 0x02, 0, a<<1, b, 1, 250);
            int st = readReg(0x05);
            if (r==1 && st==0) {
                hits.add(String.format(Locale.US,"0x%02X",a));
                append(String.format(Locale.US,"I2C ACK 7-bit 0x%02X raw 0x%02X byte=0x%02X",
                        a,a<<1,b[0]&0xff));
            }
        }
        return hits;
    }


    private static class VideoProfile {
        final String label;
        final String initProfile;
        final int width;
        final int height;
        VideoProfile(String label, String initProfile, int width, int height) {
            this.label = label;
            this.initProfile = initProfile;
            this.width = width;
            this.height = height;
        }
    }

    private boolean configureBridgeGeometry(int width, int height) {
        int cwidth = (width >> 2) & 0xFF;
        int cheight = (height >> 2) & 0xFF;
        int overflow = ((height >> 9) & 0x02) | ((width >> 10) & 0x01);

        boolean ok = true;
        ok &= writeReg(0x27, 0x34);
        ok &= writeReg(0x10, 0x10);
        ok &= writeReg(0x11, 0x11);

        ok &= writeReg(0x1C, 0x00);
        ok &= writeReg(0x1D, 0x00);
        ok &= writeReg(0x1E, cwidth);
        ok &= writeReg(0x1F, cheight);
        ok &= writeReg(0x1B, overflow);

        ok &= writeReg(0x28, 0x01);
        ok &= writeReg(0x29, ((width - 4) >> 2) & 0xFF);
        ok &= writeReg(0x2A, 0x01);
        ok &= writeReg(0x2B, ((height - 4) >> 2) & 0xFF);

        ok &= writeReg(0x30, 0x00);
        ok &= writeReg(0x31, 0x00);
        ok &= writeReg(0x32, 0x00);
        ok &= writeReg(0x33, 0x00);
        ok &= writeReg(0x26, 0x00);

        append("Geometry "+width+"x"+height+
                " cwidth="+cwidth+" cheight="+cheight+
                " overflow="+hex(overflow)+" ok="+ok);
        return ok;
    }

    private boolean applyProfile(String p) {
        boolean ok = true;
        ok &= writeReg(0x20, 0x10);
        ok &= writeReg(0x21, 0x00);
        ok &= writeReg(0x22, 0x10);
        ok &= writeReg(0x25, 0x00);

        if ("SAA7113".equals(p) || "SOPRO_NTSC".equals(p) || "SOPRO_PAL".equals(p)) {
            // Linux saa7113_init table, sent to 7-bit 0x25 (raw USB index 0x4A)
            int[][] t = {
                    {0x01,0x08},{0x02,sourceValues[sourceIndex]},{0x03,0x30},{0x04,0x00},{0x05,0x00},
                    {0x06,0x89},{0x07,0x0D},{0x08,0x88},{0x09,0x01},{0x0A,0x80},
                    {0x0B,0x47},{0x0C,0x40},{0x0D,0x00},{0x0E,0x01},{0x0F,0x2A},
                    {0x10,0x08},{0x11,0x0C},{0x12,0x07},{0x13,0x00},{0x14,0x00},
                    {0x15,0x00},{0x16,0x00},{0x17,0x00}
            };
            int good=0;
            for (int[] rv: t) if (i2cWriteReg8(0x25, rv[0], rv[1])) good++;
            append("SAA7113 writes ACK = "+good+"/"+t.length);
            ok &= good >= 16;

            if ("SOPRO_NTSC".equals(p)) {
                // Linux SAA7113 525/60 selection: clear auto-detect and set FSEL.
                int r08 = i2cReadReg8(0x25, 0x08);
                if (r08 >= 0) ok &= i2cWriteReg8(0x25, 0x08, (r08 & ~0xC0) | 0x40);
                int r0e = i2cReadReg8(0x25, 0x0E);
                if (r0e >= 0) ok &= i2cWriteReg8(0x25, 0x0E, r0e & 0x8F); // NTSC-M chroma
                append("SAA7113 forced to NTSC-M / 525-60");
            } else if ("SOPRO_PAL".equals(p)) {
                int r08 = i2cReadReg8(0x25, 0x08);
                if (r08 >= 0) ok &= i2cWriteReg8(0x25, 0x08, r08 & ~0xC0);
                int r0e = i2cReadReg8(0x25, 0x0E);
                if (r0e >= 0) ok &= i2cWriteReg8(0x25, 0x0E, r0e & 0x8F); // PAL B/G/D/H/I
                append("SAA7113 forced to PAL / 625-50");
            }
        } else if ("GENERIC_ANALOG".equals(p)) {
            // Keep decoder state untouched, bridge only.
            append("Generic analog bridge profile applied.");
        } else {
            append("Bridge-only profile applied.");
        }

        return ok;
    }

    private int i2cReadReg8(int addr7, int reg) {
        byte[] index={(byte)reg};
        int wr=conn.controlTransfer(0x40,0x02,0,addr7<<1,index,1,300);
        if (wr!=1) return -1;
        byte[] out=new byte[1];
        int rd=conn.controlTransfer(0xC0,0x02,0,addr7<<1,out,1,300);
        int st=readReg(0x05);
        return (rd==1 && st==0) ? (out[0]&0xff) : -1;
    }

    private boolean i2cWriteReg8(int addr7, int reg, int val) {
        byte[] b={(byte)reg,(byte)val};
        int r=conn.controlTransfer(0x40,0x02,0,addr7<<1,b,b.length,350);
        int st=readReg(0x05);
        return r==2 && st==0;
    }

    private boolean startBridgeCapture() {
        int r0c=readReg(0x0C);
        boolean ok=true;
        if (r0c>=0) ok &= writeReg(0x0C,(r0c|0x10)&0xff);
        ok &= writeReg(0x48,0x00);
        ok &= writeReg(0x12,0x67);
        sleep(12);
        append("Bridge capture start="+ok+" R12="+hex(readReg(0x12)));
        return ok;
    }

    private void stopBridgeCapture() {
        if (conn==null) return;
        writeReg(0x12,0x27);
        int r0c=readReg(0x0C);
        if (r0c>=0) writeReg(0x0C,r0c & ~0x10);
    }

    private int readReg(int reg) {
        if (conn==null) return -1;
        byte[] b=new byte[1];
        int r=conn.controlTransfer(0xC0,0x00,0,reg,b,1,300);
        return r==1 ? b[0]&0xff : -1;
    }

    private boolean writeReg(int reg,int val) {
        if (conn==null) return false;
        byte[] b={(byte)val};
        int r=conn.controlTransfer(0x40,0x00,0,reg,b,1,300);
        return r==1;
    }

    private String hex(int v){ return v<0 ? "ERR" : String.format(Locale.US,"0x%02X",v); }


    private void cycleSourceMode() {
        sourceIndex = (sourceIndex + 1) % sourceValues.length;
        int v = sourceValues[sourceIndex];
        if (conn != null) {
            boolean ok = i2cWriteReg8(0x25, 0x02, v);
            append(String.format(Locale.US,
                    "Live source mux REG02 -> 0x%02X result=%s", v, ok));
        }
        if (btnSource != null)
            btnSource.setText(String.format(Locale.US, "SOURCE REG02: 0x%02X", v));
    }

    private void cycleFieldMode() {
        fieldMode = (fieldMode + 1) % 4;
        String[] names = {"BOB ALT", "BOB TOP", "BOB BOTTOM", "WEAVE"};
        if (btnFieldMode != null) btnFieldMode.setText("FIELD MODE: " + names[fieldMode]);
        append("Field display mode changed to " + names[fieldMode]);
        assembler.resetDisplayPair();
    }

    private void toggleFieldPolarity() {
        invertFieldPolarity = !invertFieldPolarity;
        if (btnPolarity != null)
            btnPolarity.setText("FIELD POLARITY: " + (invertFieldPolarity ? "INVERTED" : "NORMAL"));
        append("Field polarity = " + (invertFieldPolarity ? "INVERTED" : "NORMAL"));
        assembler.resetDisplayPair();
    }

    private void cycleSaturation() {
        saturationIndex = (saturationIndex + 1) % saturationValues.length;
        int v = saturationValues[saturationIndex];
        if (conn != null) {
            boolean ok = i2cWriteReg8(0x25, 0x0C, v);
            append(String.format(Locale.US,
                    "Live saturation REG0C -> 0x%02X result=%s", v, ok));
        }
        if (btnSaturation != null)
            btnSaturation.setText(String.format(Locale.US, "SATURATION: 0x%02X", v));
    }

    private void cycleHue() {
        hueIndex = (hueIndex + 1) % hueValues.length;
        int v = hueValues[hueIndex];
        if (conn != null) {
            boolean ok = i2cWriteReg8(0x25, 0x0D, v);
            append(String.format(Locale.US,
                    "Live hue REG0D -> 0x%02X result=%s", v, ok));
        }
        if (btnHue != null)
            btnHue.setText(String.format(Locale.US, "HUE: 0x%02X", v));
    }

    private void cycleColorMode() {
        colorMode = (colorMode + 1) % 5;
        String[] names = {"AUTO", "YUYV", "YVYU", "UYVY", "VYUY"};
        if (btnColor != null) btnColor.setText("COLOR MODE: " + names[colorMode]);
        append("Color mode changed to " + names[colorMode]);
    }

    private int chooseAutoColorMode(byte[] frame, int width, int height) {
        // Hardware is configured for YUYV. Auto only decides whether U/V need
        // swapping, which is the common symptom when an analog decoder has
        // the right luminance but the whole picture is strongly red/blue.
        long scoreYuyv = colorBalanceScore(frame, width, height, 1);
        long scoreYvyu = colorBalanceScore(frame, width, height, 2);
        return scoreYvyu + scoreYvyu/12 < scoreYuyv ? 2 : 1;
    }

    private long colorBalanceScore(byte[] frame, int width, int height, int mode) {
        long sumR=0,sumG=0,sumB=0,clip=0,n=0;
        int step=Math.max(4, (width*height/12000)*4);
        for(int i=0;i+3<frame.length;i+=step){
            int a=frame[i]&255,b=frame[i+1]&255,c=frame[i+2]&255,d=frame[i+3]&255;
            int y0,u,y1,v;
            if(mode==2){ y0=a; v=b; y1=c; u=d; }
            else { y0=a; u=b; y1=c; v=d; }
            int rgb0=yuvPacked(y0,u,v), rgb1=yuvPacked(y1,u,v);
            int r0=(rgb0>>16)&255,g0=(rgb0>>8)&255,b0=rgb0&255;
            int r1=(rgb1>>16)&255,g1=(rgb1>>8)&255,b1=rgb1&255;
            sumR+=r0+r1; sumG+=g0+g1; sumB+=b0+b1; n+=2;
            if(r0<3||r0>252||g0<3||g0>252||b0<3||b0>252) clip++;
            if(r1<3||r1>252||g1<3||g1>252||b1<3||b1>252) clip++;
        }
        if(n==0) return Long.MAX_VALUE/4;
        long ar=sumR/n, ag=sumG/n, ab=sumB/n;
        long imbalance=Math.abs(ar-ag)+Math.abs(ag-ab)+Math.abs(ar-ab);
        return imbalance*20 + clip*5;
    }

    private int yuvPacked(int y,int u,int v){
        int c=y-16,d=u-128,e=v-128;
        int r=Math.max(0,Math.min(255,(298*c+409*e+128)>>8));
        int g=Math.max(0,Math.min(255,(298*c-100*d-208*e+128)>>8));
        int b=Math.max(0,Math.min(255,(298*c+516*d+128)>>8));
        return (r<<16)|(g<<8)|b;
    }

    private void captureFrame() {
        if (latestBitmap==null) {
            Toast.makeText(this,"No live frame yet",Toast.LENGTH_SHORT).show(); return;
        }
        capturedBitmap = latestBitmap.copy(Bitmap.Config.ARGB_8888,false);
        Toast.makeText(this,"Frame captured",Toast.LENGTH_SHORT).show();
        previewMsg("Captured — press SAVE IMAGE");
    }

    private void saveCaptured() {
        Bitmap b = capturedBitmap!=null ? capturedBitmap : latestBitmap;
        if (b==null) { Toast.makeText(this,"Nothing to save",Toast.LENGTH_SHORT).show(); return; }
        try {
            String name="Sopro_"+System.currentTimeMillis()+".jpg";
            ContentValues cv=new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,name);
            cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            if (Build.VERSION.SDK_INT>=29)
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/SoproCamera");
            android.net.Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);
            if(uri==null) throw new Exception("MediaStore insert failed");
            try(OutputStream os=getContentResolver().openOutputStream(uri)){
                b.compress(Bitmap.CompressFormat.JPEG,95,os);
            }
            Toast.makeText(this,"Saved: "+name,Toast.LENGTH_LONG).show();
            append("Saved image: "+name);
        } catch(Exception e) {
            append("✗ Save failed: "+e);
            Toast.makeText(this,"Save failed",Toast.LENGTH_SHORT).show();
        }
    }

    private void onFrame(Bitmap b) {
        latestBitmap=b;
        framesShown++;
        long now=System.currentTimeMillis();
        if(now-fpsStart>=1000){
            final long f=framesShown;
            framesShown=0; fpsStart=now;
            runOnUiThread(() -> fpsText.setText(f+" fps"));
        }
        runOnUiThread(() -> preview.setImageBitmap(b));
    }

    private void previewMsg(String s){ runOnUiThread(() -> previewStatus.setText(s)); }

    private void copyLog(){
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Sopro log",log.toString()));
        Toast.makeText(this,"Log copied",Toast.LENGTH_SHORT).show();
    }

    private void append(String s){
        runOnUiThread(() -> {
            log.append(s).append('\n');
            logView.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void sleep(long ms){ try{Thread.sleep(ms);}catch(Exception ignored){} }

    private synchronized void closeDevice(){
        if(conn!=null){
            try{ if(alt0!=null) conn.releaseInterface(alt0); }catch(Exception ignored){}
            try{ conn.close(); }catch(Exception ignored){}
        }
        conn=null; alt0=null; selectedAlt=null; selectedIsoPacketSize=0; selectedAltObjectIndex=-1;
    }

    @Override protected void onDestroy(){
        stopCamera(); closeDevice();
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        super.onDestroy();
    }

    private class FrameAssembler {
        private int width = 720, height = 576;
        private byte[] yuyv = new byte[width * height * 2];
        private boolean configured = false;
        private boolean haveField = false;
        private boolean currentTop = true;
        private int fieldBytes = 0;
        private boolean topReady = false, bottomReady = false;
        private int completedFields = 0;

        synchronized void configure(int w, int h) {
            width = w;
            height = h;
            yuyv = new byte[width * height * 2];
            configured = true;
            haveField = false;
            fieldBytes = 0;
            topReady = false;
            bottomReady = false;
            completedFields = 0;
            append("Assembler configured explicitly: " + width + "x" + height);
        }

        synchronized void consumeContainer(byte[] c, boolean render) {
            if (!configured) return;
            int pos = 0;
            while (pos + 2 <= c.length) {
                int len = (c[pos] & 0xff) | ((c[pos + 1] & 0xff) << 8);
                pos += 2;
                if (len <= 0 || pos + len > c.length) break;
                consumePacket(c, pos, len, render);
                pos += len;
            }
        }

        private void consumePacket(byte[] p, int off, int len, boolean render) {
            if (len < 4) return;
            boolean continuation = eq4(p, off, 0x88, 0x88, 0x88, 0x88);
            boolean fieldStart = (p[off] & 0xff) == 0x22 && (p[off + 1] & 0xff) == 0x5A;

            int dataOff = off;
            int dataLen = len;

            if (continuation) {
                dataOff += 4;
                dataLen -= 4;
            } else if (fieldStart) {
                boolean newTop = ((p[off + 2] & 1) == 0);
                if (invertFieldPolarity) newTop = !newTop;
                if (haveField) finishCurrentField(render);
                if (newTop) { topReady = false; bottomReady = false; }
                currentTop = newTop;
                haveField = true;
                fieldBytes = 0;
                dataOff += 4;
                dataLen -= 4;
            } else {
                if (!haveField) return;
            }

            if (!haveField || dataLen <= 0) return;
            copyInterlaced(p, dataOff, dataLen);
        }

        private void finishCurrentField(boolean render) {
            completedFields++;
            int expected = width * height;

            if (completedFields <= 10) {
                append("Field #" + completedFields + " " +
                        (currentTop ? "TOP" : "BOTTOM") +
                        " bytes=" + fieldBytes + " expected~" + expected);
            }

            boolean completeEnough = fieldBytes >= (expected * 90 / 100);
            if (completeEnough) {
                if (currentTop) topReady = true;
                else bottomReady = true;

                if (render) {
                    switch (fieldMode) {
                        case 1: // TOP only
                            if (currentTop) renderBobField(true);
                            break;
                        case 2: // BOTTOM only
                            if (!currentTop) renderBobField(false);
                            break;
                        case 3: // WEAVE after both complete
                            if (topReady && bottomReady) {
                                renderWeaveFrame();
                                topReady = false;
                                bottomReady = false;
                            }
                            break;
                        case 0:
                        default: // alternate BOB
                            renderBobField(currentTop);
                            break;
                    }
                }
            } else if (completedFields <= 20) {
                append("Dropped incomplete "+(currentTop ? "TOP" : "BOTTOM")+
                        " field: "+fieldBytes+"/"+expected);
            }
        }

        private void copyInterlaced(byte[] src, int off, int len) {
            int bytesPerLine = width * 2;
            int srcPos = 0;

            while (srcPos < len) {
                int fieldLine = fieldBytes / bytesPerLine;
                int inLine = fieldBytes % bytesPerLine;
                int dstLine = fieldLine * 2 + (currentTop ? 0 : 1);
                if (dstLine >= height) break;

                int n = Math.min(len - srcPos, bytesPerLine - inLine);
                int dst = dstLine * bytesPerLine + inLine;

                if (dst >= 0 && dst + n <= yuyv.length)
                    System.arraycopy(src, off + srcPos, yuyv, dst, n);

                srcPos += n;
                fieldBytes += n;
            }
        }


        synchronized void resetDisplayPair() {
            topReady = false;
            bottomReady = false;
        }

        private void renderWeaveFrame() {
            int mode = colorMode;
            if (mode == 0) mode = chooseAutoColorMode(yuyv, width, height);
            int[] argb = decodePackedFrame(yuyv, mode);
            Bitmap bmp = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            onFrame(bmp);
        }

        private int[] decodePackedFrame(byte[] packed, int mode) {
            int[] argb = new int[width * height];
            int out = 0;
            for (int i = 0; i + 3 < packed.length && out + 1 < argb.length; i += 4) {
                int a=packed[i]&255, b=packed[i+1]&255, c=packed[i+2]&255, d=packed[i+3]&255;
                int y0,u,y1,v;
                switch(mode){
                    case 2: y0=a; v=b; y1=c; u=d; break;
                    case 3: u=a; y0=b; v=c; y1=d; break;
                    case 4: v=a; y0=b; u=c; y1=d; break;
                    case 1:
                    default: y0=a; u=b; y1=c; v=d; break;
                }
                argb[out++] = yuvToArgb(y0,u,v);
                argb[out++] = yuvToArgb(y1,u,v);
            }
            return argb;
        }

        private void renderBobField(boolean top) {
            int fieldLines = height / 2;
            int bytesPerLine = width * 2;
            byte[] bob = new byte[width * height * 2];

            for (int fl = 0; fl < fieldLines; fl++) {
                int srcLine = fl * 2 + (top ? 0 : 1);
                int src = srcLine * bytesPerLine;
                int dst0 = (fl * 2) * bytesPerLine;
                int dst1 = (fl * 2 + 1) * bytesPerLine;
                if (src + bytesPerLine <= yuyv.length && dst1 + bytesPerLine <= bob.length) {
                    System.arraycopy(yuyv, src, bob, dst0, bytesPerLine);
                    System.arraycopy(yuyv, src, bob, dst1, bytesPerLine);
                }
            }

            int mode = colorMode;
            if (mode == 0) mode = chooseAutoColorMode(bob, width, height);
            int[] argb = decodePackedFrame(bob, mode);

            Bitmap bmp=Bitmap.createBitmap(argb,width,height,Bitmap.Config.ARGB_8888);
            onFrame(bmp);
        }

        private int yuvToArgb(int y, int u, int v) {
            int c = y - 16, d = u - 128, e = v - 128;
            int r = clamp((298 * c + 409 * e + 128) >> 8);
            int g = clamp((298 * c - 100 * d - 208 * e + 128) >> 8);
            int b = clamp((298 * c + 516 * d + 128) >> 8);
            return 0xff000000 | (r << 16) | (g << 8) | b;
        }

        private int clamp(int x) { return x < 0 ? 0 : (x > 255 ? 255 : x); }

        private boolean eq4(byte[] a, int o, int b0, int b1, int b2, int b3) {
            return (a[o] & 255) == b0 &&
                    (a[o + 1] & 255) == b1 &&
                    (a[o + 2] & 255) == b2 &&
                    (a[o + 3] & 255) == b3;
        }
    }

}