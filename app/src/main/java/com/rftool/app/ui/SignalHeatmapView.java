package com.rftool.app.ui;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/ui/SignalHeatmapView.java
 *
 * Отображает накопленные точки телеметрии (Wi-Fi/BLE) как тепловую карту:
 * позиция точки — относительное смещение от первой GPS-фиксации (в условных
 * единицах, не настоящая карта/проекция), цвет — интенсивность сигнала (RSSI).
 * Полезно, чтобы визуально понять зоны слабого/сильного покрытия при обходе
 * помещения или территории.
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class SignalHeatmapView extends View {

    public static class HeatPoint {
        public final double relativeX; // условные метры от точки старта (широта)
        public final double relativeY; // условные метры от точки старта (долгота)
        public final int rssi;
        public final String label;

        public HeatPoint(double relativeX, double relativeY, int rssi, String label) {
            this.relativeX = relativeX;
            this.relativeY = relativeY;
            this.rssi = rssi;
            this.label = label;
        }
    }

    private final List<HeatPoint> points = new CopyOnWriteArrayList<>();
    private Double originLat = null;
    private Double originLon = null;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float metersPerPixel = 0.5f; // масштаб карты — можно сделать настраиваемым (zoom)

    public SignalHeatmapView(Context context) {
        super(context);
        init();
    }

    public SignalHeatmapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.parseColor("#1C2A22"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1.5f);

        pointPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(Color.parseColor("#6FA989"));
        labelPaint.setTextSize(18f);
        labelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);
    }

    /** Добавляет точку, используя реальные GPS-координаты; переводятся в относительные метры. */
    public void addGpsPoint(double lat, double lon, int rssi, String label) {
        if (originLat == null) {
            originLat = lat;
            originLon = lon;
        }
        // Упрощённый перевод градусов в метры (локально, без учёта кривизны на больших расстояниях)
        double dLat = (lat - originLat) * 111_320.0;
        double dLon = (lon - originLon) * 111_320.0 * Math.cos(Math.toRadians(originLat));
        points.add(new HeatPoint(dLon, dLat, rssi, label));
        postInvalidate();
    }

    public void setScale(float metersPerPixel) {
        this.metersPerPixel = Math.max(0.05f, metersPerPixel);
        postInvalidate();
    }

    public void clear() {
        points.clear();
        originLat = null;
        originLon = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;

        // Сетка-миллиметровка для ориентира масштаба
        float step = 40f;
        for (float x = cx % step; x < w; x += step) {
            canvas.drawLine(x, 0, x, h, gridPaint);
        }
        for (float y = cy % step; y < h; y += step) {
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        // Точка старта трека
        pointPaint.setColor(Color.parseColor("#39FF88"));
        canvas.drawCircle(cx, cy, 6f, pointPaint);

        List<HeatPoint> snapshot = new ArrayList<>(points);
        for (HeatPoint p : snapshot) {
            float px = (float) (cx + p.relativeX / metersPerPixel);
            float py = (float) (cy - p.relativeY / metersPerPixel);
            if (px < -20 || px > w + 20 || py < -20 || py > h + 20) continue; // за пределами видимой области

            pointPaint.setColor(rssiToColor(p.rssi));
            canvas.drawCircle(px, py, 9f, pointPaint);
        }

        if (snapshot.isEmpty()) {
            canvas.drawText("Нет накопленных точек — запустите WIFI MAP или BLE SCAN", 20f, h - 30f, labelPaint);
        }
    }

    /** RSSI обычно в диапазоне -100 (слабо) .. -30 (сильно) dBm. */
    private int rssiToColor(int rssi) {
        float t = (rssi + 100f) / 70f; // нормализация к 0..1
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (255 * (1 - t));
        int g = (int) (255 * t);
        return Color.argb(220, r, g, 60);
    }
}
