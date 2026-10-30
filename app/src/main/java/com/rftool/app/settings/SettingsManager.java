package com.rftool.app.settings;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/settings/SettingsManager.java
 *
 * Централизованное хранилище пользовательских настроек интерфейса и поведения
 * сканеров. Всё через SharedPreferences — просто, без лишних зависимостей,
 * переживает перезапуск приложения.
 */

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

public class SettingsManager {

    private static final String PREFS_NAME = "rftool_settings";

    private static final String KEY_ACCENT_COLOR = "accent_color";
    private static final String KEY_SWEEP_DURATION_MS = "sweep_duration_ms";
    private static final String KEY_GRID_RINGS = "grid_rings";
    private static final String KEY_UNITS_IMPERIAL = "units_imperial";
    private static final String KEY_HAPTIC_ON_DISCOVERY = "haptic_on_discovery";
    private static final String KEY_LOG_LEVEL_FILTER = "log_level_filter"; // 0=ALL,1=METRIC+,2=WARN+
    private static final String KEY_BLIP_TRAIL_ENABLED = "blip_trail_enabled";
    private static final String KEY_AUTOSCROLL_LOG = "autoscroll_log";

    // Пресеты акцентных цветов — подписаны как в UI спиннере
    public static final String[] ACCENT_PRESET_NAMES = {
            "Кислотно-зелёный", "Киберпанк-циан", "Янтарный", "Тревожный красный", "Фиолетовый неон"
    };
    public static final int[] ACCENT_PRESET_VALUES = {
            Color.parseColor("#39FF88"),
            Color.parseColor("#39E5FF"),
            Color.parseColor("#FFB020"),
            Color.parseColor("#FF4C4C"),
            Color.parseColor("#B339FF")
    };

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public int getAccentColor() {
        return prefs.getInt(KEY_ACCENT_COLOR, ACCENT_PRESET_VALUES[0]);
    }

    public void setAccentColor(int color) {
        prefs.edit().putInt(KEY_ACCENT_COLOR, color).apply();
    }

    public int getSweepDurationMs() {
        return prefs.getInt(KEY_SWEEP_DURATION_MS, 3200);
    }

    public void setSweepDurationMs(int ms) {
        prefs.edit().putInt(KEY_SWEEP_DURATION_MS, ms).apply();
    }

    public int getGridRings() {
        return prefs.getInt(KEY_GRID_RINGS, 4);
    }

    public void setGridRings(int rings) {
        prefs.edit().putInt(KEY_GRID_RINGS, rings).apply();
    }

    public boolean isImperialUnits() {
        return prefs.getBoolean(KEY_UNITS_IMPERIAL, false);
    }

    public void setImperialUnits(boolean imperial) {
        prefs.edit().putBoolean(KEY_UNITS_IMPERIAL, imperial).apply();
    }

    public boolean isHapticOnDiscovery() {
        return prefs.getBoolean(KEY_HAPTIC_ON_DISCOVERY, true);
    }

    public void setHapticOnDiscovery(boolean enabled) {
        prefs.edit().putBoolean(KEY_HAPTIC_ON_DISCOVERY, enabled).apply();
    }

    public int getLogLevelFilter() {
        return prefs.getInt(KEY_LOG_LEVEL_FILTER, 0);
    }

    public void setLogLevelFilter(int level) {
        prefs.edit().putInt(KEY_LOG_LEVEL_FILTER, level).apply();
    }

    public boolean isBlipTrailEnabled() {
        return prefs.getBoolean(KEY_BLIP_TRAIL_ENABLED, true);
    }

    public void setBlipTrailEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BLIP_TRAIL_ENABLED, enabled).apply();
    }

    public boolean isAutoscrollLog() {
        return prefs.getBoolean(KEY_AUTOSCROLL_LOG, true);
    }

    public void setAutoscrollLog(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTOSCROLL_LOG, enabled).apply();
    }

    /** Конвертирует метры в отображаемую строку согласно выбранным единицам. */
    public String formatDistance(double meters) {
        if (isImperialUnits()) {
            double feet = meters * 3.28084;
            return String.format("%.1fft", feet);
        }
        return String.format("%.1fm", meters);
    }
}
