package com.drakosanctis.auriga;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * CameraConnectActivity
 *
 * Connects external cameras to the Auriga interface via:
 *  - Wi-Fi / IP Camera  (HTTP MJPEG stream, JPEG snapshot poll, WebSocket JPEG)
 *  - Bluetooth LE       (BLE GATT camera — ESP32-CAM, nRF52, custom modules)
 *  - USB Serial / MCU   (Any USB-to-UART adapter — PIC18F/32, AVR, STM32,
 *                        ESP32, RP2040, CH340/CP2102/FTDI/PL2303/CDC-ACM)
 *
 * Frame protocols supported:
 *   JPEG_SOF_EOF        — SOF 0xFF 0xD8 ... EOF 0xFF 0xD9 (auto-detect)
 *   LENGTH_PREFIX       — 4-byte little-endian uint32 length + JPEG payload
 *   RAW_RGB565          — width × height × 2 bytes, configurable resolution
 *   RAW_GRAYSCALE       — width × height bytes, configurable resolution
 */
public class CameraConnectActivity extends Activity {

    private static final String TAG = "AurigaCamConnect";

    // ── BLE camera service / characteristic UUIDs (DrakoCam BLE profile) ──
    // Must match the firmware on the ESP32 / nRF52 device.
    public static final String BLE_SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0";
    public static final String BLE_CHAR_UUID    = "12345678-1234-5678-1234-56789abcdef1";

    // ── Permission request codes ──
    private static final int REQ_BT_PERM  = 101;
    private static final int REQ_USB_PERM = 102;

    // ── UI references ──
    private Spinner  spnSource;
    private LinearLayout panelWifi, panelBt, panelSerial;
    private EditText etWifiUrl, etBtFilter, etRawWidth, etRawHeight;
    private Spinner  spnWifiFormat, spnSerialBaud, spnSerialProto;
    private Button   btnConnectWifi, btnDisconnectWifi;
    private Button   btnScanBt, btnDisconnectBt;
    private Button   btnConnectSerial, btnDisconnectSerial, btnSendCmd;
    private ImageView ivPreview;
    private TextView tvStatus, tvLog, tvBtList;

    // ── Thread / concurrency ──
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ExecutorService ioPool = Executors.newCachedThreadPool();

    // ── Wi-Fi state ──
    private volatile boolean wifiStreaming = false;
    private Thread wifiThread;

    // ── BT state ──
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothGatt btGatt;
    private final List<String> btFoundDevices = new ArrayList<>();
    private byte[] btFrameBuffer = new byte[0];
    private int    btExpectedLen = 0;
    private volatile boolean btScanning = false;

    // ── USB Serial state ──
    private UsbManager      usbManager;
    private UsbDevice       usbSerialDevice;
    private UsbDeviceConnection usbConn;
    private UsbEndpoint     usbEpIn;
    private volatile boolean serialRunning = false;
    private Thread serialThread;
    private byte[] serialBuffer = new byte[0];

    // ── Frame protocol enum ──
    private enum FrameProto { JPEG_SOF_EOF, LENGTH_PREFIX, RAW_RGB565, RAW_GRAYSCALE }

