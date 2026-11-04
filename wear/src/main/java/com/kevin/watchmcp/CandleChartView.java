package com.kevin.watchmcp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** 红涨绿跌；实体方向和文字同时传达涨跌，平盘及空数据也有有效比例尺。 */
public class CandleChartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private JSONArray bars = new JSONArray();
    private final float density;
    private double stableLow = Double.NaN, stableHigh = Double.NaN;

    public CandleChartView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setFocusable(true);
        setContentDescription("K 线，等待数据");
    }

    public void setBars(JSONArray value) {
        bars = value;
        JSONObject last = bars.optJSONObject(bars.length() - 1);
        if (last != null) {
            double open = last.optDouble("open"), close = last.optDouble("close");
            setContentDescription(String.format(Locale.CHINA, "最近 %d 根 K 线，红涨绿跌。最后一根%s，开盘 %.2f，收盘 %.2f，最高 %.2f，最低 %.2f", bars.length(), close >= open ? "上涨或平盘" : "下跌", open, close, last.optDouble("high"), last.optDouble("low")));
        } else setContentDescription("暂无 K 线数据");
        invalidate();
    }

    private void label(Canvas canvas, String value, float x, float y, int color, float sp) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sp * getResources().getDisplayMetrics().scaledDensity);
        canvas.drawText(value, x, y, paint);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (bars.length() == 0 || height < 24 * density) {
            label(canvas, "等待 K 线数据", width * .18f, height * .55f, MainActivity.MUTED, 12);
            return;
        }
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < bars.length(); i++) {
            JSONObject bar = bars.optJSONObject(i);
            if (bar == null) continue;
            low = Math.min(low, bar.optDouble("low"));
            high = Math.max(high, bar.optDouble("high"));
        }
        if (!Double.isFinite(low) || !Double.isFinite(high)) return;
        double padding = Math.max((high - low) * .1, Math.max(.01, high * .001));
        low -= padding; high += padding;
        // 同页只扩容纵轴，避免微小波动引起图表抖动。
        stableLow = Double.isNaN(stableLow) ? low : Math.min(stableLow, low);
        stableHigh = Double.isNaN(stableHigh) ? high : Math.max(stableHigh, high);
        final double minimum = stableLow, range = stableHigh - stableLow;
        float top = 12 * density, bottom = height - 16 * density;
        if (bottom <= top) return;
        float left = 2 * density, right = width - 2 * density;
        paint.setColor(Color.rgb(39, 48, 67));
        paint.setStrokeWidth(density * .5f);
        for (int i = 0; i < 3; i++) {
            float y = top + (bottom - top) * i / 2f;
            canvas.drawLine(left, y, right, y, paint);
        }
        float slot = (right - left) / Math.max(1, bars.length());
        for (int i = 0; i < bars.length(); i++) {
            JSONObject bar = bars.optJSONObject(i);
            if (bar == null) continue;
            double open = bar.optDouble("open"), close = bar.optDouble("close");
            float x = left + slot * (i + .5f);
            float openY = bottom - (float) ((open - minimum) / range) * (bottom - top);
            float closeY = bottom - (float) ((close - minimum) / range) * (bottom - top);
            float highY = bottom - (float) ((bar.optDouble("high") - minimum) / range) * (bottom - top);
            float lowY = bottom - (float) ((bar.optDouble("low") - minimum) / range) * (bottom - top);
            paint.setColor(close >= open ? MainActivity.UP : MainActivity.DOWN);
            paint.setStrokeWidth(Math.max(.7f * density, slot * .2f));
            canvas.drawLine(x, highY, x, lowY, paint);
            float half = Math.max(.5f * density, slot * .32f);
            paint.setStyle(close > open ? Paint.Style.STROKE : Paint.Style.FILL);
            canvas.drawRect(x - half, Math.min(openY, closeY), x + half, Math.max(Math.min(openY, closeY) + density, Math.max(openY, closeY)), paint);
        }
        label(canvas, String.format(Locale.CHINA, "%.2f", stableHigh), left, 10 * density, MainActivity.MUTED, 9);
        JSONObject last = bars.optJSONObject(bars.length() - 1);
        JSONObject previous = bars.optJSONObject(bars.length() - 2);
        if (last != null && previous != null && previous.optDouble("close") > 0) {
            double change = (last.optDouble("close") / previous.optDouble("close") - 1) * 100;
            label(canvas, String.format(Locale.CHINA, "较前根 %+.2f%%", change), left, height - 2 * density, change >= 0 ? MainActivity.UP : MainActivity.DOWN, 10);
        }
    }
}
