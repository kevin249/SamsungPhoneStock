package com.kevin.phonekline;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

/** The system owns the widget surface, including copies placed on AOD by LockStar. */
public final class PhoneMarketWidgetProvider extends AppWidgetProvider {
    private static final long VISIBLE_UPDATE_MS = 15_000;
    private static final String CACHE = "widget_candles";
    private static JSONObject last;
    private static long nextVisibleUpdate, nextStreamCache;
    // null after process death: the next wallpaper-state check reconciles the host's cached layout.
    private static Boolean wallpaperCoversWidget;

    private static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, PhoneMarketWidgetProvider.class));
    }

    static boolean hasWidgets(Context context) {
        return widgetIds(context).length > 0;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
    }

    static JSONObject latest(Context context) {
        SharedPreferences settings = prefs(context);
        String symbol = settings.getString("symbol", "sz000063");
        if (last != null && symbol.equals(last.optString("symbol"))) return last;
        JSONObject widget = read(settings, CACHE, symbol);
        JSONObject app = read(settings, "snapshot_" + symbol, symbol);
        if (widget == null) return app;
        return app != null && app.optString("quote_at").compareTo(widget.optString("quote_at")) > 0 ? app : widget;
    }

    private static JSONObject read(SharedPreferences settings, String key, String symbol) {
        try {
            JSONObject saved = new JSONObject(settings.getString(key, "{}"));
            return symbol.equals(saved.optString("symbol")) ? saved : null;
        } catch (JSONException invalidCache) {
            return null;
        }
    }

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        render(context, manager, ids, latest(context));
        PhoneWidgetRefreshService.schedule(context, true);
        PhoneLiveSchedule.armNext(context);
        PhoneLiveSchedule.syncFromDisplay(context);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        render(context, manager, new int[]{id}, latest(context));
    }

    @Override public void onDisabled(Context context) {
        PhoneWidgetRefreshService.cancel(context);
        last = null;
        nextVisibleUpdate = 0;
        prefs(context).edit().remove(CACHE).apply();
        PhoneLiveSchedule.armNext(context);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids, JSONObject quote) {
        if (Boolean.TRUE.equals(wallpaperCoversWidget)) {
            manager.updateAppWidget(ids, PhoneWidgetRenderer.wallpaperPlaceholder(context));
            return;
        }
        for (int id : ids) {
            manager.updateAppWidget(id, PhoneWidgetRenderer.render(context, id, quote));
        }
    }

    static void setWallpaperCover(Context context, boolean covered) {
        if (Boolean.valueOf(covered).equals(wallpaperCoversWidget)) return;
        wallpaperCoversWidget = covered;
        updateTheme(context);
    }

    static void updateTheme(Context context) {
        int[] ids = widgetIds(context);
        if (ids.length > 0) render(context, AppWidgetManager.getInstance(context), ids, latest(context));
    }

    static void settingsChanged(Context context, boolean clearQuote) {
        if (clearQuote) {
            last = null;
            prefs(context).edit().remove(CACHE).apply();
        }
        nextVisibleUpdate = 0;
        PhoneWidgetRefreshService.cancel(context);
        updateTheme(context);
        if (hasWidgets(context) && !prefs(context).getBoolean("paused", false)) {
            PhoneWidgetRefreshService.schedule(context, true);
        }
        PhoneLiveSchedule.armNext(context);
    }

    static void visibleQuote(Context context, JSONObject quote) {
        if (PhoneLiveMarketService.isStreaming()) return;
        long now = SystemClock.elapsedRealtime();
        if (now < nextVisibleUpdate || quote == null || quote.optJSONArray("bars") == null) return;
        nextVisibleUpdate = now + VISIBLE_UPDATE_MS;
        publish(context, quote);
    }

    static void publish(Context context, JSONObject quote) {
        if (PhoneLiveMarketService.isStreaming()) return;
        update(context, quote, false);
    }

    static void publishStream(Context context, JSONObject quote) {
        update(context, quote, true);
    }

    private static void update(Context context, JSONObject quote, boolean stream) {
        if (quote == null || quote.optJSONArray("bars") == null) return;
        SharedPreferences settings = prefs(context);
        if (settings.getBoolean("paused", false) ||
                !settings.getString("symbol", "sz000063").equals(quote.optString("symbol"))) return;
        int[] ids = widgetIds(context);
        if (ids.length == 0) return;
        JSONObject previous = latest(context);
        if (previous != null && previous.optString("quote_at").compareTo(quote.optString("quote_at")) > 0) return;
        boolean newStock = last == null || !last.optString("symbol").equals(quote.optString("symbol"));
        last = quote;
        // Keep accepting snapshots behind the wallpaper. A single full frame restores the latest
        // chart on screen-off or unlock instead of publishing an empty layout on every tick.
        if (!Boolean.TRUE.equals(wallpaperCoversWidget)) {
            render(context, AppWidgetManager.getInstance(context), ids, quote);
        }
        long now = SystemClock.elapsedRealtime();
        if (stream && !newStock && now < nextStreamCache) return;
        nextStreamCache = now + 30_000L;
        // Preserve public candles across process death. Signals and depth stay in memory.
        JSONObject candles = new JSONObject();
        for (String key : new String[]{"symbol", "name", "source", "date", "bars", "quote_at",
                "price", "previous", "high", "low"}) {
            try {
                if (quote.has(key)) candles.put(key, quote.get(key));
            } catch (JSONException invalidQuote) {
                return;
            }
        }
        settings.edit().putString(CACHE, candles.toString()).apply();
    }
}
