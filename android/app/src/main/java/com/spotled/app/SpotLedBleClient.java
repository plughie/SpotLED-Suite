package com.spotled.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Minimal BLE discovery, connection, and acknowledged SetData transport. */
final class SpotLedBleClient {
    interface Listener {
        void onStatus(String message);
        void onDevices(List<FoundDevice> devices);
    }

    private static final String DEVICE_NAME_PREFIX = "SpotLED_";
    private static final long SCAN_POLL_MS = 3000;
    private static final long MAX_SCAN_MS = 15000;
    private static final long MATCH_SETTLE_MS = 1200;
    private static final int MAX_CONNECTION_ATTEMPTS = 2;
    private static final UUID CMD_UUID = UUID.fromString("0000ff21-0000-1000-8000-00805f9b34fb");
    private static final UUID DATA_UUID = UUID.fromString("0000ff22-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BlockingQueue<byte[]> responses = new ArrayBlockingQueue<>(32);
    private final BlockingQueue<Boolean> connectionEvents = new ArrayBlockingQueue<>(4);
    private final AtomicInteger nextSerial = new AtomicInteger(1);
    private final java.util.concurrent.atomic.AtomicBoolean transferInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic commandCharacteristic;
    private volatile BluetoothGattCharacteristic dataCharacteristic;
    private volatile int mtu = 23;
    private volatile boolean ready;
    private volatile String connectedName = "SpotLED";
    private volatile FoundDevice selectedDevice;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private final Map<String, FoundDevice> discoveredDevices = new LinkedHashMap<>();
    private long scanStartedAt;
    private final Runnable matchSettleTimeout = this::finishDiscovery;
    private final Runnable scanTimeout;

    static final class FoundDevice {
        final BluetoothDevice device;
        final String name;
        final String address;

        FoundDevice(BluetoothDevice device, String name, String address) {
            this.device = device;
            this.name = name;
            this.address = address;
        }

        String label() {
            String suffix = address.length() >= 5 ? address.substring(address.length() - 5) : address;
            return name + "  (…" + suffix + ")";
        }
    }

    SpotLedBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.scanTimeout = this::pollScan;
    }

    private void pollScan() {
        boolean foundAny;
        synchronized (discoveredDevices) {
            foundAny = !discoveredDevices.isEmpty();
        }
        if (foundAny || SystemClock.elapsedRealtime() - scanStartedAt >= MAX_SCAN_MS) {
            finishDiscovery();
        } else {
            status("Still searching for SpotLED_* displays…");
            main.postDelayed(scanTimeout, SCAN_POLL_MS);
        }
    }

    private void finishDiscovery() {
        stopScan();
        List<FoundDevice> found;
        synchronized (discoveredDevices) {
            found = new ArrayList<>(discoveredDevices.values());
        }
        if (found.isEmpty()) {
            status("Display not found. Check power, range, and advertised name.");
        } else {
            status("Found " + found.size() + " matching SpotLED display(s); choose one.");
            listener.onDevices(found);
        }
    }

    boolean isReady() { return ready; }
    boolean hasSelectedDevice() { return selectedDevice != null; }

    static boolean matchesSpotLedName(String name) {
        return name != null && name.startsWith(DEVICE_NAME_PREFIX);
    }

    static boolean shouldRetryConnection(int attemptsMade) {
        return attemptsMade < MAX_CONNECTION_ATTEMPTS;
    }

    void scanForDevices() {
        if (transferInProgress.get()) {
            status("A transfer is in progress; wait for it to finish before scanning.");
            return;
        }
        disconnectTransport();
        selectedDevice = null;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            status("Turn on Bluetooth, then try again.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            status("Bluetooth LE scanning is unavailable.");
            return;
        }
        synchronized (discoveredDevices) { discoveredDevices.clear(); }
        scanStartedAt = SystemClock.elapsedRealtime();
        status("Looking for SpotLED_* displays…");
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
                BluetoothDevice device = result.getDevice();
                if (name == null) {
                    try { name = device.getName(); } catch (SecurityException ignored) { }
                }
                if (matchesSpotLedName(name)) {
                    try {
                        boolean firstMatch;
                        synchronized (discoveredDevices) {
                            firstMatch = discoveredDevices.isEmpty();
                            discoveredDevices.put(device.getAddress(),
                                    new FoundDevice(device, name, device.getAddress()));
                            status("Found " + discoveredDevices.size() + " matching display(s)…");
                        }
                        if (firstMatch) {
                            main.removeCallbacks(scanTimeout);
                            main.postDelayed(matchSettleTimeout, MATCH_SETTLE_MS);
                        }
                    } catch (SecurityException exception) {
                        status("Bluetooth connection permission is missing.");
                    }
                }
            }

