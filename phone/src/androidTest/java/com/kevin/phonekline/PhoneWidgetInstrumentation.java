package com.kevin.phonekline;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One UI owns the ambient clock. Exercise the separate, quote-only RemoteViews as an external host. */
final class PhoneWidgetInstrumentation {
    private static final int ID = AppWidgetManager.INVALID_APPWIDGET_ID;
    private PhoneWidgetInstrumentation() { }

    static void check(Context target, Context host) throws Exception {
        SharedPreferences prefs = target.getSharedPreferences("widget-render-instrumentation", Context.MODE_PRIVATE);
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "phone_dial".equals(name) ? prefs : super.getSharedPreferences(name, mode);
            }
        };
        JSONObject data = fixture();
        try {
            android.util.Log.i("PhoneRegression", "widget: checkPowerRates(context,prefs)");
            checkPowerRates(context,prefs);
            PhoneDualWidgetInstrumentation.check(context,host,prefs);
            checkUnlockGestures(context, host, prefs);
            checkFullChartHeights(context, host, prefs, data);
            checkTenVisibleCharts(context, host, prefs);
            prefs.edit().clear().putString("symbol", "sz000063").putInt("aod_theme", 104)
                    .putInt(PhoneDisplayStocks.HEIGHT_PREFIX + "sz000063", 0)
                    .putInt(PhoneDisplayStocks.HEIGHT_PREFIX + "sh600519", 0)
                    .putString("chart_mode", "1m").putBoolean("show_depth", true).putBoolean("show_signals", true).commit();
            RemoteViews first = render(context, ID, data);
            android.util.Log.i("PhoneRegression", "widget: checkWallpaperVisibility()");
            checkWallpaperVisibility();
            checkBoundHostUpgrade(context, host, R.layout.phone_market_widget, 0x7f030000, first);
            checkBoundHostUpgrade(context, host, R.layout.phone_market_widget_v2, 0x7f030001, first);
            checkBoundHostUpgrade(context, host, R.layout.phone_market_widget_v3, 0x7f030002, first);
            View widget = first.apply(host, new FrameLayout(host));
            checkHostContent(widget);
            require(text(widget, R.id.widget_code).contains("000063"), "market code");
            require(text(widget, R.id.widget_chart_legend).contains("1 分钟 K"), "candle mode");
            require(text(widget, R.id.widget_stamp).contains("盘口 14:30:06"), "actual book source time");
            Bitmap candles = plot(widget);
            android.util.Log.i("PhoneRegression", "widget: checkDepthScale(prefs, data)");
            checkDepthScale(prefs, data);
            JSONObject bookOnly = new JSONObject(data.toString());
            JSONObject updatedBook = bookOnly.getJSONObject("book");
            updatedBook.put("source_at", "2026-11-05 14:30:11");
            updatedBook.getJSONArray("levels").getJSONObject(0).put("volume", 4700);
            render(context, ID, bookOnly).reapply(host, widget);
            String updatedStamp = text(widget, R.id.widget_stamp);
            require(updatedStamp.contains("14:30:08") && updatedStamp.contains("盘口 14:30:11")
                    && !candles.sameAs(plot(widget)), "depth-only update with unchanged last trade");

            prefs.edit().putBoolean("show_depth", false).commit();
            render(context, ID, data).reapply(host, widget);
            Bitmap noBook = plot(widget);
            require(!noBook.sameAs(candles), "real book overlay toggle");

            prefs.edit().putBoolean("show_signals", false).commit();
            render(context, ID, data).reapply(host, widget);
            android.util.Log.i("PhoneRegression", "widget: Bitmap bare = plot(widget)");
            Bitmap bare = plot(widget);
            require(!bare.sameAs(noBook), "matched trade markers toggle");
            require(hasYellow(bare), "MA5 on the candle chart");

            prefs.edit().putString("chart_mode", "realtime").commit();
            render(context, ID, data).reapply(host, widget);
            require(text(widget, R.id.widget_chart_legend).contains("分时"), "intraday mode");
            require(!bare.sameAs(plot(widget)), "intraday line vs candle bodies");
            require(!new PhoneMarketPlot(data,true,true,true,true).hasAverage(), "no invented average when turnover is unavailable");
            require(text(widget, R.id.widget_chart_legend).contains("TDW"), "TDW legend in AOD realtime mode");
            require(hasTdw(plot(widget)), "TDW threshold lines in AOD realtime bitmap");
            JSONObject weighted = new JSONObject(data.toString());
            JSONArray minuteRows = weighted.getJSONArray("bars");
            for (int i=0; i<minuteRows.length(); i++) minuteRows.getJSONObject(i).put("session_average",35.4+i*.003);
            render(context, ID, weighted).reapply(host, widget);
            require(hasYellow(plot(widget)), "yellow weighted session average");

            android.util.Log.i("PhoneRegression", "widget: prefs.edit().putInt(aod_theme, 108).commit()");
            prefs.edit().putInt("aod_theme", 108).commit();
            for (String mode : new String[]{"1m", "realtime"}) {
                prefs.edit().putString("chart_mode", mode).commit();
                for (int[] size : new int[][]{{150,95},{315,285}}) {
                    checkDailyLabels(context, host, widget, data, size[0], size[1]);
                    checkSessionPositions(context, host, widget, prefs, data, size[0], size[1]);
                }
            }
            android.util.Log.i("PhoneRegression", "widget: JSONObject summary = new JSONObject(data.toString())");
            JSONObject summary = new JSONObject(data.toString());
            summary.remove("bars");
            render(context, ID, summary, 150, 95).reapply(host, widget);
            Bitmap summaryPlot = plot(widget);
            summary.put("high", 39.77).put("low", 30.10);
            render(context, ID, summary, 150, 95).reapply(host, widget);
            require(changedLabel(summaryPlot, plot(widget), true) && changedLabel(summaryPlot, plot(widget), false),
                    "quote-only daily numbers");

            prefs.edit().putString("symbol", "sh600519").commit();
            render(context, ID, null).reapply(host, widget);
            requireNoClock(widget);
            require(text(widget, R.id.widget_code).contains("600519"), "new symbol");
            require(!text(widget, R.id.widget_name).contains("行情测试"), "stale stock name");
            require("—".equals(text(widget, R.id.widget_price)), "empty price");
            require(widget.findViewById(R.id.widget_plot).getVisibility() != View.VISIBLE, "stale chart");
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private static RemoteViews render(Context context, int id, JSONObject data) {
        return render(context, id, data, 280, 210);
    }

    private static RemoteViews render(Context context, int id, JSONObject data, int width, int height) {
        String mode = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE).getString("chart_mode", "1m");
        return PhoneWidgetRenderer.render(context, id, data, width, height, mode);
    }

    private static boolean hasTdw(Bitmap bitmap) {
        for (int y = bitmap.getHeight() / 2; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int color = bitmap.getPixel(x, y);
                if (Color.red(color) > 60 && Color.blue(color) > 60
                        && Color.red(color) > Color.green(color) * 1.5 && Color.blue(color) > Color.green(color) * 1.5) return true;
            }
        }
        return false;
    }

    private static AppWidgetHostView boundHost(Context target, Context hostContext) {
        AppWidgetHostView host = new AppWidgetHostView(hostContext);
        AppWidgetManager manager = AppWidgetManager.getInstance(target);
        AppWidgetProviderInfo info = null;
        for (AppWidgetProviderInfo item : manager.getInstalledProviders()) {
            if (item.provider.getClassName().equals(PhoneMarketWidgetProvider.class.getName())) { info = item; break; }
        }
        require(info != null, "installed widget provider");
        host.setAppWidget(ID, info);
        return host;
    }

    private static void checkUnlockGestures(Context target, Context hostContext, SharedPreferences prefs) {
        String a = "sz000063", b = "sh600000", c = "sz000001";
        prefs.edit().putInt(PhoneDisplayStocks.HEIGHT_PREFIX + a, 0)
                .putInt(PhoneDisplayStocks.HEIGHT_PREFIX + b, 0).apply();
        AppWidgetHostView host = boundHost(target, hostContext);
        checkPassiveUpgrade(target, host, prefs, Collections.singletonList(a), Collections.emptySet(),
                285, R.layout.phone_market_widget_zsingle, R.id.widget_root, false);
        checkPassiveUpgrade(target, host, prefs, Arrays.asList(a, b), Collections.emptySet(),
                285, R.layout.phone_market_widget_zdual, R.id.widget_root, false);
        checkPassiveUpgrade(target, host, prefs, Arrays.asList(a, b), Collections.singleton(a),
                285, R.layout.phone_market_widget_zzstack, R.id.widget_zz_mini_row, false);
        checkPassiveUpgrade(target, host, prefs, Arrays.asList(a, b, c), new HashSet<>(Arrays.asList(a, c)),
                79, R.layout.phone_market_widget_zzstack, R.id.widget_zz_mini_row, true);
    }

    private static void checkPassiveUpgrade(Context target, AppWidgetHostView host, SharedPreferences prefs,
                                            List<String> stocks, Set<String> minis, int height,
                                            int shippedLayout, int rowId, boolean paged) {
        PhoneDisplayStocks.save(prefs, stocks, minis);
        prefs.edit().putInt(PhoneWidgetRenderer.PAGE + ID, 0).apply();
        RemoteViews desktop = PhoneWidgetRenderer.render(target, ID, null, 300, height, "realtime", true);
        require(desktop.getLayoutId() == shippedLayout, "desktop retains its existing layout");
        host.updateAppWidget(desktop);
        View old = host.getChildAt(0);
        require(old.findViewById(rowId).hasOnClickListeners(), "desktop stock remains actionable");
        if (paged) {
            View next = old.findViewById(R.id.widget_zz_next_page);
            require(next.getVisibility() == View.VISIBLE && next.hasOnClickListeners(), "desktop next page remains actionable");
        }
        // The in-place host starts with the same layout IDs and clickable descendants as v1.21.
        // INVALID_APPWIDGET_ID has no host metadata. It must take the same passive path as LockStar.
        RemoteViews passive = PhoneWidgetRenderer.render(target, ID, null, 300, height, "realtime");
        host.updateAppWidget(passive);
        View root = host.getChildAt(0);
        require(root != old, "upgrade replaces previously clickable hierarchy");
        if (paged) require(((ViewGroup)root.findViewById(R.id.widget_zz_stock_rows)).getChildCount() > 0, "passive page retains stocks");
        else require(root.findViewById(rowId) != null, "passive host retains the stock");
        if (paged) {
            TextView page = root.findViewById(R.id.widget_zz_next_page);
            require(page.getVisibility() == View.VISIBLE && !page.getText().toString().contains("下一页"), "passive page indicator");
        }
        int widthPx = Math.round(300 * target.getResources().getDisplayMetrics().density);
        int heightPx = Math.round(height * target.getResources().getDisplayMetrics().density);
        for (int update = 0; update < 2; update++) {
            if (update != 0) host.updateAppWidget(passive);
            root = host.getChildAt(0);
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, widthPx, heightPx);
            requirePassiveTree(root);
            for (float y : new float[]{2, heightPx / 2f, heightPx - 2}) {
                long now = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, widthPx / 2f, y, 0);
                try { require(!root.dispatchTouchEvent(down), "lockscreen quote must yield swipe start at y=" + y); }
                finally { down.recycle(); }
            }
        }
    }

    private static void requirePassiveTree(View view) {
        require(!view.isClickable() && !view.isLongClickable() && !view.hasOnClickListeners(),
                "touchable lockscreen descendant id=" + view.getId());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) requirePassiveTree(group.getChildAt(i));
        }
    }

    private static void checkFullChartHeights(Context target, Context hostContext, SharedPreferences prefs, JSONObject quote) throws Exception {
        String full = quote.optString("symbol"), b = "sh600000", c = "sz000001";
        prefs.edit().clear().putBoolean(PhoneDisplayWindow.AUTOMATIC, false)
                .putString(PhoneDisplayWindow.MODE, "realtime").commit();
        PhoneDisplayStocks.save(prefs, Arrays.asList(full, b, c), new HashSet<>(Arrays.asList(b, c)));
        for (String symbol : Arrays.asList(b, c)) {
            JSONObject mini = new JSONObject(quote.toString()).put("symbol", symbol).put("name", symbol.substring(2));
            prefs.edit().putString("snapshot_" + symbol, mini.toString()).apply();
        }
        AppWidgetHostView host = boundHost(target, hostContext);
        PhoneSystemDisplay display = new PhoneSystemDisplay(target, false, () -> {});
        PhoneWidgetPresentation dream = display.contentView();
        String heightKey = PhoneDisplayStocks.HEIGHT_PREFIX + full;
        require(!prefs.contains(heightKey) && PhoneDisplayStocks.fullHeightDp(prefs, full) == 332,
                "missing full-chart preference uses 332 dp");
        View tall = measuredPassiveWidget(target, host, quote, 420);
        ViewGroup rows = tall.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 3, "default full chart and both Mini K rows fit in a tall host");
        checkHeightDp(rows.getChildAt(0), 332);
        checkHeightDp(rows.getChildAt(1), 26);
        checkHeightDp(rows.getChildAt(2), 26);
        int previousPlot = 0;
        for (int dp : new int[]{104, 130}) {
            prefs.edit().putInt(heightKey, dp).apply();
            View root = compareDreamWithWidget(target, host, dream, quote);
            rows = root.findViewById(R.id.widget_zz_stock_rows);
            require(rows.getChildCount() == 3, "custom full panel fits with two Mini K rows");
            checkHeightDp(rows.getChildAt(0), dp);
            checkHeightDp(rows.getChildAt(0).findViewById(R.id.widget_root), dp);
            checkHeightDp(rows.getChildAt(1), 26);
            checkHeightDp(rows.getChildAt(2), 26);
            View plot = rows.getChildAt(0).findViewById(R.id.widget_plot);
            require(plot.getVisibility() == View.VISIBLE && plot.getHeight() > previousPlot,
                    "increasing full height grows the chart in the same host");
            previousPlot = plot.getHeight();
        }
        prefs.edit().putInt(heightKey, 312).apply();
        View fitted = compareDreamWithWidget(target, host, dream, quote);
        rows = fitted.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 3 && fitted.findViewById(R.id.widget_zz_next_page).getVisibility() == View.GONE,
                "shrinking the full chart keeps both Mini K rows on the page");
        checkHeightDp(rows.getChildAt(0), 166);
        checkHeightDp(rows.getChildAt(1), 26);
        checkHeightDp(rows.getChildAt(2), 26);
        dream.clearFrame();
        require(dream.findViewById(R.id.widget_zz_stock_rows) == null
                        && display.view.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_YES,
                "removing the LockStar frame restores the standalone charging display");
        display.close();

        prefs.edit().putInt(heightKey, 0).apply();
        View auto = measuredPassiveWidget(target, host, quote, 420);
        rows = auto.findViewById(R.id.widget_zz_stock_rows);
        checkHeightDp(rows.getChildAt(0), 368);
        prefs.edit().remove(heightKey).apply();
        View restored = measuredPassiveWidget(target, host, quote, 420);
        rows = restored.findViewById(R.id.widget_zz_stock_rows);
        checkHeightDp(rows.getChildAt(0), 332);

        PhoneDisplayStocks.save(prefs, Arrays.asList(full, b), Collections.emptySet());
        prefs.edit().putInt(heightKey, 104).putInt(PhoneDisplayStocks.HEIGHT_PREFIX + b, 156).apply();
        View two = measuredPassiveWidget(target, host, quote, 300);
        rows = two.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 2, "custom full chart heights coexist");
        checkHeightDp(rows.getChildAt(0), 104);
        checkHeightDp(rows.getChildAt(1), 156);

        PhoneDisplayStocks.save(prefs, Arrays.asList(full, c, b), Collections.singleton(c));
        prefs.edit().putInt(heightKey, 260).apply();
        View mixed = measuredPassiveWidget(target, host, quote, 346);
        rows = mixed.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 3 && mixed.findViewById(R.id.widget_zz_next_page).getVisibility() == View.GONE,
                "mixed-height charts share a page");
        checkHeightDp(rows.getChildAt(0), 200);
        checkHeightDp(rows.getChildAt(1), 26);
        checkHeightDp(rows.getChildAt(2), 120);
        for (int i : new int[]{0, 2}) {
            View panel = rows.getChildAt(i);
            ImageView image = panel.findViewById(R.id.widget_plot);
            require(image.getVisibility() == View.VISIBLE && image.getHeight() > 0 && image.getDrawable() != null,
                    "proportional fitting preserves the plot for full row " + i);
        }
        String d = "sh600001";
        PhoneDisplayStocks.save(prefs, Arrays.asList(full, c, b, d), new HashSet<>(Arrays.asList(c, d)));
        prefs.edit().remove(heightKey).remove(PhoneDisplayStocks.HEIGHT_PREFIX + b).apply();
        View tight = measuredPassiveWidget(target, host, quote, 218);
        rows = tight.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 4 && tight.findViewById(R.id.widget_zz_next_page).getVisibility() == View.GONE,
                "two default charts and two Mini K rows share the original LockStar host");
        for (int i = 0; i < rows.getChildCount(); i++) {
            boolean mini = i % 2 == 1;
            View panel = rows.getChildAt(i);
            checkHeightDp(panel, mini ? 26 : 83);
            if (!mini) {
                ImageView image = panel.findViewById(R.id.widget_plot);
                require(image.getVisibility() == View.VISIBLE && image.getHeight() > 0 && image.getDrawable() != null,
                        "compressed default chart remains visible " + i);
            }
        }
    }

    private static View compareDreamWithWidget(Context target, AppWidgetHostView host, PhoneWidgetPresentation dream, JSONObject quote) {
        Bundle options = new Bundle();
        options.putSize("semWidgetSize", new android.util.Size(363, 218));
        options.putInt("semAppWidgetColumnSpan", 4);
        options.putInt("semAppWidgetRowSpan", 3);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 363);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 218);
        RemoteViews frame = PhoneWidgetRenderer.render(target, ID, quote, options, false);
        View lock = measuredPassiveWidget(host, frame, 218);
        int width = lock.getWidth(), height = lock.getHeight();
        // LockStar can allocate a few more physical pixels than the dp size reported to the app.
        layoutExactly(lock, width + 9, height + 7);
        View content = lock.findViewById(R.id.widget_zzzz_presentation_content);
        require(content != null && content.getWidth() == width && content.getHeight() == height,
                "LockStar preserves reported chart dimensions inside its larger host");
        Rect bounds = new Rect();
        content.getDrawingRect(bounds);
        ((ViewGroup) lock).offsetDescendantRectToMyCoords(content, bounds);
        require(bounds.top == 0 && Math.abs(bounds.left + bounds.right - lock.getWidth()) <= 1,
                "LockStar charts align at the top center");
        // Feed the same published frame into the charging service's real content view. The test
        // has its own stock preferences and does not require changing the user's LockStar binding.
        dream.showFrame(frame, 363, 218);
        float density = target.getResources().getDisplayMetrics().density;
        int w = Math.round(411 * density), h = Math.round(891 * density);
        layoutExactly(dream, w, h);
        ViewGroup expected = lock.findViewById(R.id.widget_zz_stock_rows);
        ViewGroup actual = dream.findViewById(R.id.widget_zz_stock_rows);
        require(actual != null && actual.getChildCount() == expected.getChildCount(), "charging dream shows every LockStar row");
        View widget = dream.getChildAt(1);
        require(widget.getScaleY() == 1f && widget.getScaleX() == 1f && widget.getWidth() == width
                        && widget.getHeight() == height, "charging dream preserves the LockStar frame size");
        require(text(dream, R.id.widget_zz_next_page).equals(text(lock, R.id.widget_zz_next_page)), "matching charging page");
        for (int i = 0; i < expected.getChildCount(); i++) {
            View a = expected.getChildAt(i), b = actual.getChildAt(i);
            require(a.getTop() == b.getTop() && a.getHeight() == b.getHeight() && a.getWidth() == b.getWidth(),
                    "charging stock row geometry " + i);
            Bitmap reference = Bitmap.createBitmap(a.getWidth(), a.getHeight(), Bitmap.Config.ARGB_8888);
            Bitmap rendered = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                a.draw(new Canvas(reference));
                b.draw(new Canvas(rendered));
                require(reference.sameAs(rendered), "charging stock row matches LockStar pixels " + i);
            } finally { reference.recycle(); rendered.recycle(); }
        }
        return lock;
    }

    private static void checkTenVisibleCharts(Context target, Context hostContext, SharedPreferences prefs) throws Exception {
        prefs.edit().clear().putBoolean(PhoneDisplayWindow.AUTOMATIC, false).putString(PhoneDisplayWindow.MODE, "1m").commit();
        List<String> stocks = new java.util.ArrayList<>();
        JSONArray bars = new JSONArray();
        int index = 0;
        for (String time : Arrays.asList("09:45", "10:00", "10:30", "11:00", "13:30", "14:00", "14:30", "14:45")) {
            boolean rise = index++ % 2 == 0;
            bars.put(new JSONObject().put("time", "2026-11-05 " + time).put("high", 11).put("low", 10)
                    .put("open", rise ? 10 : 11).put("close", rise ? 11 : 10));
        }
        for (int i = 0; i < 10; i++) {
            String code = "sz" + (200100 + i);
            stocks.add(code);
            JSONObject snapshot = new JSONObject().put("symbol", code).put("name", "压缩测试" + (i + 1))
                    .put("date", "2026-11-05").put("quote_at", "2026-11-05 14:45:00")
                    .put("price", 10).put("previous", 10.25).put("high", 11).put("low", 10).put("bars", bars);
            prefs.edit().putString("snapshot_" + code, snapshot.toString()).apply();
        }
        PhoneDisplayStocks.save(prefs, stocks, Collections.emptySet());
        AppWidgetHostView host = boundHost(target, hostContext);
        RemoteViews frame = PhoneWidgetRenderer.render(target, ID, null, 363, 330, "1m", false);
        View root = measuredPassiveWidget(host, frame, 330);
        ViewGroup rows = root.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 10 && root.findViewById(R.id.widget_zz_next_page).getVisibility() == View.GONE,
                "ten full charts fit in one 330 dp widget");
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            checkHeightDp(row, 33);
            require(row.getBottom() <= rows.getHeight()
                            && row.findViewById(R.id.widget_root).getContentDescription().toString().contains(stocks.get(i).substring(2)),
                    "visible compact quote in stock order " + i);
            ImageView plot = row.findViewById(R.id.widget_plot);
            require(plot.getVisibility() == View.VISIBLE && plot.getHeight() > 0, "compact chart bounds " + i);
            Bitmap rendered = Bitmap.createBitmap(plot.getWidth(), plot.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                plot.draw(new Canvas(rendered));
                require(hasBothCandleColors(rendered), "compact chart draws rising and falling candles " + i);
            } finally { rendered.recycle(); }
        }
    }

    private static boolean hasBothCandleColors(Bitmap rendered) {
        boolean up = false, down = false;
        int[] line = new int[rendered.getWidth()];
        for (int y = 0; y < rendered.getHeight() && !(up && down); y++) {
            rendered.getPixels(line, 0, line.length, 0, y, line.length, 1);
            for (int ink : line) {
                if (Color.alpha(ink) < 70) continue;
                up |= sameInk(ink, PhoneMarketPlot.WIDGET_UP);
                down |= sameInk(ink, PhoneMarketPlot.WIDGET_DOWN);
            }
        }
        return up && down;
    }

    private static boolean sameInk(int actual, int expected) {
        return Math.abs(Color.red(actual) - Color.red(expected)) < 8
                && Math.abs(Color.green(actual) - Color.green(expected)) < 8
                && Math.abs(Color.blue(actual) - Color.blue(expected)) < 8;
    }

    private static View measuredPassiveWidget(Context target, AppWidgetHostView host, JSONObject quote, int heightDp) {
        return measuredPassiveWidget(host, PhoneWidgetRenderer.render(target, ID, quote, 363, heightDp, "realtime", false), heightDp);
    }

    private static View measuredPassiveWidget(AppWidgetHostView host, RemoteViews frame, int heightDp) {
        host.updateAppWidget(frame);
        View root = host.getChildAt(0);
        float density = root.getResources().getDisplayMetrics().density;
        int w = Math.round(363 * density), h = Math.round(heightDp * density);
        layoutExactly(root, w, h);
        requirePassiveTree(root);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, w / 2f, h / 4f, 0);
        try { require(!root.dispatchTouchEvent(down), "resized LockStar chart yields swipe start"); }
        finally { down.recycle(); }
        return root;
    }

    private static void layoutExactly(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void checkHeightDp(View view, int expected) {
        int pixels = Math.round(expected * view.getResources().getDisplayMetrics().density);
        require(Math.abs(view.getHeight() - pixels) <= 1,
                "expected " + expected + "dp (" + pixels + "px), measured " + view.getHeight() + "px");
    }

    private static void checkBoundHostUpgrade(Context target, Context hostContext, int oldId, int shippedId, RemoteViews next) {
        RemoteViews old = new RemoteViews(target.getPackageName(), oldId);
        require(oldId == shippedId && oldId != next.getLayoutId(), "distinct upgrade layout ID");
        AppWidgetHostView host = boundHost(target, hostContext);
        host.updateAppWidget(old);
        View oldRoot = host.getChildAt(0);
        require(oldRoot instanceof ViewGroup, "existing host widget");
        // LockStar already holds the inflated native views when the package changes.
        if (oldId == R.layout.phone_market_widget) {
            ((ViewGroup) oldRoot).addView(new TextClock(target));
        } else {
            TextView legacyRange = new TextView(target);
            legacyRange.setText("日高 38.71    日低 32.17");
            ((ViewGroup) oldRoot).addView(legacyRange);
        }
        host.updateAppWidget(next);
        require(host.getChildAt(0) != oldRoot, "in-place upgrade reinflated RemoteViews");
        requireNoClock(host);
        requireNoRangeLine(host);
        require(text(host, R.id.widget_price).equals("35.82"), "same host showing upgraded market");
        RemoteViews placeholder = PhoneWidgetRenderer.wallpaperPlaceholder(target);
        require(placeholder.getLayoutId() != next.getLayoutId(), "distinct wallpaper-only layout");
        host.updateAppWidget(placeholder);
        View empty = host.getChildAt(0);
        require(empty instanceof FrameLayout && ((FrameLayout)empty).getChildCount() == 0,
                "no stale quote nodes over the interactive lock wallpaper");
        require(!empty.isClickable() && !empty.hasOnClickListeners() && empty.getContentDescription() == null
                && empty.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                "inactive, undecorated placeholder");
        empty.layout(0,0,64,48);
        Bitmap underlay = Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888);
        underlay.eraseColor(Color.MAGENTA);
        empty.draw(new Canvas(underlay));
        int[] colors = new int[64*48];
        underlay.getPixels(colors,0,64,0,0,64,48);
        for (int color : colors) require(color == Color.MAGENTA, "placeholder obscured the wallpaper");
        underlay.recycle();
        host.updateAppWidget(next);
        require(host.getChildAt(0) != empty && text(host,R.id.widget_price).equals("35.82"),
                "AOD market restored on the same host");
        plot(host);
    }

    private static void checkWallpaperVisibility() {
        require(PhoneWallpaperService.shouldCoverWidget(true, true, Display.STATE_ON, true), "interactive lock wallpaper");
        require(!PhoneWallpaperService.shouldCoverWidget(false, true, Display.STATE_ON, true), "wallpaper hidden");
        require(!PhoneWallpaperService.shouldCoverWidget(true, true, Display.STATE_ON, false), "wallpaper on home");
        require(!PhoneWallpaperService.shouldCoverWidget(true, false, Display.STATE_ON, true), "screen going off");
        for (int state : new int[]{Display.STATE_OFF,Display.STATE_DOZE,Display.STATE_DOZE_SUSPEND}) {
            require(!PhoneWallpaperService.shouldCoverWidget(true, true, state, true), "AOD owns the quote at " + state);
        }
    }

    private static void checkPowerRates(Context context, SharedPreferences prefs) {
        prefs.edit().clear().putInt("rate",10).commit();
        BroadcastReceiver[] listener={null};
        Intent battery=new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED,0);
        Context simulated=new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) {
                listener[0]=receiver; return battery;
            }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags) {
                listener[0]=receiver; return battery;
            }
            @Override public void unregisterReceiver(BroadcastReceiver receiver) { listener[0]=null; }
        };
        java.util.List<Integer> delivered=new java.util.ArrayList<>();
        PhoneSamplingRate rate=new PhoneSamplingRate(simulated,prefs,delivered::add);
        require(!prefs.contains("rate") && PhoneSamplingRate.value(prefs,true)==5
                && PhoneSamplingRate.value(prefs,false)==2, "legacy settings get separate defaults");
        rate.start();
        listener[0].onReceive(simulated,battery.putExtra(BatteryManager.EXTRA_PLUGGED,BatteryManager.BATTERY_PLUGGED_USB));
        listener[0].onReceive(simulated,battery);
        prefs.edit().putInt(PhoneSamplingRate.UNPLUGGED,1).putInt(PhoneSamplingRate.PLUGGED,10).commit();
        listener[0].onReceive(simulated,battery.putExtra(BatteryManager.EXTRA_PLUGGED,0));
        rate.stop();
        require(delivered.equals(java.util.Arrays.asList(2,5,10,1)), "live power changes update one demand");
        PhoneSamplingRate.migrate(prefs);
        require(PhoneSamplingRate.value(prefs,true)==10 && PhoneSamplingRate.value(prefs,false)==1
                && listener[0]==null,"preserved custom rates and released observer");
    }

    private static JSONObject fixture() throws Exception {
        JSONArray bars = new JSONArray();
        for (int i = 0; i < 31; i++) {
            double mid = 35.82 + .04 * Math.sin(i * .35);
            bars.put(new JSONObject().put("time", String.format(Locale.ROOT, "2026-11-05 14:%02d", i + 1))
                    .put("open", mid).put("high", mid + .05).put("low", mid - .04).put("close", mid + .02));
        }
        JSONArray levels = new JSONArray().put(new JSONObject().put("price", 35.80).put("side", "bid").put("volume", 1400))
                .put(new JSONObject().put("price", 35.84).put("side", "ask").put("volume", 1300));
        JSONArray fills = new JSONArray().put(new JSONObject().put("bar_time", "2026-11-05 14:08").put("price", 35.84).put("side", "B"))
                .put(new JSONObject().put("bar_time", "2026-11-05 14:21").put("price", 35.87).put("side", "S"));
        return new JSONObject().put("symbol", "sz000063").put("name", "行情测试")
                .put("date", "2026-11-05").put("quote_at", "2026-11-05 14:30:08").put("price", 35.82)
                .put("previous", 35.26).put("high", 38.71).put("low", 32.17).put("bars", bars).put("fills", fills)
                .put("book", new JSONObject().put("source_at", "2026-11-05 14:30:06").put("levels", levels));
    }

    private static void checkHostContent(View widget) {
        requireNoClock(widget);
        require(text(widget, R.id.widget_name).contains("行情测试"), "stock name");
        require("35.82".equals(text(widget, R.id.widget_price)), "quote");
        TextView price = widget.findViewById(R.id.widget_price);
        require(price.getCurrentTextColor() == PhoneMarketPlot.WIDGET_TEXT, "soft white quote");
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            require(price.getTypeface().getWeight() >= 700, "bold quote");
        }
        requireNoRangeLine(widget);
        require(text(widget, R.id.widget_stamp).contains("14:30"), "market source time");
        for (int id : new int[]{R.id.widget_name,R.id.widget_price}) {
            TextView field = widget.findViewById(id);
            require(field.getVisibility() == View.VISIBLE && Color.luminance(field.getCurrentTextColor()) > .32f,
                    "readable market text " + widget.getResources().getResourceEntryName(id));
        }
    }

    private static Bitmap plot(View widget) {
        ImageView plot = widget.findViewById(R.id.widget_plot);
        require(plot.getVisibility() == View.VISIBLE && plot.getDrawable() instanceof BitmapDrawable, "chart bitmap");
        return ((BitmapDrawable) plot.getDrawable()).getBitmap();
    }

    private static void checkDailyLabels(Context context, Context host, View widget, JSONObject data, int w, int h) throws Exception {
        render(context, ID, data, w, h).reapply(host, widget);
        checkHostContent(widget);
        Bitmap original = plot(widget);
        int timePixels = 0;
        for (int y = original.getHeight() * 85 / 100; y < original.getHeight(); y++) {
            for (int x = original.getWidth() / 5; x < original.getWidth(); x++) {
                if (brightText(original.getPixel(x, y))) timePixels++;
            }
        }
        require(timePixels >= 8, "readable time axis at " + w + "x" + h);
        JSONObject changed = new JSONObject(data.toString()).put("high", 39.77);
        render(context, ID, changed, w, h).reapply(host, widget);
        Bitmap newHigh = plot(widget);
        require(changedLabel(original, newHigh, true), "daily high at upper left " + w + "x" + h);
        changed.put("low", 30.10);
        render(context, ID, changed, w, h).reapply(host, widget);
        require(changedLabel(newHigh, plot(widget), false), "daily low at lower left " + w + "x" + h);
    }

    private static boolean changedLabel(Bitmap a, Bitmap b, boolean top) {
        // This fixture has only afternoon prices, so its left corners contain only the overlaid labels.
        // In a compact candle widget the TDW panel can put the low above the bitmap midpoint.
        int y0 = 0;
        int y1 = top ? a.getHeight() / 2 : a.getHeight();
        int changed = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < a.getWidth() / 6; x++) {
                if (brightText(a.getPixel(x,y)) != brightText(b.getPixel(x,y))) changed++;
            }
        }
        return changed >= 6;
    }

    private static void checkSessionPositions(Context context, Context host, View widget, SharedPreferences prefs,
                                              JSONObject base, int w, int h) throws Exception {
        android.util.Log.i("PhoneRegression", "positions " + w + "x" + h + " " + prefs.getString("chart_mode", ""));
        JSONArray bars = new JSONArray(), withoutProbes = new JSONArray();
        for (String time : new String[]{"09:31", "10:30", "13:01"}) bars.put(positionBar(time));
        // Keep more than 60 subsequent candles. A rolling 60-minute viewport loses all three probes.
        for (int i = 2; i <= 75; i++) {
            JSONObject bar = positionBar(String.format(Locale.ROOT, "%02d:%02d", 13+i/60, i%60));
            bars.put(bar);
            withoutProbes.put(bar);
        }
        JSONObject tradingDay = new JSONObject(base.toString()).put("bars", bars);
        render(context, ID, tradingDay, w, h).reapply(host, widget);
        Bitmap full = plot(widget);
        // Capture the literal text and its alignment through the production Canvas draw path.
        int plotWidth = w < 205 ? 450 : 650;
        int plotHeight = Math.round(plotWidth * full.getHeight() / (float)full.getWidth());
        AxisLabels axis = new AxisLabels(plotWidth);
        new PhoneChartOptions(prefs).buildWidget(tradingDay).drawWidget(axis, 0, 0, plotWidth, plotHeight);
        require(axis.openX == 0 && Math.abs(axis.closeX - axis.gridRight) < 1 && axis.closeX < axis.priceLeft,
                "15:00 ends at the grid before the price column " + w + "x" + h);
        require(axis.highX >= 0 && axis.highX <= plotWidth*.03 && axis.lowX >= 0 && axis.lowX <= plotWidth*.03
                && axis.highY > 0 && axis.highY < axis.lowY && axis.lowY <= axis.timeY,
                "daily numbers inside the plotting bounds " + w + "x" + h);
        for (int i = 0; i <= 4; i++)
            require(axis.gridLines.contains(Math.round(axis.closeX * i / 4)), "trading hour grid " + i);
        require(axis.rightPrice, "right-side price ticks " + w + "x" + h);

        tradingDay.put("bars", withoutProbes);
        render(context, ID, tradingDay, w, h).reapply(host, widget);
        Bitmap omitted = plot(widget);
        // TDW legitimately changes when its input history changes. Compare only the price panel.
        require(Float.isFinite(axis.tdwTop), "TDW panel boundary recorded");
        // Use the upper price band: small host bitmap rounding and label outlines may cross
        // the separator, while all three fixed-price probes intersect this band.
        int priceBottom = Math.min((int)(axis.tdwTop * full.getHeight() / plotHeight), full.getHeight() * 45 / 100);
        require(differsInBand(full, omitted, 0, .025, priceBottom), "opening price under left corner labels");
        float plotFraction = axis.gridRight / plotWidth;
        require(differsInBand(full, omitted, .235 * plotFraction, .265 * plotFraction, priceBottom), "10:30 price at one-quarter session");
        require(differsInBand(full, omitted, .485 * plotFraction, .515 * plotFraction, priceBottom), "afternoon open at half session");
        try(java.io.FileOutputStream out=context.openFileOutput("regression-position-full.png",Context.MODE_PRIVATE)) {full.compress(Bitmap.CompressFormat.PNG,100,out);}
        try(java.io.FileOutputStream out=context.openFileOutput("regression-position-omitted.png",Context.MODE_PRIVATE)) {omitted.compress(Bitmap.CompressFormat.PNG,100,out);}
        require(!differsInBand(full, omitted, .30 * plotFraction, .45 * plotFraction, priceBottom)
                && !differsInBand(full, omitted, .60 * plotFraction, .95 * plotFraction, priceBottom),
                "removing three timestamps must not slide the other prices " + w + "x" + h + " " + prefs.getString("chart_mode", "") + " priceBottom="+priceBottom+" plotFraction="+plotFraction);
    }

    private static JSONObject positionBar(String time) throws Exception {
        return new JSONObject().put("time","2026-11-05 " + time).put("open",35.32).put("high",37.60)
                .put("low",34.20).put("close",35.82);
    }

    private static boolean differsInBand(Bitmap a, Bitmap b, double left, double right, int bottom) {
        int changed = 0;
        // Skia can vary a couple of antialias pixels when an earlier path segment is removed.
        // A shifted price or missing probe changes a visible cluster, not two edge pixels.
        for (int y=0; y<Math.min(bottom, a.getHeight()); y++) {
            for (int x=(int)(a.getWidth()*left); x<a.getWidth()*right; x++) {
                int first=a.getPixel(x,y), second=b.getPixel(x,y);
                int delta=Math.max(Math.abs(Color.red(first)-Color.red(second)),
                        Math.max(Math.abs(Color.green(first)-Color.green(second)),Math.abs(Color.blue(first)-Color.blue(second))));
                if (delta>24 && ++changed>=4) return true;
            }
        }
        return false;
    }

    private static final class AxisLabels extends Canvas {
        final int width;
        final java.util.Set<Integer> gridLines = new java.util.HashSet<>();
        float tdwTop = Float.NaN;
        float gridRight = Float.NaN, priceLeft = Float.POSITIVE_INFINITY;
        boolean rightPrice;
        float shortDepth = Float.POSITIVE_INFINITY, longDepth;
        float openX = Float.NaN, closeX = Float.NaN, timeY = Float.NaN;
        float highX = Float.NaN, lowX = Float.NaN, highY = Float.NaN, lowY = Float.NaN;

        AxisLabels(int width) { this.width = width; }

        @Override public void drawLine(float x0, float y0, float x1, float y1, Paint paint) {
            int color = paint.getColor();
            if (color == 0xff777777 && y0 == y1 && x1 - x0 >= width * .55f) tdwTop = y0;
            boolean neutral = Math.abs(Color.red(color)-Color.green(color)) <= 24
                    && Math.abs(Color.blue(color)-Color.green(color)) <= 24;
            if (neutral && x0 == x1 && Math.abs(y1-y0) > 10) {
                gridLines.add(Math.round(x0));
            }
            if (neutral && y0 == y1 && Math.abs(x1-x0) >= width*.55f) gridRight = x1;
            if (((color & 0xffffff) == (PhoneMarketPlot.WIDGET_UP & 0xffffff)
                    || (color & 0xffffff) == (PhoneMarketPlot.WIDGET_DOWN & 0xffffff))
                    && y0 == y1 && x1 > width*.55f && x1 > x0) {
                shortDepth = Math.min(shortDepth, x1-x0);
                longDepth = Math.max(longDepth, x1-x0);
            }
        }

        @Override public void drawText(String text, float x, float y, Paint paint) {
            if ("09:30".equals(text) && paint.getTextAlign() == Paint.Align.LEFT) { openX = x; timeY = y; }
            if ("15:00".equals(text) && paint.getTextAlign() == Paint.Align.RIGHT) closeX = x;
            if ("38.71".equals(text) && paint.getTextAlign() == Paint.Align.LEFT) { highX = x; highY = y; }
            if ("32.17".equals(text) && paint.getTextAlign() == Paint.Align.LEFT) { lowX = x; lowY = y; }
            if (paint.getTextAlign() == Paint.Align.RIGHT && x >= width*.95f && text.matches("[0-9]+\\.[0-9]{2}")) {
                rightPrice = true;
                priceLeft = Math.min(priceLeft, x - paint.measureText(text));
            }
        }
    }

    private static void checkDepthScale(SharedPreferences prefs, JSONObject base) throws Exception {
        JSONObject data = new JSONObject(base.toString());
        data.remove("fills");
        JSONArray levels = new JSONArray()
                .put(new JSONObject().put("price",34.5).put("side","bid").put("volume",1000))
                .put(new JSONObject().put("price",36.5).put("side","ask").put("volume",10));
        data.getJSONObject("book").put("levels",levels);
        AxisLabels canvas = new AxisLabels(450);
        new PhoneChartOptions(prefs).buildWidget(data).drawWidget(canvas,0,0,450,160);
        require(canvas.longDepth > 0 && Math.abs(canvas.shortDepth/canvas.longDepth - .01f) < .001f,
                "L2 widths preserve volume ratio including a one-percent order");
    }

    private static boolean brightText(int pixel) {
        int min = Math.min(Color.red(pixel), Math.min(Color.green(pixel), Color.blue(pixel)));
        int max = Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
        return min >= 167 && max - min <= 48;
    }

    private static boolean hasYellow(Bitmap plot) {
        for (int y=0; y<plot.getHeight(); y++) for (int x=0; x<plot.getWidth(); x++) {
            int pixel=plot.getPixel(x,y);
            if (Color.red(pixel)>185 && Color.green(pixel)>150 && Color.green(pixel)-Color.blue(pixel)>65)
                return true;
        }
        return false;
    }

    private static void requireNoRangeLine(View view) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            String value = ((TextView)view).getText().toString();
            require(!value.contains("日高") && !value.contains("日低") && !value.contains("38.71") && !value.contains("32.17"),
                    "daily numbers must be inside the chart");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0; i<group.getChildCount(); i++) requireNoRangeLine(group.getChildAt(i));
        }
    }

    private static void requireNoClock(View view) {
        require(!(view instanceof TextClock), "duplicate system clock/date");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0; i<group.getChildCount(); i++) requireNoClock(group.getChildAt(i));
        }
    }

    private static String text(View widget, int id) {
        TextView field = widget.findViewById(id);
        require(field != null, "missing widget field " + id);
        return field.getText().toString();
    }

    private static void require(boolean ok, String detail) {
        if (!ok) throw new AssertionError("widget: " + detail);
    }
}
