package com.rftool.app.wifi;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/wifi/WifiWardrivingService.java
 *
 * Фоновый сервис, выполняющий периодическое Wi-Fi сканирование (WifiManager.startScan())
 * синхронно с обновлениями геопозиции (LocationManager). Каждая точка телеметрии
 * агрегирует SSID/BSSID/RSSI/Capabilities + широту/долготу/точность в момент скана.
 *
 * Данные накапливаются в памяти и доступны через getSnapshot()/экспорт в CSV.
 * Это диагностический инструмент для аудита покрытия СВОЕЙ сети/объекта.
 */

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class WifiWardrivingService extends Service {

    private static final String CHANNEL_ID = "rftool_wardriving";
    private static final int NOTIF_ID = 1001;
    private static final long SCAN_INTERVAL_MS = 8000L; // интервал между циклами скана

    public static class WifiTelemetryPoint {
        public final String ssid;
        public final String bssid;
        public final int rssi;
        public final String capabilities;
        public final int frequencyMhz;
        public final double latitude;
        public final double longitude;
        public final float locationAccuracyM;
        public final long timestampMs;

        public WifiTelemetryPoint(String ssid, String bssid, int rssi, String capabilities,
                                   int frequencyMhz, double latitude, double longitude,
                                   float locationAccuracyM, long timestampMs) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.rssi = rssi;
            this.capabilities = capabilities;
            this.frequencyMhz = frequencyMhz;
            this.latitude = latitude;
            this.longitude = longitude;
            this.locationAccuracyM = locationAccuracyM;
            this.timestampMs = timestampMs;
        }

        public String toCsvRow() {
            return ssid + "," + bssid + "," + rssi + "," + frequencyMhz + "," +
                    "\"" + capabilities + "\"" + "," + latitude + "," + longitude + "," +
                    locationAccuracyM + "," + timestampMs;
        }
    }

    public interface TelemetryListener {
        void onNewTelemetry(WifiTelemetryPoint point);
        void onLog(String message);
    }

    private final IBinder binder = new LocalBinder();
    private WifiManager wifiManager;
    private LocationManager locationManager;
    private volatile Location lastKnownLocation;
    private final List<TelemetryListener> listeners = new CopyOnWriteArrayList<>();
    private final List<WifiTelemetryPoint> collected = new CopyOnWriteArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanning = false;

    private final BroadcastReceiver scanResultsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handleScanResults();
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastKnownLocation = location;
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled(String provider) { }

        @Override
        public void onProviderDisabled(String provider) { }
    };

    public class LocalBinder extends android.os.Binder {
        public WifiWardrivingService getService() {
            return WifiWardrivingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        createNotificationChannel();
        registerReceiver(scanResultsReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("Wardriving активен"));
        startLocationUpdates();
        startScanLoop();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        scanning = false;
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(scanResultsReceiver);
        } catch (IllegalArgumentException ignored) { }
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            locationManager.removeUpdates(locationListener);
        }
    }

    public void addListener(TelemetryListener listener) {
        listeners.add(listener);
    }

    public void removeListener(TelemetryListener listener) {
        listeners.remove(listener);
    }

    public List<WifiTelemetryPoint> getSnapshot() {
        return new ArrayList<>(collected);
    }

    public String exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("ssid,bssid,rssi,frequency_mhz,capabilities,lat,lon,accuracy_m,timestamp_ms\n");
        for (WifiTelemetryPoint p : collected) {
            sb.append(p.toCsvRow()).append("\n");
        }
        return sb.toString();
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            notifyLog("Нет разрешения на геолокацию — координаты будут пустыми");
            return;
        }
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 3000L, 1f, locationListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            notifyLog("SecurityException при запросе геолокации: " + e.getMessage());
        }
    }

    private void startScanLoop() {
        scanning = true;
        handler.post(scanRunnable);
    }

    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            if (!scanning) return;
            triggerScan();
            handler.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private void triggerScan() {
        if (wifiManager == null) return;
        if (!wifiManager.isWifiEnabled()) {
            notifyLog("Wi-Fi выключен, попытка включить сканирование невозможна");
            return;
        }
        @SuppressWarnings("deprecation")
        boolean started = wifiManager.startScan();
        if (!started) {
            notifyLog("startScan() отклонён системой (возможен троттлинг на Android 9+)");
        }
    }

    private void handleScanResults() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        @SuppressWarnings("deprecation")
        List<ScanResult> results = wifiManager.getScanResults();
        if (results == null) return;

        double lat = lastKnownLocation != null ? lastKnownLocation.getLatitude() : 0.0;
        double lon = lastKnownLocation != null ? lastKnownLocation.getLongitude() : 0.0;
        float accuracy = lastKnownLocation != null ? lastKnownLocation.getAccuracy() : -1f;

        for (ScanResult r : results) {
            String ssid = (r.SSID == null || r.SSID.isEmpty()) ? "<hidden>" : r.SSID;
            WifiTelemetryPoint point = new WifiTelemetryPoint(
                    ssid, r.BSSID, r.level, r.capabilities, r.frequency,
                    lat, lon, accuracy, System.currentTimeMillis());
            collected.add(point);
            for (TelemetryListener l : listeners) {
                l.onNewTelemetry(point);
            }
        }
        notifyLog("Скан завершён: найдено " + results.size() + " точек доступа");
    }

    private void notifyLog(String msg) {
        for (TelemetryListener l : listeners) {
            l.onLog(msg);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Wardriving Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RF Tool")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }
}