            @Override public void onScanFailed(int errorCode) {
                stopScan();
                status("Bluetooth scan failed (" + errorCode + "). Check permissions and retry.");
            }
        };
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            main.removeCallbacks(scanTimeout);
            main.removeCallbacks(matchSettleTimeout);
            main.postDelayed(scanTimeout, SCAN_POLL_MS);
        } catch (SecurityException exception) {
            status("Bluetooth scan permission is missing.");
        }
    }

    void selectDevice(FoundDevice foundDevice) {
        if (foundDevice == null) return;
        disconnectTransport();
        selectedDevice = foundDevice;
        connectedName = foundDevice.name;
        status("Selected " + connectedName + ". It will connect only during a send.");
    }

    private void connectionFailed(String reason, BluetoothGatt failedGatt) {
        ready = false;
        commandCharacteristic = null;
        dataCharacteristic = null;
        mtu = 23;
        if (failedGatt != null) {
            try { failedGatt.close(); } catch (Exception ignored) { }
            if (gatt == failedGatt) gatt = null;
        }
        status(reason);
        connectionEvents.offer(false);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt source, int status, int newState) {
            if (source != gatt) {
                source.close();
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionFailed("BLE connection failed (" + status + ")", source);
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected; preparing transfer…");
                if (!source.requestMtu(185)) source.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionFailed("Disconnected from " + connectedName, source);
            }
        }

        @Override public void onMtuChanged(BluetoothGatt source, int value, int status) {
            if (source != gatt) { source.close(); return; }
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = value;
            if (commandCharacteristic == null) source.discoverServices();
        }

        @Override public void onServicesDiscovered(BluetoothGatt source, int status) {
            if (source != gatt) { source.close(); return; }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                connectionFailed("Could not inspect display services (" + status + ")", source);
                return;
            }
            for (android.bluetooth.BluetoothGattService service : source.getServices()) {
                if (commandCharacteristic == null) commandCharacteristic = service.getCharacteristic(CMD_UUID);
                if (dataCharacteristic == null) dataCharacteristic = service.getCharacteristic(DATA_UUID);
            }
            if (commandCharacteristic == null || dataCharacteristic == null) {
                connectionFailed("Display is missing the expected SPOTLED BLE characteristics", source);
                return;
            }
            try {
                source.setCharacteristicNotification(commandCharacteristic, true);
                BluetoothGattDescriptor descriptor = commandCharacteristic.getDescriptor(CCCD_UUID);
                if (descriptor == null) {
                    connectionFailed("Display has no notification descriptor", source);
                    return;
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    source.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    source.writeDescriptor(descriptor);
                }
            } catch (SecurityException exception) {
                connectionFailed("Bluetooth connection permission is missing", source);
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt source, BluetoothGattDescriptor descriptor, int status) {
            if (source != gatt) { source.close(); return; }
            if (CCCD_UUID.equals(descriptor.getUuid()) && status == BluetoothGatt.GATT_SUCCESS) {
                ready = true;
                status("Connected to " + connectedName + "; sending…");
                connectionEvents.offer(true);
            } else if (CCCD_UUID.equals(descriptor.getUuid())) {
                connectionFailed("Could not enable display acknowledgments (" + status + ")", source);
            }
        }

        @Override public void onCharacteristicChanged(BluetoothGatt source,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            if (source == gatt && CMD_UUID.equals(characteristic.getUuid())) responses.offer(value.clone());
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt source,
                BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (source == gatt && value != null && CMD_UUID.equals(characteristic.getUuid())) responses.offer(value.clone());
        }
    };

    void sendRgbFrame(byte[] rgb, int width, int height) {
        sendRgbFrame(rgb, width, height, 0);
    }

    void sendRgbFrame(byte[] rgb, int width, int height, int effectMode) {
        sendRgbAnimation(new byte[][] {rgb}, width, height, 500, effectMode, false);
    }

    void sendRgbAnimation(byte[][] frames, int width, int height, int intervalMs) {
        sendRgbAnimation(frames, width, height, intervalMs, 0, true);
    }

    private void sendRgbAnimation(byte[][] frames, int width, int height, int intervalMs,
                                  int effectMode, boolean animatedImage) {
        FoundDevice target = selectedDevice;
        if (target == null) {
            status("Find and select a SpotLED display before sending.");
            return;
        }
        if (!transferInProgress.compareAndSet(false, true)) {
            status("A transfer is already in progress.");
            return;
        }
        new Thread(() -> {
            boolean transferAcknowledged = false;
            try {
                byte[] payload = SpotLedProtocol.buildRgbAnimation(
                        frames, width, height, intervalMs, effectMode);
                if (payload.length > 7280)
                    throw new IllegalArgumentException("Image animation exceeds the display's 7,280-byte receive limit.");
                connectForTransfer(target);
                int serial = nextSerial.getAndUpdate(value -> (value + 1) & 0xffff);
                responses.clear();
                writeCommand(SpotLedProtocol.buildStart(serial, SpotLedProtocol.SET_DATA_OBJECT, payload.length));
                waitForAck(2, serial, 8);
                int chunkSize = Math.max(20, Math.min(182, mtu - 3));
                for (int offset = 0; offset < payload.length; offset += chunkSize) {
                    int count = Math.min(chunkSize, payload.length - offset);
                    byte[] chunk = new byte[count];
                    System.arraycopy(payload, offset, chunk, 0, count);
                    writeNoResponse(dataCharacteristic, chunk);
                    Thread.sleep(5);
                }
                writeCommand(SpotLedProtocol.buildEnd(serial, SpotLedProtocol.SET_DATA_OBJECT, payload.length));
                waitForAck(4, serial, 12);
                transferAcknowledged = true;
            } catch (Exception exception) {
                status("Send failed: " + (exception.getMessage() == null ? exception : exception.getMessage()));
            } finally {
                disconnectTransport();
                transferInProgress.set(false);
                if (transferAcknowledged) {
                    status(animatedImage
                            ? "GIF transfer acknowledged; disconnected. Check the display for animation."
                            : "Transfer acknowledged; disconnected. Check the display to confirm rendering.");
                }
            }
        }, "spotled-transfer").start();
    }

    private void connectForTransfer(FoundDevice target) throws Exception {
        for (int attempt = 1; attempt <= MAX_CONNECTION_ATTEMPTS; attempt++) {
            connectionEvents.clear();
            status(attempt == 1 ? "Connecting to " + target.name + " for transfer…"
                    : "Retrying connection to " + target.name + "…");
            BluetoothGatt newGatt;
            try {
                newGatt = target.device.connectGatt(context, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException exception) {
                throw new IllegalStateException("Bluetooth connection permission is missing.", exception);
            }
            if (newGatt == null) {
                disconnectTransport();
            } else {
                gatt = newGatt;
                Boolean connected = connectionEvents.poll(15, TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(connected) && ready) return;
                disconnectTransport();
            }
            if (attempt < MAX_CONNECTION_ATTEMPTS) Thread.sleep(700);
        }
        throw new IllegalStateException(
                "Could not connect for the transfer after two attempts. Check that another phone is not connected.");
    }

    private void waitForAck(int expectedId, int serial, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            byte[] response = responses.poll(250, TimeUnit.MILLISECONDS);
            if (response == null || response.length < 5) continue;
            if ((response[1] & 0xff) == expectedId
                    && (((response[2] & 0xff) << 8) | (response[3] & 0xff)) == serial) {
                if (response[4] != 0) throw new IllegalStateException("Device rejected transfer (status " + (response[4] & 0xff) + ")");
                return;
            }
        }
        throw new IllegalStateException(expectedId == 2
                ? "No start acknowledgment; image data was not sent."
                : "No final acknowledgment; the image may have been received. It was not retried.");
    }

    private void writeCommand(byte[] bytes) throws Exception { writeNoResponse(commandCharacteristic, bytes); }

    private void writeNoResponse(BluetoothGattCharacteristic characteristic, byte[] bytes) throws Exception {
        if (gatt == null || characteristic == null) throw new IllegalStateException("BLE connection is unavailable");
        for (int attempt = 0; attempt < 12; attempt++) {
            int result;
            if (Build.VERSION.SDK_INT >= 33) {
                result = gatt.writeCharacteristic(characteristic, bytes,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                characteristic.setValue(bytes);
                result = gatt.writeCharacteristic(characteristic)
                        ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_FAILURE;
            }
            if (result == BluetoothGatt.GATT_SUCCESS) return;
            if (Build.VERSION.SDK_INT >= 33
                    && result == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY) {
                Thread.sleep(15);
                continue; // Android explicitly rejected this attempt before enqueueing it.
            }
            throw new IllegalStateException("BLE write failed (" + result + ")");
        }
        throw new IllegalStateException("BLE remained busy after 12 rejected write attempts.");
    }

    void close() {
        stopScan();
        selectedDevice = null;
        disconnectTransport();
    }

    private void disconnectTransport() {
        ready = false;
        commandCharacteristic = null;
        dataCharacteristic = null;
        mtu = 23;
        connectionEvents.clear();
        responses.clear();
        BluetoothGatt current = gatt;
        gatt = null;
        if (current != null) {
            try { current.disconnect(); } catch (SecurityException ignored) { }
            current.close();
        }
    }

    private void stopScan() {
        main.removeCallbacks(scanTimeout);
        main.removeCallbacks(matchSettleTimeout);
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (SecurityException ignored) { }
        }
        scanCallback = null;
    }

    private void status(String message) { main.post(() -> listener.onStatus(message)); }
}
