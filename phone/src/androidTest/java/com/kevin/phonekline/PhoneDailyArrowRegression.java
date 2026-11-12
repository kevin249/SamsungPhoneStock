package com.kevin.phonekline;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

final class PhoneDailyArrowRegression {
    static void check(Instrumentation test) throws Exception {
        Context target = test.getTargetContext();
        SharedPreferences prefs = target.getSharedPreferences("daily-arrows-test", Context.MODE_PRIVATE);
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "phone_dial".equals(name) ? prefs : super.getSharedPreferences(name, mode);
            }
        };
        String symbol = "sh603986";
        JSONArray rows = new JSONArray();
        java.time.LocalDate start = java.time.LocalDate.of(2026, 10, 1);
        for (int i = 0; i < 18; i++) {
            rows.put(new JSONObject().put("time", start.plusDays(i).toString())
                    .put("open", 15).put("low", 10).put("high", 20).put("close", i == 0 || i == 17 ? 10 : 20));
        }
        JSONObject bare = PhoneDailyCandles.history(symbol, "测试", rows);
        JSONObject marked = PhoneRealtimeCandles.copy(bare).put("fills", new JSONArray()
                .put(new JSONObject().put("time", "2026-10-06 10:00").put("price", 15).put("side", "B"))
                .put(new JSONObject().put("time", "2026-10-07 10:00").put("price", 15).put("side", "S")));
        prefs.edit().clear().putString("symbol", symbol).putString("chart_mode", "1d")
                .putBoolean("auto_chart_switch", false).putString("daily_snapshot_" + symbol, marked.toString()).putBoolean("show_signals", true).commit();
        try {
            test.runOnMainSync(() -> {
                require(!new PhoneMarketPlot(marked).hasSignals(), "daily plot exposes B/S");
                PhoneCandleView view = new PhoneCandleView(context);
                view.setTheme(PhoneCandleView.LOCK_BLACK);
                view.layout(0, 0, 1080, 2340);
                for (PhoneCandleView.Surface surface : new PhoneCandleView.Surface[]{
                        PhoneCandleView.Surface.LOCK, PhoneCandleView.Surface.DREAM}) {
                    view.setSurface(surface);
                    view.setMarket(marked, symbol);
                    Bitmap withSignals = render(view);
                    require(!view.getContentDescription().toString().contains("B/S"), surface + " daily B/S description");
                    view.setMarket(bare, symbol);
                    Bitmap withoutSignals = render(view);
                    require(withSignals.sameAs(withoutSignals), "B/S change the daily chart on " + surface);
                    require(hasBothArrowColors(withoutSignals), "daily TDW arrows missing on " + surface);
                    withSignals.recycle();
                    withoutSignals.recycle();
                }

                View aod = PhoneWidgetRenderer.render(context, 0, marked, 315, 285).apply(context, new FrameLayout(context));
                Bitmap aodMarked = widgetPlot(aod);
                require(!aod.getContentDescription().toString().contains("B/S"), "AOD daily B/S description");
                require(hasBothArrowColors(aodMarked), "AOD daily TDW arrows missing");
                prefs.edit().putBoolean("show_signals", false).commit();
                View aodBare = PhoneWidgetRenderer.render(context, 0, marked, 315, 285).apply(context, new FrameLayout(context));
                require(aodMarked.sameAs(widgetPlot(aodBare)), "B/S change the AOD daily chart");
            });
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private static Bitmap render(PhoneCandleView view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static Bitmap widgetPlot(View widget) {
        ImageView image = widget.findViewById(R.id.widget_plot);
        return ((BitmapDrawable) image.getDrawable()).getBitmap();
    }

    private static boolean hasBothArrowColors(Bitmap bitmap) {
        boolean red = false, green = false;
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight() && !(red && green); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int color : row) {
                red |= near(color, 0xffff5151);
                green |= near(color, 0xff48e486);
            }
        }
        return red && green;
    }

    private static boolean near(int actual, int expected) {
        return Math.abs(Color.red(actual) - Color.red(expected)) <= 5
                && Math.abs(Color.green(actual) - Color.green(expected)) <= 5
                && Math.abs(Color.blue(actual) - Color.blue(expected)) <= 5;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
