package com.kevin.phonekline;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import org.json.JSONObject;
import java.util.Locale;

/** One quote row on the same trading-session time axis as the full intraday chart. */
final class PhoneMiniRow {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final PhoneMarketPlot plot;
    private final String name, price, change;
    final String description;
    private final int color;
    private final double low, high;

    PhoneMiniRow(SharedPreferences prefs, String code, JSONObject data) {
        if (data != null && (!code.equals(data.optString("symbol")) || "1d".equals(data.optString("period")))) data = null;
        plot = PhoneMarketPlot.mini(data);
        String title = data == null ? "" : data.optString("name");
        if (title.isEmpty() || title.equals("null") || title.equals(code) || title.equals(code.substring(2))) title = prefs.getString("stock_name_" + code, "");
        name = title.isEmpty() ? code.substring(2) : title;
        double last = data == null ? Double.NaN : data.optDouble("price");
        if (!PhoneRealtimeCandles.positive(last)) last = plot.lastClose();
        price = PhoneMarketPlot.format(last);
        double previous = data == null ? Double.NaN : data.optDouble("previous");
        double percent = PhoneRealtimeCandles.positive(previous) ? (last / previous - 1) * 100 : Double.NaN;
        change = Double.isFinite(percent) ? String.format(Locale.ROOT, "%+.2f%%", percent) : "—";
        color = percent > 0 ? PhoneMarketPlot.WIDGET_UP : percent < 0 ? PhoneMarketPlot.WIDGET_DOWN : PhoneMarketPlot.WIDGET_TEXT;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (PhoneMarketPlot.TracePoint point : plot.trace) {
            min = Math.min(min, point.price);
            max = Math.max(max, point.price);
        }
        low = min;
        high = max;
        description = name + " " + code + "，单行分时，涨跌 " + change + "，现价 " + price;
    }

    void draw(Canvas canvas, float left, float top, float width, float height) {
        int save = canvas.save();
        canvas.translate(left, top);
        float font = Math.min(height * .57f, width * .058f);
        text(canvas, name, 0, width * .29f, height, font, false, PhoneMarketPlot.WIDGET_TEXT);
        text(canvas, change, width * .78f, width * .23f, height, font, true, color);
        text(canvas, price, width, width * .21f, height, font, true, color);
        paint.setColor(color);
        paint.setStrokeWidth(Math.max(1f, height * .045f));
        float lastX = 0, lastY = 0;
        for (PhoneMarketPlot.TracePoint point : plot.trace) {
            float x = width * (.30f + .24f * point.slot / 240f);
            float y = height * (high == low ? .5f : .84f - .68f * (float)((point.price - low) / (high - low)));
            if (point.start) canvas.drawCircle(x, y, paint.getStrokeWidth() / 2, paint);
            else canvas.drawLine(lastX, lastY, x, y, paint);
            lastX = x;
            lastY = y;
        }
        canvas.restoreToCount(save);
    }

    private void text(Canvas c, String value, float x, float limit, float height, float size, boolean right, int ink) {
        paint.setColor(ink);
        paint.setTextAlign(right ? Paint.Align.RIGHT : Paint.Align.LEFT);
        paint.setTextSize(size);
        float measured = paint.measureText(value);
        if (measured > limit) paint.setTextSize(size * limit / measured);
        c.drawText(value, x, (height - paint.ascent() - paint.descent()) / 2, paint);
    }
}
