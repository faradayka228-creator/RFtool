package com.rftool.app;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/MainActivity.java
 *
 * Главная Activity. Связывает все модули и UI, включая Settings-панель,
 * переключение Radar/Heatmap, аудит безопасности Wi-Fi, декодер BLE-вендоров.
 */

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ViewFlipper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rftool.app.ble.BleManufacturerDecoder;
import com.rftool.app.ble.BleScanner;
import com.rftool.app.lan.LanTopologyMapper;
import com.rftool.app.lan.PortScanner;
import com.rftool.app.nfc.CustomHceService;
import com.rftool.app.settings.SettingsManager;
import com.rftool.app.ui.RadarView;
import com.rftool.app.ui.SignalHeatmapView;
import com.rftool.app.ui.TerminalLogAdapter;
import com.rftool.app.wifi.WifiChannelAnalyzer;
import com.rftool.app.wifi.WifiSecurityAuditor;
import com.rftool.app.wifi.WifiWardrivingService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMISSIONS = 42;

    private RadarView radarView;
    private SignalHeatmapView heatmapView;
    private ViewFlipper viewFlipperVis;
    private TextView tvRadarCounter;
    private TextView tvStatusIndicator;
    private RecyclerView rvTerminalLog;
    private EditText etTargetIp;
    private TerminalLogAdapter logAdapter;
    private ImageButton btnSettings;
    private Button btnToggleView;

    private BleScanner bleScanner;
    private LanTopologyMapper lanMapper;
    private PortScanner portScanner;
    private SettingsManager settingsManager;
    private Vibrator vibrator;

    private WifiWardrivingService wardrivingService;
    private boolean wardrivingBound = false;
    private boolean wardrivingRunning = false;
    private boolean bleRunning = false;
    private boolean showingHeatmap = false;

    private final Set<String> radarObjectIds = new HashSet<>();
    private final List<WifiWardrivingService.WifiTelemetryPoint> allWifiPoints = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settingsManager = new SettingsManager(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        bindViews();
        setupTerminal();
        applySettingsToViews();
        setupListeners();
        requestAllPermissions();

        bleScanner = new BleScanner(getApplicationContext());
        lanMapper = new LanTopologyMapper();
        portScanner = new PortScanner();

        CustomHceService.setLogListener((direction, hex) ->
                runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.BROADCAST,
                        "HCE " + direction + ": " + hex)));

        logAdapter.log(TerminalLogAdapter.Level.INFO, "Система инициализирована");
    }

    private void bindViews() {
        radarView = findViewById(R.id.radarView);
        heatmapView = findViewById(R.id.heatmapView);
        viewFlipperVis = findViewById(R.id.viewFlipperVis);
        tvRadarCounter = findViewById(R.id.tvRadarCounter);
        tvStatusIndicator = findViewById(R.id.tvStatusIndicator);
        rvTerminalLog = findViewById(R.id.rvTerminalLog);
        etTargetIp = findViewById(R.id.etTargetIp);
        btnSettings = findViewById(R.id.btnSettings);
        btnToggleView = findViewById(R.id.btnToggleView);
    }

    private void setupTerminal() {
        logAdapter = new TerminalLogAdapter();
        rvTerminalLog.setLayoutManager(new LinearLayoutManager(this));
        rvTerminalLog.setAdapter(logAdapter);
        logAdapter.setFilterLevel(settingsManager.getLogLevelFilter());

        // Автоскролл: следим за вставкой новых строк и, если включено, прокручиваем вниз
        logAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                if (settingsManager.isAutoscrollLog()) {
                    rvTerminalLog.scrollToPosition(logAdapter.getItemCount() - 1);
                }
            }
        });
    }

    private void applySettingsToViews() {
        radarView.setAccentColor(settingsManager.getAccentColor());
        radarView.setSweepDurationMs(settingsManager.getSweepDurationMs());
        radarView.setGridRings(settingsManager.getGridRings());
    }

    private void setupListeners() {
        Button btnBle = findViewById(R.id.btnBle);
        Button btnWifi = findViewById(R.id.btnWifi);
        Button btnLan = findViewById(R.id.btnLan);
        Button btnPorts = findViewById(R.id.btnPorts);
        Button btnNfc = findViewById(R.id.btnNfc);
        Button btnClear = findViewById(R.id.btnClear);

        btnBle.setOnClickListener(v -> toggleBleScan());
        btnWifi.setOnClickListener(v -> toggleWardriving());
        btnLan.setOnClickListener(v -> startLanSweep());
        btnPorts.setOnClickListener(v -> startPortScan());
        btnNfc.setOnClickListener(v -> logAdapter.log(TerminalLogAdapter.Level.INFO,
                "NFC/HCE готов. Поднесите терминал/ридер с поддержкой кастомного AID F0010203040506"));
        btnClear.setOnClickListener(v -> logAdapter.clear());

        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnToggleView.setOnClickListener(v -> toggleRadarHeatmap());
    }

    private void toggleRadarHeatmap() {
        showingHeatmap = !showingHeatmap;
        viewFlipperVis.setDisplayedChild(showingHeatmap ? 1 : 0);
        btnToggleView.setText(showingHeatmap ? "RADAR" : "HEATMAP");
    }

    // ===================== НАСТРОЙКИ =====================

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        Spinner spinnerAccent = dialogView.findViewById(R.id.spinnerAccentColor);
        SeekBar seekSweep = dialogView.findViewById(R.id.seekSweepSpeed);
        TextView tvSweepValue = dialogView.findViewById(R.id.tvSweepSpeedValue);
        SeekBar seekRings = dialogView.findViewById(R.id.seekGridRings);
        TextView tvRingsValue = dialogView.findViewById(R.id.tvGridRingsValue);
        Switch switchImperial = dialogView.findViewById(R.id.switchImperialUnits);
        Switch switchHaptic = dialogView.findViewById(R.id.switchHaptic);
        Switch switchAutoscroll = dialogView.findViewById(R.id.switchAutoscroll);
        Spinner spinnerLogLevel = dialogView.findViewById(R.id.spinnerLogLevel);
        Button btnApply = dialogView.findViewById(R.id.btnApplySettings);

        ArrayAdapter<String> accentAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, SettingsManager.ACCENT_PRESET_NAMES);
        spinnerAccent.setAdapter(accentAdapter);
        int currentAccent = settingsManager.getAccentColor();
        for (int i = 0; i < SettingsManager.ACCENT_PRESET_VALUES.length; i++) {
            if (SettingsManager.ACCENT_PRESET_VALUES[i] == currentAccent) {
                spinnerAccent.setSelection(i);
                break;
            }
        }

        // SeekBar 0..55 -> реальная длительность 500..6000мс (шаг 100)
        seekSweep.setMax(55);
        int currentSweepSteps = (settingsManager.getSweepDurationMs() - 500) / 100;
        seekSweep.setProgress(Math.max(0, Math.min(55, currentSweepSteps)));
        tvSweepValue.setText((settingsManager.getSweepDurationMs()) + " мс / оборот");
        seekSweep.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int ms = 500 + progress * 100;
                tvSweepValue.setText(ms + " мс / оборот");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        seekRings.setMax(6);
        seekRings.setProgress(settingsManager.getGridRings() - 2);
        tvRingsValue.setText(settingsManager.getGridRings() + " колец");
        seekRings.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvRingsValue.setText((progress + 2) + " колец");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        switchImperial.setChecked(settingsManager.isImperialUnits());
        switchHaptic.setChecked(settingsManager.isHapticOnDiscovery());
        switchAutoscroll.setChecked(settingsManager.isAutoscrollLog());

        ArrayAdapter<CharSequence> logLevelAdapter = ArrayAdapter.createFromResource(this,
                R.array.log_level_filter_options, android.R.layout.simple_spinner_dropdown_item);
        spinnerLogLevel.setAdapter(logLevelAdapter);
        spinnerLogLevel.setSelection(settingsManager.getLogLevelFilter());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();

        btnApply.setOnClickListener(v -> {
            int selectedAccent = SettingsManager.ACCENT_PRESET_VALUES[spinnerAccent.getSelectedItemPosition()];
            settingsManager.setAccentColor(selectedAccent);

            int sweepMs = 500 + seekSweep.getProgress() * 100;
            settingsManager.setSweepDurationMs(sweepMs);

            int rings = seekRings.getProgress() + 2;
            settingsManager.setGridRings(rings);

            settingsManager.setImperialUnits(switchImperial.isChecked());
            settingsManager.setHapticOnDiscovery(switchHaptic.isChecked());
            settingsManager.setAutoscrollLog(switchAutoscroll.isChecked());
            settingsManager.setLogLevelFilter(spinnerLogLevel.getSelectedItemPosition());

            applySettingsToViews();
            logAdapter.setFilterLevel(settingsManager.getLogLevelFilter());
            logAdapter.log(TerminalLogAdapter.Level.INFO, "Настройки применены");
            dialog.dismiss();
        });

        dialog.show();
    }

    private void triggerHapticIfEnabled() {
        if (!settingsManager.isHapticOnDiscovery() || vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(40);
        }
    }

    private void updateRadarCounter() {
        tvRadarCounter.setText("OBJ: " + radarObjectIds.size());
    }

    // ===================== BLE =====================

    private void toggleBleScan() {
        if (!bleRunning) {
            bleScanner.startScanning(new BleScanner.DiscoveryListener() {
                @Override
                public void onDeviceUpdated(BleScanner.BleDevice device) {
                    runOnUiThread(() -> {
                        boolean isNew = radarObjectIds.add(device.address);
                        if (isNew) triggerHapticIfEnabled();

                        float normalized = (float) Math.min(1.0, device.estimatedDistanceM / 20.0);
                        String distLabel = settingsManager.formatDistance(device.estimatedDistanceM);
                        RadarView.RadarBlip blip = new RadarView.RadarBlip(
                                device.address,
                                device.name + " " + device.rssi + "dBm",
                                normalized,
                                settingsManager.getAccentColor());
                        radarView.upsertBlip(blip);
                        updateRadarCounter();

                        logAdapter.log(TerminalLogAdapter.Level.METRIC,
                                String.format("BLE %s RSSI=%d dBm dist~%s VENDOR=%s TYPE=%s",
                                        device.address, device.rssi, distLabel, device.vendor, device.deviceTypeHint));
                    });
                }

                @Override
                public void onLog(String message) {
                    runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.INFO, message));
                }

                @Override
                public void onScanFailed(int errorCode) {
                    runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.ERROR,
                            "BLE scan failed, код=" + errorCode));
                }
            });
            bleRunning = true;
            tvStatusIndicator.setText("● BLE_ACTIVE");
        } else {
            bleScanner.stopScanning();
            bleRunning = false;
            tvStatusIndicator.setText("● READY");
            logAdapter.log(TerminalLogAdapter.Level.INFO, "BLE-сканирование остановлено");
        }
    }

    // ===================== WIFI WARDRIVING =====================

    private final ServiceConnection wardrivingConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            WifiWardrivingService.LocalBinder binder = (WifiWardrivingService.LocalBinder) service;
            wardrivingService = binder.getService();
            wardrivingBound = true;
            wardrivingService.addListener(new WifiWardrivingService.TelemetryListener() {
                @Override
                public void onNewTelemetry(WifiWardrivingService.WifiTelemetryPoint point) {
                    runOnUiThread(() -> {
                        allWifiPoints.add(point);
                        boolean isNew = radarObjectIds.add(point.bssid);
                        if (isNew) triggerHapticIfEnabled();

                        WifiSecurityAuditor.AuditResult audit = WifiSecurityAuditor.audit(point.capabilities);
                        int channel = WifiChannelAnalyzer.frequencyToChannel(point.frequencyMhz);

                        logAdapter.log(TerminalLogAdapter.Level.METRIC,
                                String.format("WIFI %s [%s] RSSI=%d ch=%d(%s) SEC=%s RISK=%s",
                                        point.ssid, point.bssid, point.rssi, channel,
                                        WifiChannelAnalyzer.band(point.frequencyMhz),
                                        audit.encryptionSummary, WifiSecurityAuditor.riskLabel(audit.risk)));

                        if (audit.risk == WifiSecurityAuditor.RiskLevel.CRITICAL) {
                            logAdapter.log(TerminalLogAdapter.Level.WARN,
                                    "⚠ " + point.ssid + ": " + audit.note);
                        }

                        if (point.latitude != 0.0 || point.longitude != 0.0) {
                            heatmapView.addGpsPoint(point.latitude, point.longitude, point.rssi, point.ssid);
                        }

                        updateRadarCounter();
                    });
                }

                @Override
                public void onLog(String message) {
                    runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.INFO, message));
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            wardrivingBound = false;
        }
    };

    private void toggleWardriving() {
        if (!wardrivingRunning) {
            Intent intent = new Intent(this, WifiWardrivingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            bindService(intent, wardrivingConnection, Context.BIND_AUTO_CREATE);
            wardrivingRunning = true;
            tvStatusIndicator.setText("● WARDRIVING");
            logAdapter.log(TerminalLogAdapter.Level.INFO, "Wardriving-сервис запущен");
        } else {
            if (wardrivingBound) {
                unbindService(wardrivingConnection);
                wardrivingBound = false;
            }
            stopService(new Intent(this, WifiWardrivingService.class));
            wardrivingRunning = false;
            tvStatusIndicator.setText("● READY");

            // Краткая сводка по загруженности каналов 2.4ГГц после остановки сессии
            if (!allWifiPoints.isEmpty()) {
                java.util.Map<Integer, Integer> congestion = WifiChannelAnalyzer.buildCongestionMap(allWifiPoints);
                int best = WifiChannelAnalyzer.findLeastCongestedChannel(congestion, WifiChannelAnalyzer.NON_OVERLAPPING_24GHZ);
                logAdapter.log(TerminalLogAdapter.Level.INFO,
                        "Рекомендуемый наименее загруженный канал (1/6/11): " + best);
            }
            logAdapter.log(TerminalLogAdapter.Level.INFO, "Wardriving-сервис остановлен");
        }
    }

    // ===================== LAN SWEEP =====================

    private void startLanSweep() {
        logAdapter.log(TerminalLogAdapter.Level.INFO, "Запуск ping sweep подсети...");
        lanMapper.startSweep(getApplicationContext(), 500, new LanTopologyMapper.ScanListener() {
            @Override
            public void onHostFound(LanTopologyMapper.HostResult result) {
                runOnUiThread(() -> {
                    boolean isNew = radarObjectIds.add(result.ip);
                    if (isNew) triggerHapticIfEnabled();

                    logAdapter.log(TerminalLogAdapter.Level.BROADCAST,
                            String.format("HOST %s MAC=%s VENDOR=%s RTT=%dms",
                                    result.ip, result.mac, result.vendor, result.rttMs));
                    RadarView.RadarBlip blip = new RadarView.RadarBlip(
                            result.ip, result.ip, 0.6f, Color.parseColor("#59C8FF"));
                    radarView.upsertBlip(blip);
                    updateRadarCounter();
                });
            }

            @Override
            public void onProgress(int scanned, int total) { }

            @Override
            public void onComplete(List<LanTopologyMapper.HostResult> allHosts) {
                runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.INFO,
                        "LAN sweep завершён. Найдено хостов: " + allHosts.size()));
            }

            @Override
            public void onLog(String message) {
                runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.WARN, message));
            }
        });
    }

    // ===================== PORT SCAN =====================

    private void startPortScan() {
        String target = etTargetIp.getText().toString().trim();
        if (target.isEmpty()) {
            logAdapter.log(TerminalLogAdapter.Level.WARN, "Укажите TARGET_IP перед сканированием портов");
            return;
        }
        logAdapter.log(TerminalLogAdapter.Level.INFO, "Сканирование портов " + target + " [1-1024]...");

        portScanner.scanRange(target, 1, 1024, 400, 64, new PortScanner.ScanListener() {
            @Override
            public void onPortResult(PortScanner.PortResult result) {
                runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.METRIC,
                        String.format("PORT %d OPEN (%s) %dms", result.port, result.serviceGuess, result.latencyMs)));
            }

            @Override
            public void onProgress(int scanned, int total) { }

            @Override
            public void onComplete(List<PortScanner.PortResult> openPorts) {
                runOnUiThread(() -> logAdapter.log(TerminalLogAdapter.Level.INFO,
                        "Port scan завершён. Открыто портов: " + openPorts.size()));
            }
        });
    }

    // ===================== PERMISSIONS =====================

    private void requestAllPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> notGranted = new ArrayList<>();
        for (String p : perms) {
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                notGranted.add(p);
            }
        }
        if (!notGranted.isEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            logAdapter.log(TerminalLogAdapter.Level.INFO, "Запрос разрешений обработан");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bleRunning) bleScanner.stopScanning();
        lanMapper.shutdown();
        portScanner.cancel();
        if (wardrivingBound) {
            unbindService(wardrivingConnection);
        }
    }
}
