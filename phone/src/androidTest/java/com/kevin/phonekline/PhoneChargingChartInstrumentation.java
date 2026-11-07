package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import org.json.JSONArray;
import org.json.JSONObject;

/** Capture the geometry emitted by the charging View in design coordinates (1080 × 2340). */
final class PhoneChargingChartInstrumentation {
    private static final double BID = 261.19, ASK = 268.73;

    static void check(Context context, SharedPreferences options, int theme, int[] chart) throws Exception {
        JSONArray bars = new JSONArray(), levels = new JSONArray();
        for (double price : new double[]{BID, ASK}) {
            boolean bid = price == BID;
            String time = "2026-11-05 " + (bid ? "09:45" : "15:00");
            // Two isolated flat candles also become isolated points in intraday mode.
            bars.put(new JSONObject().put("time",time).put("open",price).put("high",price)
                    .put("low",price).put("close",price));
            levels.put(new JSONObject().put("side",bid ? "bid" : "ask").put("price",price)
                    .put("volume",bid ? 400 : 200));
        }
        // Use non-round prices away from the centers of the legacy 32 display bins.
        JSONObject data = new JSONObject().put("symbol","sh603986").put("date","2026-11-05")
                .put("high",272.99).put("low",259.00).put("bars",bars)
                .put("book",new JSONObject().put("source_at","2026-11-05 15:00:00").put("levels",levels));
        PhoneCandleView view = new PhoneCandleView(context);
        view.layout(0,0,540,1170);
        view.setTheme(theme);
        view.setSurface(PhoneCandleView.Surface.DREAM);
        view.setMarket(data,"sh603986");
        for (String mode : new String[]{"1m","realtime"}) {
            options.edit().putString("chart_mode",mode).apply();
            view.refreshChartOptions();
            Geometry geometry = new Geometry(chart);
            view.draw(geometry);
            geometry.verify(theme + " " + mode, "realtime".equals(mode));
        }
        options.edit().remove("chart_mode").apply();
    }

    private static final class Geometry extends Canvas {
        private final int[] chart;
        private final float priceBottom;
        private float topPrice = Float.POSITIVE_INFINITY, bottomPrice = Float.NEGATIVE_INFINITY;
        private float gridRight = Float.NaN, bidRight = Float.NaN, askRight = Float.NaN;
        private float lastTradeX = Float.NEGATIVE_INFINITY, timeRight = Float.NaN;
        private float bidY = Float.NaN, askY = Float.NaN;

        Geometry(int[] chart) {
            this.chart = chart;
            priceBottom = chart[3]-44-(chart[3]-44-chart[1]-15)*.26f-5;
        }

        @Override public void drawText(String text,float x,float y,Paint paint) {
            if ("15:00".equals(text) && paint.getTextAlign() == Paint.Align.RIGHT) timeRight=x;
        }

        @Override public void drawCircle(float x,float y,float radius,Paint paint) {
            // The two intraday segments each contain only their actual close.
            if (y >= chart[1] && y <= priceBottom) {
                lastTradeX=Math.max(lastTradeX,x);
                observePrice(y);
            }
        }

        @Override public void drawLine(float x0,float y0,float x1,float y1,Paint paint) {
            if (y0 < chart[1] || y0 > priceBottom || y0 != y1) return;
            float length = Math.abs(x1-x0), right = Math.max(x0,x1);
            int redMinusGreen = Color.red(paint.getColor()) - Color.green(paint.getColor());
            if (length == 0 && Math.abs(redMinusGreen) > 20) observePrice(y0); // flat candle wick
            if (length > (chart[2]-chart[0])*.6f && Math.abs(redMinusGreen) < 15) gridRight = right;
            if (length > 20 && redMinusGreen > 20) { bidRight=right; bidY=y0; }
            if (length > 20 && redMinusGreen < -20) { askRight=right; askY=y0; }
        }

        private void observePrice(float y) {
            if (y >= chart[1] && y <= priceBottom) {
                topPrice = Math.min(topPrice,y);
                bottomPrice = Math.max(bottomPrice,y);
            }
        }

        void verify(String label, boolean intraday) {
            if (!(Math.abs(askY-topPrice) <= 1 && Math.abs(bidY-bottomPrice) <= 1))
                throw new AssertionError(label + " depth y " + askY + "/" + bidY + " vs quote y " + topPrice + "/" + bottomPrice);
            if (!(Math.abs(askRight-gridRight) <= 1 && Math.abs(bidRight-gridRight) <= 1))
                throw new AssertionError(label + " depth right edges " + askRight + "/" + bidRight + " vs grid " + gridRight);
            if (intraday && !(Math.abs(timeRight-gridRight) <= 1 && Math.abs(lastTradeX-gridRight) <= 1))
                throw new AssertionError(label + " 15:00 trace/axis x " + lastTradeX + "/" + timeRight + " vs grid " + gridRight);
        }
    }
}
