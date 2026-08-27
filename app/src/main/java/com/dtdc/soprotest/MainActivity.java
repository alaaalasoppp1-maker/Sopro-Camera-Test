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
    private UsbInterface alt0, alt1;
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

    public native byte[] nativeReadIso(int fd, int endpoint, int packetSize, int packetCount, int timeoutMs);
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
        findViewById(R.id.btnRetry).setOnClickListener(v -> startCamera(true));
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> copyLog());

        append("Sopro Camera Player v0.5.1");
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

        alt0 = null; alt1 = null;
        for (int i=0;i<device.getInterfaceCount();i++) {
            UsbInterface in = device.getInterface(i);
            if (in.getId()!=0) continue;
            int iso = -1;
            for (int e=0;e<in.getEndpointCount();e++) {
                UsbEndpoint ep = in.getEndpoint(e);
                if (ep.getAddress()==0x82) iso = ep.getMaxPacketSize();
            }
            if (iso==0) alt0=in;
            if (iso==1024) alt1=in;
        }
        if (alt0==null || alt1==null) {
            append("✗ Required video alt settings not found");
            return false;
        }
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

            List<String> found = correctedI2cScan();
            append("Corrected I2C devices: "+found);

            List<String> profiles = new ArrayList<>();
            if (found.contains("0x25")) profiles.add("SAA7113");
            profiles.add("SAA7113");
            profiles.add("GENERIC_ANALOG");
            profiles.add("BRIDGE_ONLY");

            LinkedHashSet<String> unique = new LinkedHashSet<>(profiles);
            boolean started = false;

            for (String p: unique) {
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

                if (!conn.setInterface(alt1)) {
                    append("✗ setInterface alt1 failed");
                    continue;
                }

                currentProfile = p;
                runOnUiThread(() -> profileText.setText("Profile: "+currentProfile+" / ISO alt1"));
                assembler.reset();

                // Turn bridge capture on only after profile and alt selection.
                if (!startBridgeCapture()) {
                    append("✗ bridge capture start failed");
                    conn.setInterface(alt0);
                    continue;
                }

                // Probe native ISO for 2 seconds before committing.
                int fd = conn.getFileDescriptor();
                int useful = 0;
                long deadline = System.currentTimeMillis()+2200;
                while (System.currentTimeMillis()<deadline && useful<4) {
                    byte[] block = nativeReadIso(fd, 0x82, 1024, 24, 500);
                    if (block!=null && block.length>100) {
                        useful++;
                        assembler.consumeContainer(block, false);
                    }
                }
                append("ISO probe useful blocks = "+useful+" errno="+nativeLastErrno());

                if (useful>0) {
                    started = true;
                    break;
                }

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

    private void beginLiveLoop() {
        if (!streaming.compareAndSet(false, true)) return;
        fpsStart = System.currentTimeMillis();
        framesShown = 0;

        streamThread = new Thread(() -> {
            int fd = conn.getFileDescriptor();
            while (streaming.get()) {
                byte[] block = nativeReadIso(fd, 0x82, 1024, 32, 650);
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

    private boolean applyProfile(String p) {
        // Common bridge format for external analog decoders:
        // YUV422 CbYCrY + interlaced CCIR656, matching Linux em28xx defaults.
        boolean ok = true;
        ok &= writeReg(0x10, 0x10);
        ok &= writeReg(0x11, 0x11);
        ok &= writeReg(0x27, 0x34); // 0x14 YUV422 + legacy 0x20 bit
        ok &= writeReg(0x20, 0x10);
        ok &= writeReg(0x21, 0x00);
        ok &= writeReg(0x22, 0x10);
        ok &= writeReg(0x25, 0x00);

        if ("SAA7113".equals(p)) {
            // Linux saa7113_init table, sent to 7-bit 0x25 (raw USB index 0x4A)
            int[][] t = {
                    {0x01,0x08},{0x02,0xC2},{0x03,0x30},{0x04,0x00},{0x05,0x00},
                    {0x06,0x89},{0x07,0x0D},{0x08,0x88},{0x09,0x01},{0x0A,0x80},
                    {0x0B,0x47},{0x0C,0x40},{0x0D,0x00},{0x0E,0x01},{0x0F,0x2A},
                    {0x10,0x08},{0x11,0x0C},{0x12,0x07},{0x13,0x00},{0x14,0x00},
                    {0x15,0x00},{0x16,0x00},{0x17,0x00}
            };
            int good=0;
            for (int[] rv: t) if (i2cWriteReg8(0x25, rv[0], rv[1])) good++;
            append("SAA7113 writes ACK = "+good+"/"+t.length);
            ok &= good > t.length/2;
        } else if ("GENERIC_ANALOG".equals(p)) {
            // Keep decoder state untouched, bridge only.
            append("Generic analog bridge profile applied.");
        } else {
            append("Bridge-only profile applied.");
        }

        return ok;
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
        conn=null; alt0=null; alt1=null;
    }

    @Override protected void onDestroy(){
        stopCamera(); closeDevice();
        try{unregisterReceiver(receiver);}catch(Exception ignored){}
        super.onDestroy();
    }

    private class FrameAssembler {
        private int width=720, height=576;
        private byte[] yuyv = new byte[width*height*2];
        private boolean haveGeometry=false;
        private boolean haveField=false, topField=true;
        private int fieldBytes=0;
        private byte[] fieldScratch = new byte[500000];

        synchronized void reset(){
            width=720; height=576;
            yuyv=new byte[width*height*2];
            haveGeometry=false; haveField=false; fieldBytes=0;
        }

        synchronized void consumeContainer(byte[] c, boolean render){
            int pos=0;
            while(pos+2<=c.length){
                int len=(c[pos]&0xff)|((c[pos+1]&0xff)<<8);
                pos+=2;
                if(len<=0 || pos+len>c.length) break;
                consumePacket(c,pos,len,render);
                pos+=len;
            }
        }

        private void consumePacket(byte[] p,int off,int len,boolean render){
            if(len<4) return;
            boolean cont = eq4(p,off,0x88,0x88,0x88,0x88);
            boolean start = (p[off]&0xff)==0x22 && (p[off+1]&0xff)==0x5A;
            if(!cont && !start) return;

            int dataOff=off+4, dataLen=len-4;
            if(start){
                boolean newTop = ((p[off+2]&1)==0);

                if(haveField){
                    if(!haveGeometry){
                        chooseGeometry(fieldBytes);
                    } else {
                        if(render) renderFrameIfUseful();
                    }
                }
                haveField=true;
                topField=newTop;
                fieldBytes=0;
            }

            if(!haveField || dataLen<=0) return;

            if(!haveGeometry){
                int n=Math.min(dataLen, fieldScratch.length-fieldBytes);
                if(n>0){ System.arraycopy(p,dataOff,fieldScratch,fieldBytes,n); fieldBytes+=n; }
                return;
            }

            copyInterlaced(p,dataOff,dataLen);
        }

        private void chooseGeometry(int measured){
            int[][] candidates={{720,576},{720,480},{640,480},{640,576}};
            int best=0, diff=Integer.MAX_VALUE;
            for(int i=0;i<candidates.length;i++){
                int expected=candidates[i][0]*candidates[i][1]; // bytes per YUYV field
                int d=Math.abs(expected-measured);
                if(d<diff){diff=d;best=i;}
            }
            width=candidates[best][0]; height=candidates[best][1];
            yuyv=new byte[width*height*2];
            haveGeometry=true;
            append("Frame geometry inferred: "+width+"x"+height+
                    " firstFieldBytes="+measured+" diff="+diff);
        }

        private void copyInterlaced(byte[] src,int off,int len){
            int bytesPerLine=width*2;
            int srcPos=0;
            while(srcPos<len){
                int logical=fieldBytes;
                int fieldLine=logical/bytesPerLine;
                int inLine=logical%bytesPerLine;
                int dstLine=fieldLine*2+(topField?0:1);
                if(dstLine>=height) break;
                int n=Math.min(len-srcPos, bytesPerLine-inLine);
                int dst=dstLine*bytesPerLine+inLine;
                if(dst+n<=yuyv.length) System.arraycopy(src,off+srcPos,yuyv,dst,n);
                srcPos+=n; fieldBytes+=n;
            }
        }

        private void renderFrameIfUseful(){
            if(fieldBytes < width*height/4) return;
            int[] argb=new int[width*height];
            int j=0;
            for(int i=0;i+3<yuyv.length && j+1<argb.length;i+=4){
                int y0=yuyv[i]&0xff, u=yuyv[i+1]&0xff, y1=yuyv[i+2]&0xff, v=yuyv[i+3]&0xff;
                argb[j++]=yuvToArgb(y0,u,v);
                argb[j++]=yuvToArgb(y1,u,v);
            }
            Bitmap b=Bitmap.createBitmap(argb,width,height,Bitmap.Config.ARGB_8888);
            onFrame(b);
        }

        private int yuvToArgb(int y,int u,int v){
            int c=y-16,d=u-128,e=v-128;
            int r=clamp((298*c+409*e+128)>>8);
            int g=clamp((298*c-100*d-208*e+128)>>8);
            int b=clamp((298*c+516*d+128)>>8);
            return 0xff000000|(r<<16)|(g<<8)|b;
        }
        private int clamp(int x){return x<0?0:(x>255?255:x);}
        private boolean eq4(byte[] a,int o,int b0,int b1,int b2,int b3){
            return (a[o]&255)==b0&&(a[o+1]&255)==b1&&(a[o+2]&255)==b2&&(a[o+3]&255)==b3;
        }
    }
}
