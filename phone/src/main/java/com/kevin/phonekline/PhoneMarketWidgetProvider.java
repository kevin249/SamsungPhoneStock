package com.kevin.phonekline;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

/** The system owns the widget surface, including copies placed on AOD by LockStar. */
public final class PhoneMarketWidgetProvider extends AppWidgetProvider {
    private static final long VISIBLE_UPDATE_MS = 15_000;
    private static final String CACHE = "widget_candles";
    private static final java.util.Map<String,JSONObject> minutes = new java.util.HashMap<>();
    private static final java.util.Map<String,JSONObject> daily = new java.util.HashMap<>();
    private static final java.util.Map<String,Long> nextVisible = new java.util.HashMap<>(), nextCache = new java.util.HashMap<>();
    // null after process death: the next wallpaper-state check reconciles the host's cached layout.
    private static Boolean wallpaperCoversWidget;

    private static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, PhoneMarketWidgetProvider.class));
    }

    static boolean hasWidgets(Context context) {
        return widgetIds(context).length > 0;
    }

    static boolean hasLockStarWidget(Context context) {
        try {
            if (Settings.System.getInt(context.getContentResolver(), "lockstar_enabled", 0) <= 0
                    || !context.getPackageManager().getApplicationInfo("com.samsung.systemui.lockstar", 0).enabled) {
                return false;
            }
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return false;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) {
            Bundle options = manager.getAppWidgetOptions(id);
            // The Samsung lock-screen editor identifies these hosts by widget size and grid spans.
            // One UI Home also sends semWidgetSize, but supplies its launcher host metadata.
            if (options.containsKey("semWidgetSize") && !options.containsKey("semHostType")
                    && !options.containsKey("hsWidgetDisplayId")
                    && options.getInt("semAppWidgetRowSpan") > 0 && options.getInt("semAppWidgetColumnSpan") > 0) {
                return true;
            }
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
    }

    static JSONObject latest(Context context) {
        return latest(context,prefs(context).getString("symbol", "sz000063"));
    }

    static JSONObject latest(Context context, String symbol) {
        SharedPreferences settings = prefs(context);
        if (symbol.isEmpty()) return null;
        PhoneSignalStore.migrate(settings,symbol);
        if ("1d".equals(PhoneLiveSchedule.displayPeriod(context)) && daily.containsKey(symbol))
            return PhoneSignalStore.restore(settings,daily.get(symbol));
        if (minutes.containsKey(symbol)) return PhoneSignalStore.restore(settings,minutes.get(symbol));
        JSONObject widget = read(settings, CACHE + "_" + symbol, symbol);
        if (widget == null) widget = read(settings, CACHE, symbol);
        JSONObject app = read(settings, "snapshot_" + symbol, symbol);
        JSONObject chosen = widget == null ? app
                : app != null && app.optString("quote_at").compareTo(widget.optString("quote_at")) >= 0 ? app : widget;
        return PhoneSignalStore.restore(settings,chosen);
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
        minutes.clear(); daily.clear(); nextCache.clear();
        nextVisible.clear();
        prefs(context).edit().remove(CACHE).apply();
        PhoneLiveSchedule.armNext(context);
    }

    private static void render(Context context, AppWidgetManager manager, int[] ids, JSONObject quote) {
        if (Boolean.TRUE.equals(wallpaperCoversWidget)) {
            // The wallpaper releases this only after replacing its retained market frame.
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
            minutes.clear(); daily.clear(); nextCache.clear();
            prefs(context).edit().remove(CACHE).apply();
        }
        nextVisible.clear();
        PhoneWidgetRefreshService.cancel(context);
        updateTheme(context);
        if (hasWidgets(context) && !prefs(context).getBoolean("paused", false)) {
            PhoneWidgetRefreshService.schedule(context, true);
        }
        PhoneLiveSchedule.armNext(context);
    }

    static void visibleQuote(Context context, JSONObject quote) {
        PhoneSignalStore.remember(prefs(context),quote);
        if (PhoneLiveMarketService.isStreaming()) return;
        long now = SystemClock.elapsedRealtime();
        if (quote == null || quote.optJSONArray("bars") == null) return;
        String symbol = quote.optString("symbol");
        if (now < nextVisible.getOrDefault(symbol,0L)) return;
        nextVisible.put(symbol,now + VISIBLE_UPDATE_MS);
        publish(context, quote);
    }

    static void publish(Context context, JSONObject quote) {
        publish(context,quote,null);
    }

    static void publish(Context context, JSONObject quote, JSONObject lower) {
        if (PhoneLiveMarketService.isStreaming()) return;
        update(context, quote, false);
        update(context, lower, false);
        updateTheme(context);
    }

    static void publishStream(Context context, JSONObject quote) {
        publishStream(context,quote,null);
    }

    static void publishStream(Context context, JSONObject upper, JSONObject lower) {
        update(context,upper,true);
        update(context,lower,true);
        updateTheme(context);
    }

    private static void update(Context context, JSONObject quote, boolean stream) {
        if (quote == null || quote.optJSONArray("bars") == null) return;
        SharedPreferences settings = prefs(context);
        String symbol = quote.optString("symbol");
        if (settings.getBoolean("paused", false) || symbol.isEmpty()
                || !(settings.getString("symbol", "sz000063").equals(symbol)
                || PhoneDisplayStocks.second(settings).equals(symbol))) return;
        minutes.keySet().removeIf(code -> !code.equals(settings.getString("symbol","sz000063"))
                && !code.equals(PhoneDisplayStocks.second(settings)));
        daily.keySet().retainAll(java.util.Arrays.asList(settings.getString("symbol","sz000063"),PhoneDisplayStocks.second(settings)));
        quote = PhoneSignalStore.accept(settings,quote);
        int[] ids = widgetIds(context);
        if (ids.length == 0) return;
        if ("1d".equals(quote.optString("period"))) {
            daily.put(symbol,quote);
            return;
        }
        JSONObject previous = minutes.get(symbol);
        if (previous != null && previous.optString("quote_at").compareTo(quote.optString("quote_at")) > 0) return;
        boolean newStock = !minutes.containsKey(symbol);
        minutes.put(symbol,quote);
        long now = SystemClock.elapsedRealtime();
        if (stream && !newStock && now < nextCache.getOrDefault(symbol,0L)) return;
        nextCache.put(symbol,now + 30_000L);
        // Keep accepted B/S markers with their dated candles so idle displays survive process death.
        // The volatile depth book is deliberately not persisted.
        JSONObject candles = new JSONObject();
        for (String key : new String[]{"symbol", "name", "source", "date", "bars", "quote_at",
                "price", "previous", "high", "low", "fills"}) {
            try {
                if (quote.has(key)) candles.put(key, quote.get(key));
            } catch (JSONException invalidQuote) {
                return;
            }
        }
        settings.edit().putString(CACHE + "_" + symbol, candles.toString()).apply();
    }
}