    // ──────────────────────────────────────────────────────────────[...]

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_connect);
        bindViews();
        setupSourceSpinner();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm != null) btAdapter = bm.getAdapter();
    }

    // ── VIEW BINDING ────────────────────────────────────────────────────────[...]

    private void bindViews() {
        spnSource       = findViewById(R.id.spn_source);
        panelWifi       = findViewById(R.id.panel_wifi);
        panelBt         = findViewById(R.id.panel_bt);
        panelSerial     = findViewById(R.id.panel_serial);
        etWifiUrl       = findViewById(R.id.et_wifi_url);
        etBtFilter      = findViewById(R.id.et_bt_filter);
        etRawWidth      = findViewById(R.id.et_raw_width);
        etRawHeight     = findViewById(R.id.et_raw_height);
        spnWifiFormat   = findViewById(R.id.spn_wifi_format);
        spnSerialBaud   = findViewById(R.id.spn_serial_baud);
        spnSerialProto  = findViewById(R.id.spn_serial_proto);
        btnConnectWifi      = findViewById(R.id.btn_connect_wifi);
        btnDisconnectWifi   = findViewById(R.id.btn_disconnect_wifi);
        btnScanBt           = findViewById(R.id.btn_scan_bt);
        btnDisconnectBt     = findViewById(R.id.btn_disconnect_bt);
        btnConnectSerial    = findViewById(R.id.btn_connect_serial);
        btnDisconnectSerial = findViewById(R.id.btn_disconnect_serial);
        btnSendCmd          = findViewById(R.id.btn_send_cmd);
        ivPreview   = findViewById(R.id.iv_preview);
        tvStatus    = findViewById(R.id.tv_status);
        tvLog       = findViewById(R.id.tv_log);
        tvBtList    = findViewById(R.id.tv_bt_list);

        btnConnectWifi.setOnClickListener(v -> startWifi());
        btnDisconnectWifi.setOnClickListener(v -> stopWifi());
        btnScanBt.setOnClickListener(v -> scanBluetooth());
        btnDisconnectBt.setOnClickListener(v -> disconnectBluetooth());
        btnConnectSerial.setOnClickListener(v -> connectSerial());
        btnDisconnectSerial.setOnClickListener(v -> disconnectSerial());
        btnSendCmd.setOnClickListener(v -> sendSerialCommand());

        spnSerialProto.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                boolean raw = pos >= 2;
                etRawWidth.setVisibility(raw ? View.VISIBLE : View.GONE);
                etRawHeight.setVisibility(raw ? View.VISIBLE : View.GONE);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private void setupSourceSpinner() {
        String[] sources = { "Wi-Fi / IP Camera", "Bluetooth LE Camera", "USB Serial / MCU" };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sources);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnSource.setAdapter(adapter);
        spnSource.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                panelWifi.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                panelBt.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                panelSerial.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
            }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    // ── WI-FI / IP CAMERA ────────────────────────────────────────────────────

    private void startWifi() {
        String url = etWifiUrl.getText().toString().trim();
        if (url.isEmpty()) { toast("Enter a stream URL first."); return; }
        int fmtPos = spnWifiFormat.getSelectedItemPosition();
        stopWifi();
        wifiStreaming = true;
        setStatus("Connecting to " + url);
        wifiThread = new Thread(() -> {
            try {
                if (fmtPos == 0) streamMJPEG(url);
                else if (fmtPos == 1) pollSnapshot(url);
                else if (fmtPos == 2) streamWebSocketJpeg(url);
            } catch (Exception e) {
                log("Wi-Fi error: " + e.getMessage());
                setStatus("Stream error — " + e.getMessage());
            }
        });
        wifiThread.setDaemon(true);
        wifiThread.start();
    }

    /** HTTP MJPEG — reads multipart/x-mixed-replace boundary stream */
    private void streamMJPEG(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.connect();
        setStatus("MJPEG stream active");
        InputStream is = conn.getInputStream();
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        boolean inJpeg = false;
        int prev = -1;
        int b;
        while (wifiStreaming && (b = is.read()) != -1) {
            if (!inJpeg) {
                if (prev == 0xFF && b == 0xD8) { inJpeg = true; frame.reset(); frame.write(0xFF); frame.write(0xD8); }
            } else {
                frame.write(b);
                if (prev == 0xFF && b == 0xD9) {
                    renderBitmap(frame.toByteArray());
                    inJpeg = false;
                    frame.reset();
                }
            }
            prev = b;
        }
        conn.disconnect();
    }

    /** JPEG snapshot polling — GETs a fresh JPEG every pollMs milliseconds */
    private void pollSnapshot(String urlStr) throws IOException, InterruptedException {
        int pollMs = 200;
        setStatus("Snapshot poll active");
        while (wifiStreaming) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr + "?t=" + System.currentTimeMillis()).openConnection();
                conn.setConnectTimeout(3000); conn.setReadTimeout(3000); conn.connect();
                byte[] data = readAll(conn.getInputStream());
                conn.disconnect();
                renderBitmap(data);
            } catch (Exception ignored) {}
            Thread.sleep(pollMs);
        }
    }

    /**
     * WebSocket JPEG stream — connects to ws:// endpoint.
     * Receives binary messages where each message is one complete JPEG frame.
     * Uses a minimal hand-rolled WebSocket upgrade (no third-party lib needed).
     */
    private void streamWebSocketJpeg(String wsUrl) {
        setStatus("WebSocket JPEG stream active");
        // Build a simple WebSocket via HttpURLConnection upgrade
        try {
            String host = wsUrl.replaceFirst("ws://", "").replaceFirst("/.*", "");
            String path = wsUrl.replaceFirst("ws://[^/]+", "");
            if (path.isEmpty()) path = "/";
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(host, 80), 5000);
            java.io.PrintWriter out = new java.io.PrintWriter(socket.getOutputStream(), true);
            String key = android.util.Base64.encodeToString(
                    new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16}, android.util.Base64.NO_WRAP);
            out.println("GET " + path + " HTTP/1.1\r\nHost: " + host +
                    "\r\nUpgrade: websocket\r\nConnection: Upgrade" +
                    "\r\nSec-WebSocket-Key: " + key +
                    "\r\nSec-WebSocket-Version: 13\r\n");
            InputStream is = socket.getInputStream();
            // skip HTTP headers
            StringBuilder headers = new StringBuilder();
            int c, prev = -1;
            while ((c = is.read()) != -1) {
                headers.append((char) c);
                if (prev == '\n' && c == '\n') break;
                prev = c;
            }
            log("WS handshake OK");
            // Read WebSocket frames
            while (wifiStreaming) {
                int h1 = is.read(); if (h1 < 0) break;
                int h2 = is.read(); if (h2 < 0) break;
                boolean fin = (h1 & 0x80) != 0;
                int opcode = h1 & 0x0F;
                if (opcode == 0x8) break; // close frame
                long payLen = h2 & 0x7F;
                if (payLen == 126) {
                    payLen = ((is.read() << 8) | is.read()) & 0xFFFFL;
                } else if (payLen == 127) {
                    payLen = 0;
                    for (int i = 0; i < 8; i++) payLen = (payLen << 8) | (is.read() & 0xFF);
                }
                byte[] payload = new byte[(int) payLen];
                int read = 0;
                while (read < payload.length) {
                    int r = is.read(payload, read, payload.length - read);
                    if (r < 0) break;
                    read += r;
                }
                if (opcode == 0x2 || opcode == 0x0) { // binary or continuation
                    renderBitmap(payload);
                }
            }
            socket.close();
        } catch (Exception e) {
            log("WS error: " + e.getMessage());
        }
    }

    private void stopWifi() {
        wifiStreaming = false;
        if (wifiThread != null) { wifiThread.interrupt(); wifiThread = null; }
        setStatus("Wi-Fi stream stopped");
    }

    // ── BLUETOOTH LE CAMERA ───────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private void scanBluetooth() {
        if (btAdapter == null || !btAdapter.isEnabled()) {
            toast("Bluetooth is off. Enable it and try again."); return;
        }
        if (!checkBtPermissions()) return;
        btFoundDevices.clear();
        updateBtList("Scanning…");
        bleScanner = btAdapter.getBluetoothLeScanner();
        btScanning = true;
        setStatus("BLE scan active…");

        bleScanner.startScan(new ScanCallback() {
            @SuppressLint("MissingPermission")
            @Override public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice dev = result.getDevice();
                String name = dev.getName() != null ? dev.getName() : "(unknown)";
                String filter = etBtFilter.getText().toString().trim().toLowerCase();
                if (!filter.isEmpty() && !name.toLowerCase().contains(filter)) return;
                String entry = name + "  " + dev.getAddress() + "  RSSI:" + result.getRssi();
                if (!btFoundDevices.contains(dev.getAddress())) {
                    btFoundDevices.add(dev.getAddress());
                    updateBtList(String.join("\n", btFoundDevices.stream()
                            .map(a -> a).toArray(String[]::new)));
                    // Auto-connect to first matching device
                    if (btGatt == null) connectBleDevice(dev);
                }
            }
            @Override public void onScanFailed(int errorCode) {
                log("BLE scan failed: " + errorCode);
                btScanning = false;
            }
        });
        // Stop scan after 10 seconds
        mainHandler.postDelayed(() -> {
            if (btScanning && bleScanner != null) {
                bleScanner.stopScan(new ScanCallback() {});
                btScanning = false;
                log("BLE scan stopped.");
            }
        }, 10000);
    }

    @SuppressLint("MissingPermission")
    private void connectBleDevice(BluetoothDevice device) {
        setStatus("Connecting to " + (device.getName() != null ? device.getName() : device.getAddress()));
        btGatt = device.connectGatt(this, false, new BluetoothGattCallback() {

            @SuppressLint("MissingPermission")
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("BLE connected. Discovering services…");
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    setStatus("BLE disconnected");
                    log("BLE disconnected.");
                    btGatt = null;
                }
            }

            @SuppressLint("MissingPermission")
            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                BluetoothGattService svc = gatt.getService(UUID.fromString(BLE_SERVICE_UUID));
                if (svc == null) {
                    log("DrakoCam BLE service not found on this device.");
                    setStatus("BLE: DrakoCam service not found");
                    return;
                }
                BluetoothGattCharacteristic ch = svc.getCharacteristic(UUID.fromString(BLE_CHAR_UUID));
                if (ch == null) { log("RX characteristic not found."); return; }
                gatt.setCharacteristicNotification(ch, true);
                // Enable CCCD descriptor for notifications
                java.util.UUID CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
                android.bluetooth.BluetoothGattDescriptor desc = ch.getDescriptor(CCCD);
                if (desc != null) {
                    desc.setValue(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(desc);
                }
                setStatus("BLE camera streaming");
                log("BLE: notifications enabled on RX characteristic.");
            }

            @Override public void onCharacteristicChanged(BluetoothGatt gatt,
                    BluetoothGattCharacteristic characteristic) {
                byte[] chunk = characteristic.getValue();
                if (chunk == null) return;

                // Protocol: first message may be 4-byte length header (little-endian uint32)
                if (chunk.length == 4 && btExpectedLen == 0) {
                    btExpectedLen = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    btFrameBuffer = new byte[0];
                    return;
                }

                // Append chunk to frame buffer
                btFrameBuffer = appendBytes(btFrameBuffer, chunk);

                if (btExpectedLen > 0 && btFrameBuffer.length >= btExpectedLen) {
                    // Complete length-prefixed frame
                    renderBitmap(Arrays.copyOf(btFrameBuffer, btExpectedLen));
                    btFrameBuffer = new byte[0];
                    btExpectedLen = 0;
                } else if (btExpectedLen == 0) {
                    // Fall back to JPEG SOF/EOF detection
                    int sof = findBytes(btFrameBuffer, new byte[]{(byte)0xFF, (byte)0xD8});
                    int eof = findBytes(btFrameBuffer, new byte[]{(byte)0xFF, (byte)0xD9});
                    if (sof != -1 && eof > sof) {
                        renderBitmap(Arrays.copyOfRange(btFrameBuffer, sof, eof + 2));
                        btFrameBuffer = new byte[0];
                    }
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void disconnectBluetooth() {
        if (btGatt != null) { btGatt.disconnect(); btGatt.close(); btGatt = null; }
        if (bleScanner != null && btScanning) { bleScanner.stopScan(new ScanCallback(){}); btScanning = false; }
        setStatus("Bluetooth disconnected");
        log("BLE disconnected manually.");
    }

    private boolean checkBtPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            String[] perms = {
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            };
            List<String> missing = new ArrayList<>();
            for (String p : perms) if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
            if (!missing.isEmpty()) {
                ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_BT_PERM);
                return false;
            }
        }
        return true;
    }

    // ── USB SERIAL / MCU ──────────────────────────────────────────────────────

    /**
     * Connects to the first USB-serial device found via Android USB Host API.
     * Supports any chip recognised as a USB CDC-ACM or CDC device by Android
     * (Arduino, STM32 CDC, ESP32, RP2040 CDC) as well as any device that
     * Android maps as a raw USB bulk endpoint (CH340, CP2102, FTDI FT232,
     * PL2303 — these register as standard bulk-transfer devices).
     *
     * For PIC18F4550 with USB CDC firmware (MPLAB XC8 + MCC USB CDC):
     *   - The PIC enumerates as CDC-ACM on Android just like any COM port.
     *   - Connect USB OTG cable, open this screen, tap CONNECT SERIAL.
     *   - Android will prompt for USB permission on first connect.
     */
    private void connectSerial() {
        Map<String, UsbDevice> devMap = usbManager.getDeviceList();
        if (devMap.isEmpty()) {
            toast("No USB devices detected.\nPlease attach your microcontroller via OTG cable.");
            log("No USB devices found.");
            return;
        }
        // Pick the first device (user can unplug others first)
        usbSerialDevice = devMap.values().iterator().next();
        log("USB device: " + usbSerialDevice.getDeviceName() +
                " VID=0x" + Integer.toHexString(usbSerialDevice.getVendorId()) +
                " PID=0x" + Integer.toHexString(usbSerialDevice.getProductId()));

        if (!usbManager.hasPermission(usbSerialDevice)) {
            // Request permission — Android will call back via ACTION_USB_PERMISSION broadcast
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    this, 0,
                    new android.content.Intent("com.drakosanctis.auriga.USB_PERMISSION"),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(usbSerialDevice, pi);
            toast("USB permission requested — tap ALLOW then connect again.");
            return;
        }
        openUsbSerial();
    }

    @SuppressLint("NewApi")
    private void openUsbSerial() {
        if (usbSerialDevice == null) return;
        usbConn = usbManager.openDevice(usbSerialDevice);
        if (usbConn == null) { toast("Failed to open USB device."); return; }

        // Claim the first interface with a bulk-in endpoint
        for (int i = 0; i < usbSerialDevice.getInterfaceCount(); i++) {
            UsbInterface iface = usbSerialDevice.getInterface(i);
            usbConn.claimInterface(iface, true);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                    usbEpIn = ep;
                    break;
                }
            }
            if (usbEpIn != null) break;
        }

        if (usbEpIn == null) {
            toast("No bulk-IN endpoint found on this USB device.\nOnly CDC-ACM / bulk-transfer devices are supported.");
            usbConn.close(); return;
        }

        setStatus("USB Serial connected");
        log("USB Serial open — reading frames…");
        serialRunning = true;
        btnConnectSerial.setVisibility(View.GONE);
        btnDisconnectSerial.setVisibility(View.VISIBLE);
        btnSendCmd.setVisibility(View.VISIBLE);

        FrameProto proto = getSelectedProto();
        serialThread = new Thread(() -> readSerialFrames(proto));
        serialThread.setDaemon(true);
        serialThread.start();
    }

    private void readSerialFrames(FrameProto proto) {
        int mtu = usbEpIn.getMaxPacketSize();
        byte[] buf = new byte[Math.max(mtu, 512)];
        int frameCount = 0;

        while (serialRunning) {
            int transferred = usbConn.bulkTransfer(usbEpIn, buf, buf.length, 100);
            if (transferred <= 0) continue;
            serialBuffer = appendBytes(serialBuffer, Arrays.copyOf(buf, transferred));

            switch (proto) {

                case JPEG_SOF_EOF: {
                    while (true) {
                        int sof = findBytes(serialBuffer, new byte[]{(byte)0xFF, (byte)0xD8});
                        int eof = findLastBytes(serialBuffer, new byte[]{(byte)0xFF, (byte)0xD9});
                        if (sof == -1 || eof <= sof) break;
                        byte[] jpeg = Arrays.copyOfRange(serialBuffer, sof, eof + 2);
                        serialBuffer = Arrays.copyOfRange(serialBuffer, eof + 2, serialBuffer.length);
                        renderBitmap(jpeg);
                        frameCount++;
                    }
                    break;
                }

                case LENGTH_PREFIX: {
                    while (serialBuffer.length >= 4) {
                        int len = ByteBuffer.wrap(serialBuffer).order(ByteOrder.LITTLE_ENDIAN).getInt();
                        if (len < 1 || len > 1_000_000) { serialBuffer = Arrays.copyOfRange(serialBuffer, 1, serialBuffer.length); continue; }
                        if (serialBuffer.length < 4 + len) break;
                        byte[] jpeg = Arrays.copyOfRange(serialBuffer, 4, 4 + len);
                        serialBuffer = Arrays.copyOfRange(serialBuffer, 4 + len, serialBuffer.length);
                        renderBitmap(jpeg);
                        frameCount++;
                    }
                    break;
                }

                case RAW_RGB565: {
                    int w = getRawWidth(), h = getRawHeight();
                    int needed = w * h * 2;
                    if (serialBuffer.length >= needed) {
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
                        int[] pixels = new int[w * h];
                        for (int i = 0; i < w * h; i++) {
                            int px = ((serialBuffer[i*2] & 0xFF) << 8) | (serialBuffer[i*2+1] & 0xFF);
                            int r = (px >> 11) << 3;
                            int g = ((px >> 5) & 0x3F) << 2;
                            int b = (px & 0x1F) << 3;
                            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                        }
                        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
                        serialBuffer = Arrays.copyOfRange(serialBuffer, needed, serialBuffer.length);
                        renderBitmapDirect(bmp);
                        frameCount++;
                    }
                    break;
                }

                case RAW_GRAYSCALE: {
                    int w = getRawWidth(), h = getRawHeight();
                    int needed = w * h;
                    if (serialBuffer.length >= needed) {
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        int[] pixels = new int[w * h];
                        for (int i = 0; i < w * h; i++) {
                            int v = serialBuffer[i] & 0xFF;
                            pixels[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
                        }
                        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
                        serialBuffer = Arrays.copyOfRange(serialBuffer, needed, serialBuffer.length);
                        renderBitmapDirect(bmp);
                        frameCount++;
                    }
                    break;
                }
            }
        }
        log("Serial stream ended. " + frameCount + " frames received.");
    }

    private void disconnectSerial() {
        serialRunning = false;
        if (serialThread != null) { serialThread.interrupt(); serialThread = null; }
        if (usbConn != null) { usbConn.close(); usbConn = null; }
        usbSerialDevice = null; usbEpIn = null;
        serialBuffer = new byte[0];
        setStatus("USB Serial disconnected");
        log("Serial disconnected.");
        mainHandler.post(() -> {
            btnConnectSerial.setVisibility(View.VISIBLE);
            btnDisconnectSerial.setVisibility(View.GONE);
            btnSendCmd.setVisibility(View.GONE);
        });
    }

    private void sendSerialCommand() {
        if (usbConn == null) { toast("Not connected."); return; }
        // Find a bulk-OUT endpoint to send the command
        for (int i = 0; i < usbSerialDevice.getInterfaceCount(); i++) {
            UsbInterface iface = usbSerialDevice.getInterface(i);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_OUT) {
                    String cmd = "STATUS\n";
                    byte[] bytes = cmd.getBytes();
                    int sent = usbConn.bulkTransfer(ep, bytes, bytes.length, 1000);
                    log("TX: " + cmd.trim() + " (" + sent + " bytes)");
                    return;
                }
            }
        }
        toast("No bulk-OUT endpoint found.");
    }

    // ── RENDER HELPERS ───────────────────────────────────────────────────────[...]

    private void renderBitmap(byte[] jpegBytes) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        if (bmp != null) renderBitmapDirect(bmp);
    }

    private void renderBitmapDirect(Bitmap bmp) {
        mainHandler.post(() -> ivPreview.setImageBitmap(bmp));
    }

    // ── BYTE UTILITIES ───────────────────────────────────────────────────────[...]

    private static byte[] appendBytes(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static int findBytes(byte[] buf, byte[] seq) {
        outer: for (int i = 0; i <= buf.length - seq.length; i++) {
            for (int j = 0; j < seq.length; j++) if (buf[i+j] != seq[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static int findLastBytes(byte[] buf, byte[] seq) {
        int last = -1;
        outer: for (int i = buf.length - seq.length; i >= 0; i--) {
            for (int j = 0; j < seq.length; j++) if (buf[i+j] != seq[j]) continue outer;
            last = i; break;
        }
        return last;
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ── MISC ──────────────────────────────────────────────────────────[...]

    private FrameProto getSelectedProto() {
        int pos = spnSerialProto.getSelectedItemPosition();
        switch (pos) {
            case 1: return FrameProto.LENGTH_PREFIX;
            case 2: return FrameProto.RAW_RGB565;
            case 3: return FrameProto.RAW_GRAYSCALE;
            default: return FrameProto.JPEG_SOF_EOF;
        }
    }

    private int getRawWidth() {
        try { return Integer.parseInt(etRawWidth.getText().toString()); } catch (Exception e) { return 320; }
    }
    private int getRawHeight() {
        try { return Integer.parseInt(etRawHeight.getText().toString()); } catch (Exception e) { return 240; }
    }

    private void setStatus(String msg) {
        mainHandler.post(() -> tvStatus.setText(msg));
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        mainHandler.post(() -> {
            String existing = tvLog.getText().toString();
            int lines = existing.split("\n").length;
            if (lines > 40) {
                int nl = existing.indexOf('\n');
                existing = existing.substring(nl + 1);
            }
            tvLog.setText(existing + (existing.isEmpty() ? "" : "\n") + msg);
        });
    }

    private void toast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    private void updateBtList(String text) {
        mainHandler.post(() -> tvBtList.setText(text));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopWifi();
        disconnectBluetooth();
        disconnectSerial();
        ioPool.shutdownNow();
    }
}
