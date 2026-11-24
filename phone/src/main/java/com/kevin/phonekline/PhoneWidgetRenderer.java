package com.kevin.phonekline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PhoneWidgetRenderer {
    // New layouts sort after shipped resources, preserving their IDs for cached LockStar hosts.
    // The new single layout also forces reinflation after child IDs change in this upgrade.
    private static final DateTimeFormatter MARKET_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

    private PhoneWidgetRenderer() {}

    static RemoteViews wallpaperPlaceholder(Context context) {
        return new RemoteViews(context.getPackageName(), R.layout.phone_market_widget_wallpaper);
    }

    static RemoteViews render(Context context, int widgetId, JSONObject snapshot, Bundle options, boolean allowRotation) {
        boolean desktop = isDesktopWidget(options);
        int width = width(context, options), height = height(context, options);
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        List<PhoneStockLayout.Page> pages = pages(context, prefs, height);
        if (allowRotation && !desktop) advancePassivePageIfDue(context, prefs, widgetId, pages.size());
        RemoteViews rows = render(context, widgetId, snapshot, width, height, PhoneLiveSchedule.displayPeriod(context), desktop, pages);
        return PhoneMarketWidgetProvider.isLockStarHost(options) ? lockStarPresentation(context, rows, width, height) : rows;
    }

    static RemoteViews lockStarPresentation(Context context, RemoteViews rows, int widthDp, int heightDp) {
        RemoteViews frame = new RemoteViews(context.getPackageName(), R.layout.phone_market_widget_zzzz_lockstar_frame);
        // One UI rounds the grid's outer bounds differently from converting its reported dp size.
        // Give both hosts the same inner pixel rectangle; the system still owns the entire outer slot.
        float density = context.getResources().getDisplayMetrics().density;
        frame.setViewPadding(R.id.widget_zzzz_presentation_measure, Math.round(widthDp * density),
                Math.round(heightDp * density), 0, 0);
        frame.removeAllViews(R.id.widget_zzzz_presentation_content);
        frame.addView(R.id.widget_zzzz_presentation_content, rows);
        return frame;
    }

    static boolean advancePassivePageIfDue(Context context, int id, Bundle options) {
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        return advancePassivePageIfDue(context, prefs, id, pages(context, prefs, height(context, options)).size());
    }

    private static boolean advancePassivePageIfDue(Context context, SharedPreferences prefs, int id, int count) {
        passivePages.watch(context);
        return passivePages.update(prefs, id, count, SystemClock.elapsedRealtime(),
                context.getSystemService(PowerManager.class).isInteractive());
    }

    private static Bundle options(Context context, int id) {
        return AppWidgetManager.getInstance(context).getAppWidgetOptions(id);
    }

    private static boolean landscape(Context context) {
        return context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    static int widgetWidth(Context context, int id) { return width(context, options(context, id)); }
    static int widgetHeight(Context context, int id) { return height(context, options(context, id)); }

    private static int width(Context context, Bundle options) {
        boolean landscape = landscape(context);
        return dimension(options, landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 280);
    }

    private static int height(Context context, Bundle options) {
        boolean landscape = landscape(context);
        return dimension(options, landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 210);
    }

    static final String PAGE = "widget_stock_page_";
    private static final long PASSIVE_PAGE_MS = 30_000;
    static final PassivePages passivePages = new PassivePages();
    private static final int MINI_HEIGHT = 26, PAGE_HEIGHT = 24;

    private static List<PhoneStockLayout.Page> pages(Context context, SharedPreferences prefs, int height) {
        float density = context.getResources().getDisplayMetrics().density;
        return PhoneStockLayout.pages(PhoneDisplayStocks.symbols(prefs), PhoneDisplayStocks.minis(prefs),
                code -> Math.round(PhoneDisplayStocks.fullHeightDp(prefs, code) * density),
                Math.round(height * density), Math.round(MINI_HEIGHT * density), Math.round(PAGE_HEIGHT * density),
                Math.round(PhoneDisplayStocks.DEFAULT_FULL_HEIGHT_DP * density), Math.round(PhoneStockLayout.MIN_FULL_HEIGHT_DP * density));
    }

    static List<String> visibleStocks(Context context, SharedPreferences prefs, int id) {
        List<PhoneStockLayout.Page> pages = pages(context, prefs, height(context, options(context, id)));
        return pages.get(Math.floorMod(prefs.getInt(PAGE + id, 0), pages.size())).stocks;
    }

    // LockStar presents the same widget on AOD and the lit lock screen. Keep its selected page
    // across that handoff and across process death. Only its current screen's reading timer resets.
    static final class PassivePages extends BroadcastReceiver {
        private final Map<Integer, Long> shownSince = new HashMap<>();
        private boolean interactive;
        private Context app;

        void watch(Context context) {
            if (app != null) return;
            app = context.getApplicationContext();
            IntentFilter changes = new IntentFilter(Intent.ACTION_SCREEN_ON);
            changes.addAction(Intent.ACTION_SCREEN_OFF);
            app.registerReceiver(this, changes);
        }

        @Override public void onReceive(Context context, Intent intent) {
            // A whole off/on cycle can occur between quotes, including a deferred broadcast.
            interactive = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            shownSince.clear();
        }

        boolean update(SharedPreferences prefs, int id, int count, long now, boolean screenInteractive) {
            // Sampling also catches a render delivered before the corresponding screen broadcast.
            if (interactive != screenInteractive) {
                interactive = screenInteractive;
                shownSince.clear();
            }
            if (count <= 1) {
                forget(id);
                return false;
            }
            Long since = shownSince.get(id);
            if (since == null || now < since) {
                shownSince.put(id, now);
                return false;
            }
            if (now - since < PASSIVE_PAGE_MS) return false;
            shownSince.put(id, now);
            int next = (Math.floorMod(prefs.getInt(PAGE + id, 0), count) + 1) % count;
            prefs.edit().putInt(PAGE + id, next).apply();
            return true;
        }

        void forget(int id) { shownSince.remove(id); }

        void stop() {
            if (app != null) app.unregisterReceiver(this);
            app = null;
            shownSince.clear();
        }
    }

    static RemoteViews render(Context context, int widgetId, JSONObject snapshot, int width, int height) {
        return render(context, widgetId, snapshot, width, height, PhoneLiveSchedule.displayPeriod(context));
    }

    static RemoteViews render(Context context, int widgetId, JSONObject snapshot, int width, int height, String period) {
        return render(context, widgetId, snapshot, width, height, period, isDesktopWidget(options(context, widgetId)));
    }

    static RemoteViews render(Context context, int widgetId, JSONObject snapshot, int width, int height,
                              String period, boolean desktop) {
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        return render(context, widgetId, snapshot, width, height, period, desktop, pages(context, prefs, height));
    }

    private static RemoteViews render(Context context, int widgetId, JSONObject snapshot, int width, int height,
                                      String period, boolean desktop, List<PhoneStockLayout.Page> pages) {
        PendingIntent open = desktop ? openApp(context, widgetId) : null;
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        int index = Math.floorMod(prefs.getInt(PAGE + widgetId, 0), pages.size());
        PhoneStockLayout.Page page = pages.get(index);
        List<String> stocks = page.stocks;
        Set<String> minis = PhoneDisplayStocks.minis(prefs);
        boolean paged = pages.size() > 1;
        if (!paged && stocks.size() <= 2 && java.util.Collections.disjoint(stocks, minis)
                && stocks.stream().allMatch(code -> PhoneDisplayStocks.fullHeightDp(prefs, code) == 0)) {
            if (stocks.size() == 1) return renderStock(context,open,snapshot,width,height,period,stocks.get(0),false);
            if (stocks.size() == 2) return renderDual(context,open,snapshot,
                    PhoneMarketWidgetProvider.latest(context,stocks.get(1)),width,height,period);
        }
        float density = context.getResources().getDisplayMetrics().density;
        RemoteViews stack = new RemoteViews(context.getPackageName(), desktop ? R.layout.phone_market_widget_zzstack
                : R.layout.phone_market_widget_zzz_passive_stack);
        stack.removeAllViews(R.id.widget_zz_stock_rows);
        for (int i = 0; i < stocks.size(); i++) {
            String code = stocks.get(i);
            JSONObject data = snapshot != null && code.equals(snapshot.optString("symbol")) ? snapshot
                    : PhoneMarketWidgetProvider.latest(context, code);
            RemoteViews row;
            if (minis.contains(code)) {
                PhoneMiniRow mini = new PhoneMiniRow(prefs, code, data);
                int pixels = Math.max(1, Math.min(900, Math.round(width * density)));
                int h = Math.max(1, Math.round(pixels * MINI_HEIGHT / (float) Math.max(1, width)));
                Bitmap bitmap = Bitmap.createBitmap(pixels, h, Bitmap.Config.ARGB_8888);
                bitmap.setDensity(Bitmap.DENSITY_NONE);
                mini.draw(new Canvas(bitmap), 2, 0, pixels - 4, h);
                row = new RemoteViews(context.getPackageName(), R.layout.phone_market_widget_zzmini);
                row.setImageViewBitmap(R.id.widget_zz_mini_row, bitmap);
                row.setContentDescription(R.id.widget_zz_mini_row, mini.description);
                if (open != null) row.setOnClickPendingIntent(R.id.widget_zz_mini_row, open);
            } else {
                int pixels = page.heights[i];
                int fullHeight = Math.max(1, Math.round(pixels / density));
                row = new RemoteViews(context.getPackageName(), R.layout.phone_market_widget_zzzz_fixed_panel);
                row.setViewPadding(R.id.widget_zzz_panel_measure, 0, pixels, 0, 0);
                row.removeAllViews(R.id.widget_zz_stock_panel);
                row.addView(R.id.widget_zz_stock_panel, renderStock(context, open, data, width, fullHeight, period, code, stocks.size() > 1));
            }
            stack.addView(R.id.widget_zz_stock_rows, row);
        }
        show(stack, R.id.widget_zz_next_page, paged);
        if (paged) {
            stack.setTextViewText(R.id.widget_zz_next_page, (index + 1) + " / " + pages.size() + (desktop ? "　下一页 ›" : ""));
            if (desktop) stack.setOnClickPendingIntent(R.id.widget_zz_next_page, broadcast(context, widgetId, PhoneMarketWidgetProvider.NEXT_PAGE));
        }
        return stack;
    }

    static RemoteViews renderDual(Context context, int widgetId, JSONObject upper, JSONObject lower,
                                  int width, int height, String period) {
        PendingIntent open = isDesktopWidget(options(context, widgetId)) ? openApp(context, widgetId) : null;
        return renderDual(context, open, upper, lower, width, height, period);
    }

    private static RemoteViews renderDual(Context context, PendingIntent open, JSONObject upper, JSONObject lower,
                                          int width, int height, String period) {
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        RemoteViews views = new RemoteViews(context.getPackageName(), open == null ? R.layout.phone_market_widget_zzz_passive_dual
                : R.layout.phone_market_widget_zdual);
        int panelHeight = Math.max(1,(height-1)/2);
        views.removeAllViews(R.id.widget_upper_slot);
        views.removeAllViews(R.id.widget_lower_slot);
        views.addView(R.id.widget_upper_slot,renderStock(context,open,upper,width,panelHeight,period,
                prefs.getString("symbol","sz000063"),true));
        views.addView(R.id.widget_lower_slot,renderStock(context,open,lower,width,panelHeight,period,
                PhoneDisplayStocks.second(prefs),true));
        return views;
    }

    private static RemoteViews renderStock(Context context, PendingIntent open, JSONObject snapshot, int width, int height,
                                          String period, String selected, boolean dual) {
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        String symbol = PhoneRealtimeCandles.normalize(selected);
        if (symbol.isEmpty()) symbol = "sz000063";
        if (snapshot != null && !symbol.equals(PhoneRealtimeCandles.normalize(snapshot.optString("symbol")))) snapshot = null;
        if ("1d".equals(period)) {
            try {
                JSONObject history = new JSONObject(prefs.getString("daily_snapshot_" + symbol, "{}"));
                snapshot = symbol.equals(history.optString("symbol"))
                        ? PhoneDailyCandles.merge(history, snapshot) : null;
            } catch (Exception unavailable) { snapshot = null; }
            if (snapshot == null) {
                snapshot = new JSONObject();
                try { snapshot.put("symbol", symbol).put("period", "1d"); }
                catch (org.json.JSONException ignored) { }
            }
        }
        PhoneSignalStore.migrate(prefs,symbol);
        snapshot = PhoneSignalStore.restore(prefs,snapshot);
        PhoneChartOptions chartOptions = new PhoneChartOptions(prefs, period);
        PhoneMarketPlot plot = chartOptions.buildWidget(snapshot);
        String overlays = chartOptions.overlays(plot);
        String chartDescription = chartOptions.legend(plot) + (overlays.isEmpty() ? "" : " · " + overlays);
        String code = symbol.substring(2);
        String name = snapshot == null ? "" : snapshot.optString("name");
        if (name.isEmpty() || "null".equals(name) || name.equals(code) || name.equals(symbol)) {
            name = prefs.getString("stock_name_" + symbol, "");
        }
        if ("null".equals(name) || name.equals(code) || name.equals(symbol)) name = "";

        double last = positive(snapshot, "price");
        if (!Double.isFinite(last)) last = plot.lastClose();
        boolean priced = Double.isFinite(last);
        boolean hasPoints = Double.isFinite(plot.lastClose());
        String price = PhoneMarketPlot.format(last);
        double change = (last / positive(snapshot, "previous") - 1) * 100;
        double dayHigh = positive(snapshot, "high"), dayLow = positive(snapshot, "low");
        boolean compact = dual || width < 205 || height < 125;
        // Keep the plot visible when several full charts share a short slot.
        boolean singleLineHeader = height < 58;
        boolean showChange = !singleLineHeader && Double.isFinite(change);
        String stamp = freshness(snapshot, priced, hasPoints, plot.hasBook(), compact, chartOptions.title());
        if (prefs.getBoolean("paused", false)) stamp = "已暂停 · " + stamp;

        int padding = singleLineHeader ? 1 : compact ? 3 : 7;
        int innerWidth = Math.max(1, width - 2 * padding);
        int lineSize = compact ? 10 : 13;
        int nameSize = compact ? 12 : 16;
        int changeSize = compact ? 11 : 16;
        // Keep room for the stock name even when a four-digit quote replaces a two-digit quote.
        int priceSize = Math.max(singleLineHeader ? 12 : 14, Math.min(Math.min(height / 3, compact ? 32 : 48),
                innerWidth / price.length()));
        boolean showStamp = height >= 74 || (!hasPoints && !singleLineHeader);
        boolean showLegend = hasPoints && height >= 143 && innerWidth >= 200;
        int headerHeight = (int)Math.ceil(1.25 * Math.max(priceSize, nameSize + (showChange ? changeSize : 0)));
        int lines = (showLegend ? 1 : 0) + (showStamp ? 1 : 0);
        int chartHeight = height - 2 * padding - headerHeight - (int)Math.ceil(lines * lineSize * 1.3);
        boolean showChart = (hasPoints || Double.isFinite(dayHigh) || Double.isFinite(dayLow))
                && innerWidth >= 90 && chartHeight >= (singleLineHeader ? 12 : 21);

        RemoteViews views = new RemoteViews(context.getPackageName(), open == null ? R.layout.phone_market_widget_zzz_passive_single
                : R.layout.phone_market_widget_zsingle);
        views.setInt(R.id.widget_root, "setBackgroundColor", PhoneMarketPlot.WIDGET_BACKGROUND);
        for (int id : new int[]{R.id.widget_price, R.id.widget_name, R.id.widget_code,
                R.id.widget_change, R.id.widget_chart_legend, R.id.widget_stamp}) {
            views.setTextColor(id, PhoneMarketPlot.WIDGET_TEXT);
        }
        float density = context.getResources().getDisplayMetrics().density;
        int inset = Math.round(padding * density);
        views.setViewPadding(R.id.widget_root, inset, inset, inset, inset);
        size(views, R.id.widget_price, priceSize);
        size(views, R.id.widget_name, nameSize);
        size(views, R.id.widget_code, lineSize);
        size(views, R.id.widget_change, changeSize);
        size(views, R.id.widget_chart_legend, lineSize);
        size(views, R.id.widget_stamp, lineSize);
        views.setTextViewText(R.id.widget_price, price);
        views.setTextViewText(R.id.widget_name, name.isEmpty() ? code : name);
        String changeText = Double.isFinite(change) ? String.format(Locale.ROOT, "%+.2f%%", change) : "";
        int changeInk = change > 0 ? PhoneMarketPlot.WIDGET_UP : change < 0 ? PhoneMarketPlot.WIDGET_DOWN : PhoneMarketPlot.WIDGET_TEXT;
        boolean inlineChange = singleLineHeader && !changeText.isEmpty();
        views.setTextViewText(R.id.widget_code, inlineChange ? changeText : code);
        if (inlineChange) views.setTextColor(R.id.widget_code, changeInk);
        show(views, R.id.widget_code, inlineChange || (!name.isEmpty() && innerWidth >= 205));
        views.setTextViewText(R.id.widget_change, changeText);
        views.setTextColor(R.id.widget_change, changeInk);
        show(views, R.id.widget_change, showChange);
        views.setTextViewText(R.id.widget_chart_legend, chartDescription);
        show(views, R.id.widget_chart_legend, showChart && showLegend);
        show(views, R.id.widget_plot, showChart);
        if (showChart) {
            views.setContentDescription(R.id.widget_plot, chartDescription);
            views.setImageViewBitmap(R.id.widget_plot, chart(plot, innerWidth, chartHeight, density));
        }
        views.setTextViewText(R.id.widget_stamp, stamp);
        show(views, R.id.widget_stamp, showStamp);
        views.setContentDescription(R.id.widget_root, (name.isEmpty() ? "" : name + " ") + code + "，现价 "
                + price + "，当日最高价 " + PhoneMarketPlot.format(dayHigh) + "，当日最低价 " + PhoneMarketPlot.format(dayLow)
                + "，" + chartDescription + "，" + stamp);
        if (open != null) views.setOnClickPendingIntent(R.id.widget_root, open);
        return views;
    }

    private static PendingIntent broadcast(Context context, int id, String action) {
        Intent intent = new Intent(context, PhoneMarketWidgetProvider.class).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent openApp(Context context, int id) {
        return PendingIntent.getActivity(context, id, new Intent(context, PhoneDialActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean isDesktopWidget(Bundle host) {
        if (host == null) return false;
        int category = host.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY);
        if ((category & android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) != 0) return false;
        if (host.containsKey("hsWidgetDisplayId") || host.containsKey("semHostType")) return true;
        return !"samsung".equalsIgnoreCase(android.os.Build.MANUFACTURER) && !host.containsKey("semWidgetSize")
                && category == android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN;
    }

    private static Bitmap chart(PhoneMarketPlot plot, int widthDp, int heightDp, float density) {
        // Draw at the aspect ratio of the chart slot. Limit pixel memory sent to the widget host.
        int designWidth = Math.max(450, Math.min(900, Math.round(widthDp * 2.1f)));
        int designHeight = Math.max(90, Math.round(designWidth * (heightDp / (float)widthDp)));
        float scale = Math.min(widthDp * density / designWidth, (float)Math.sqrt(400_000.0 / designWidth / designHeight));
        int pixelsWide = Math.max(1, Math.round(designWidth * scale));
        int pixelsHigh = Math.max(1, Math.round(designHeight * scale));
        Bitmap bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(bitmap);
        // Keep untouched ARGB pixels transparent so LockStar's wallpaper shows through.
        canvas.scale(pixelsWide / (float)designWidth, pixelsHigh / (float)designHeight);
        plot.drawWidget(canvas, 0, 0, designWidth - 1, designHeight - 1);
        return bitmap;
    }

    private static String freshness(JSONObject snapshot, boolean priced, boolean hasPoints, boolean hasBook,
                                    boolean compact, String chartTitle) {
        if (!priced) return "暂无报价 · 等待" + chartTitle;
        LocalDateTime at = PhoneRealtimeCandles.localTime(snapshot.optString("quote_at"));
        String stamp = at == null ? "快照 · 时间未知" : "快照 " + MARKET_TIME.format(at);
        JSONObject depth = hasBook ? snapshot.optJSONObject("book") : null;
        LocalDateTime book = depth == null ? null : PhoneRealtimeCandles.localTime(depth.optString("source_at"));
        if (book != null && at != null) {
            if (compact && at.toLocalDate().equals(PhoneRealtimeCandles.now().toLocalDate())) {
                stamp = "价 " + CLOCK.format(at) + "  盘 " + CLOCK.format(book);
            } else {
                stamp = MARKET_TIME.format(at) + (compact ? "  盘 " : " · 盘口 ") + CLOCK.format(book);
            }
        }
        if (!hasPoints) stamp += " · 暂无" + chartTitle;
        return stamp;
    }

    private static double positive(JSONObject snapshot, String key) {
        double value = snapshot == null ? Double.NaN : snapshot.optDouble(key);
        return PhoneRealtimeCandles.positive(value) ? value : Double.NaN;
    }

    private static int dimension(Bundle options, String primary, String secondary, int fallback) {
        if (options == null) return fallback;
        int value = options.getInt(primary);
        if (value <= 0) value = options.getInt(secondary);
        return value > 0 ? value : fallback;
    }

    private static void show(RemoteViews views, int id, boolean visible) {
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
    }

    private static void size(RemoteViews views, int id, int dp) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dp);
    }
}
