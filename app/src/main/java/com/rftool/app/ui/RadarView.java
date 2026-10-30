package com.rftool.app.ui;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/ui/RadarView.java
 *
 * Кастомная View, рисующая пульсирующий вращающийся радар-экран в стиле
 * инженерной диагностической консоли. Обнаруженные беспроводные устройства
 * отображаются как точки на радарной сетке с подписями.
 *
 * Расположение точки вычисляется из псевдо-угла (стабильного хэша идентификатора
 * устройства) и "дистанции", оценённой по RSSI. Это НЕ триангуляция реального
 * направления (Android API этого не даёт без спецоборудования) — это визуализация
 * релятивной близости/активности для UI, о чём явно сказано в комментарии.
 */

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class RadarView extends View {

    public static class RadarBlip {
        public final String id;
        public final String label;
        public final float distanceNormalized; // 0..1 (0 = центр, 1 = край)
        public final int colorHint;

        public RadarBlip(String id, String label, float distanceNormalized, int colorHint) {
            this.id = id;
            this.label = label;
            this.distanceNormalized = Math.max(0f, Math.min(1f, distanceNormalized));
            this.colorHint = colorHint;
        }
    }

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float sweepAngleDeg = 0f;
    private ValueAnimator sweepAnimator;
    private int accentColor = Color.parseColor("#39FF88");
    private int sweepDurationMs = 3200;
    private int gridRings = 4;

    // ConcurrentHashMap — безопасен при обновлении из фоновых потоков сканеров
    private final ConcurrentHashMap<String, RadarBlip> blips = new ConcurrentHashMap<>();

    public RadarView(Context context) {
        super(context);
        init();
    }

    public RadarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint.setColor(Color.parseColor("#1C2A22"));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(2f);

        sweepPaint.setStyle(Paint.Style.FILL);

        blipPaint.setStyle(Paint.Style.FILL);
        blipPaint.setShadowLayer(12f, 0, 0, Color.parseColor("#3939FF88"));

        labelPaint.setColor(Color.parseColor("#D6FFE4"));
        labelPaint.setTextSize(22f);
        labelPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

        centerDotPaint.setColor(Color.parseColor("#39FF88"));
        centerDotPaint.setStyle(Paint.Style.FILL);

        setLayerType(View.LAYER_TYPE_SOFTWARE, null); // нужен для shadowLayer на некоторых прошивках

        startSweepAnimation();
    }

    private void startSweepAnimation() {
        sweepAnimator = ValueAnimator.ofFloat(0f, 360f);
        sweepAnimator.setDuration(sweepDurationMs);
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
        sweepAnimator.addUpdateListener(animation -> {
            sweepAngleDeg = (float) animation.getAnimatedValue();
            invalidate();
        });
        sweepAnimator.start();
    }

    /** Меняет акцентный цвет радара (развёртка, центр, свечение меток по умолчанию). */
    public void setAccentColor(int color) {
        this.accentColor = color;
        centerDotPaint.setColor(color);
        postInvalidate();
    }

    /** Длительность полного оборота развёртки в мс — меньше = быстрее "пульс" сканирования. */
    public void setSweepDurationMs(int ms) {
        this.sweepDurationMs = Math.max(500, ms);
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }
        startSweepAnimation();
    }

    /** Количество концентрических колец сетки (плотность разметки). */
    public void setGridRings(int rings) {
        this.gridRings = Math.max(2, Math.min(8, rings));
        postInvalidate();
    }

    /** Добавить или обновить метку устройства на радаре. Потокобезопасно. */
    public void upsertBlip(RadarBlip blip) {
        blips.put(blip.id, blip);
        postInvalidate();
    }

    public void removeBlip(String id) {
        blips.remove(id);
        postInvalidate();
    }

    public void clearBlips() {
        blips.clear();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float maxRadius = Math.min(w, h) / 2f - 24f;

        // Фоновая радиальная подсветка центра
        Paint bgGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgGlow.setShader(new RadialGradient(cx, cy, maxRadius,
                Color.parseColor("#14281F"), Color.parseColor("#0A0E0C"), Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, maxRadius, bgGlow);

        // Концентрические кольца сетки
        for (int i = 1; i <= gridRings; i++) {
            canvas.drawCircle(cx, cy, maxRadius * i / gridRings, gridPaint);
        }

        // Крестовина
        canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, gridPaint);
        canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, gridPaint);

        // Диагонали для "технического" вида
        float diag = (float) (maxRadius * Math.cos(Math.PI / 4));
        canvas.drawLine(cx - diag, cy - diag, cx + diag, cy + diag, gridPaint);
        canvas.drawLine(cx - diag, cy + diag, cx + diag, cy - diag, gridPaint);

        // Развёртка (sweep) — сектор-градиент, вращающийся по кругу
        canvas.save();
        canvas.rotate(sweepAngleDeg, cx, cy);
        Paint sweepGradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sweepGlow = Color.argb(0x60, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor));
        Shader sweepShader = new android.graphics.SweepGradient(cx, cy,
                new int[]{Color.TRANSPARENT, sweepGlow, Color.TRANSPARENT},
                new float[]{0f, 0.02f, 0.18f});
        sweepGradientPaint.setShader(sweepShader);
        canvas.drawCircle(cx, cy, maxRadius, sweepGradientPaint);
        canvas.restore();

        // Центр радара
        canvas.drawCircle(cx, cy, 6f, centerDotPaint);

        // Отрисовка меток устройств
        List<RadarBlip> snapshot = new ArrayList<>(blips.values());
        for (RadarBlip blip : snapshot) {
            float angle = (float) ((stableHash(blip.id) % 360) * Math.PI / 180.0);
            float r = maxRadius * blip.distanceNormalized;
            float bx = (float) (cx + r * Math.cos(angle));
            float by = (float) (cy + r * Math.sin(angle));

            blipPaint.setColor(blip.colorHint);
            canvas.drawCircle(bx, by, 10f, blipPaint);

            canvas.drawText(blip.label, bx + 14f, by + 6f, labelPaint);
        }

        // Если устройств нет — показать статус
        if (snapshot.isEmpty()) {
            labelPaint.setColor(Color.parseColor("#6FA989"));
            canvas.drawText("СКАНИРОВАНИЕ...", cx - 90f, cy + maxRadius + 40f, labelPaint);
            labelPaint.setColor(Color.parseColor("#D6FFE4"));
        }
    }

    private int stableHash(String s) {
        int h = 0;
        for (int i = 0; i < s.length(); i++) {
            h = 31 * h + s.charAt(i);
        }
        return Math.abs(h);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (sweepAnimator != null) {
            sweepAnimator.cancel();
        }
    }
}
