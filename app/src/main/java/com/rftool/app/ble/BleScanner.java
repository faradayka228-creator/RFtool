package com.rftool.app.ble;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/ble/BleScanner.java
 *
 * Пассивный непрерывный BLE-сканер. Обнаруживает ближайшие активные периферийные
 * устройства, разбирает их advertising-данные (имя, список сервисов, manufacturer data)
 * и оценивает примерную дистанцию по стандартной log-distance path-loss формуле:
 *
 *      distance = 10 ^ ((txPower - RSSI) / (10 * n))
 *
 * где txPower — опорная мощность на 1 метре (берём из AD-структуры, либо дефолт -59 dBm),
 * n — коэффициент затухания среды (обычно 2.0 для открытого пространства, 2.5-4.0 в помещении).
 *
 * Это ЧИСТО ПРИЁМНЫЙ сканер: никаких advertising-пакетов на другие устройства не отправляется.
 */

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BleScanner {

    public static class BleDevice {
        public final String address;
        public final String name;
        public final int rssi;
        public final double estimatedDistanceM;
        public final List<ParcelUuid> serviceUuids;
        public final long lastSeenMs;
        public final String vendor;
        public final String deviceTypeHint;

        public BleDevice(String address, String name, int rssi, double estimatedDistanceM,
                          List<ParcelUuid> serviceUuids, long lastSeenMs,
                          String vendor, String deviceTypeHint) {
            this.address = address;
            this.name = name;
            this.rssi = rssi;
            this.estimatedDistanceM = estimatedDistanceM;
            this.serviceUuids = serviceUuids;
            this.lastSeenMs = lastSeenMs;
            this.vendor = vendor;
            this.deviceTypeHint = deviceTypeHint;
        }
    }

    public interface DiscoveryListener {
        void onDeviceUpdated(BleDevice device);
        void onLog(String message);
        void onScanFailed(int errorCode);
    }

    private static final int DEFAULT_TX_POWER = -59; // типичное значение для BLE 4.0 на 1м
    private static final double PATH_LOSS_EXPONENT = 2.2; // смешанная среда (помещение/открытое пр-во)

    private final BluetoothAdapter adapter;
    private BluetoothLeScanner leScanner;
    private final Map<String, BleDevice> deviceMap = new ConcurrentHashMap<>();
    private volatile boolean isScanning = false;
    private DiscoveryListener listener;

    public BleScanner(Context context) {
        BluetoothAdapter.getDefaultAdapter();
        android.bluetooth.BluetoothManager btManager =
                (android.bluetooth.BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = btManager != null ? btManager.getAdapter() : null;
    }

    public boolean isBluetoothAvailable() {
        return adapter != null && adapter.isEnabled();
    }

    public void startScanning(DiscoveryListener listener) {
        this.listener = listener;
        if (!isBluetoothAvailable()) {
            listener.onLog("Bluetooth выключен или недоступен на устройстве");
            return;
        }
        leScanner = adapter.getBluetoothLeScanner();
        if (leScanner == null) {
            listener.onLog("BluetoothLeScanner недоступен");
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build();

        try {
            leScanner.startScan(null, settings, scanCallback);
            isScanning = true;
            listener.onLog("BLE-сканирование запущено (пассивный режим)");
        } catch (SecurityException e) {
            listener.onLog("Нет разрешения BLUETOOTH_SCAN: " + e.getMessage());
        }
    }

    public void stopScanning() {
        if (leScanner != null && isScanning) {
            try {
                leScanner.stopScan(scanCallback);
            } catch (SecurityException ignored) { }
        }
        isScanning = false;
    }

    public java.util.Collection<BleDevice> getSnapshot() {
        return deviceMap.values();
    }

    public void clear() {
        deviceMap.clear();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                processResult(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (listener != null) listener.onScanFailed(errorCode);
        }
    };

    private void processResult(ScanResult result) {
        String address = result.getDevice().getAddress();
        int rssi = result.getRssi();

        int txPower = DEFAULT_TX_POWER;
        if (result.getScanRecord() != null && result.getScanRecord().getTxPowerLevel() != Integer.MIN_VALUE) {
            txPower = result.getScanRecord().getTxPowerLevel();
        }

        double distance = estimateDistance(rssi, txPower);

        String name = "unknown";
        List<ParcelUuid> uuids = null;
        if (result.getScanRecord() != null) {
            if (result.getScanRecord().getDeviceName() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            uuids = result.getScanRecord().getServiceUuids();
        }

        BleManufacturerDecoder.DecodedInfo decoded = BleManufacturerDecoder.decode(result.getScanRecord());

        BleDevice device = new BleDevice(address, name, rssi, distance, uuids, System.currentTimeMillis(),
                decoded.vendor, decoded.deviceTypeHint);
        deviceMap.put(address, device);

        if (listener != null) {
            listener.onDeviceUpdated(device);
        }
    }

    /** Формула логарифмического затухания (log-distance path loss model). */
    private double estimateDistance(int rssi, int txPower) {
        if (rssi == 0) return -1.0;
        double ratio = (txPower - rssi) / (10.0 * PATH_LOSS_EXPONENT);
        return Math.pow(10.0, ratio);
    }
}
