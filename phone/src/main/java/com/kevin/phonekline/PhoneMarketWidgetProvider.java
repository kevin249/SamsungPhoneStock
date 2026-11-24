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
import android.util.SparseArray;
import android.widget.RemoteViews;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** The system owns the widget surface, including copies placed on AOD by LockStar. */
public final class PhoneMarketWidgetProvider extends AppWidgetProvider {
    static final String NEXT_PAGE = "com.kevin.phonekline.WIDGET_NEXT_PAGE";

    @Override public void onReceive(Context context, android.content.Intent intent) {
        if (NEXT_PAGE.equals(intent.getAction())) {
            int target = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            for (int id : widgetIds(context)) {
                if (id != target) continue;
                if (!PhoneWidgetRenderer.isDesktopWidget(AppWidgetManager.getInstance(context).getAppWidgetOptions(id))) break;
                SharedPreferences settings = prefs(context);
                String key = PhoneWidgetRenderer.PAGE + id;
                settings.edit().putInt(key, settings.getInt(key, 0) + 1).apply();
                updateTheme(context);
                PhoneWidgetRefreshService.schedule(context, true);
                break;
            }
            return;
        }
        super.onReceive(context, intent);
    }

    static java.util.Set<String> visibleStocks(Context context) {
        SharedPreferences settings = prefs(context);
        java.util.Set<String> visible = new java.util.LinkedHashSet<>();
        for (int id : widgetIds(context)) visible.addAll(PhoneWidgetRenderer.visibleStocks(context, settings, id));
        return visible;
    }

    private static final long VISIBLE_UPDATE_MS = 15_000;
    private static final String CACHE = "widget_candles";
    private static final java.util.Map<String,JSONObject> minutes = new java.util.HashMap<>();
    private static final java.util.Map<String,JSONObject> daily = new java.util.HashMap<>();
    private static final java.util.Map<String,Long> nextVisible = new java.util.HashMap<>(), nextCache = new java.util.HashMap<>();
    // null after process death: the next wallpaper-state check reconciles the host's cached layout.
    private static Boolean wallpaperCoversWidget;
    private static final SparseArray<RemoteViews> lockStarFrames = new SparseArray<>();
    private static final Set<Runnable> lockStarObservers = new CopyOnWriteArraySet<>();

    private static int[] widgetIds(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, PhoneMarketWidgetProvider.class));
    }

    static boolean hasWidgets(Context context) {
        return widgetIds(context).length > 0;
    }

    static boolean hasLockStarWidget(Context context) {
        return lockStarWidgetId(context) != AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    static int lockStarWidgetId(Context context) {
        try {
            if (Settings.System.getInt(context.getContentResolver(), "lockstar_enabled", 0) <= 0
                    || !context.getPackageManager().getApplicationInfo("com.samsung.systemui.lockstar", 0).enabled) {
                return AppWidgetManager.INVALID_APPWIDGET_ID;
            }
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return AppWidgetManager.INVALID_APPWIDGET_ID;
        }
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : widgetIds(context)) {
            if (isLockStarHost(manager.getAppWidgetOptions(id))) return id;
        }
        return AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    static boolean isLockStarHost(Bundle options) {
        // Samsung's lock-screen editor supplies grid spans without One UI Home's launcher metadata.
        return options.containsKey("semWidgetSize") && !options.containsKey("semHostType")
                && !options.containsKey("hsWidgetDisplayId")
                && options.getInt("semAppWidgetRowSpan") > 0 && options.getInt("semAppWidgetColumnSpan") > 0;
    }

    static void addLockStarObserver(Runnable listener) { lockStarObservers.add(listener); }
    static void removeLockStarObserver(Runnable listener) { lockStarObservers.remove(listener); }

    private static void lockStarChanged() {
        for (Runnable observer : lockStarObservers) observer.run();
    }

    // The charging screensaver can show this same committed frame without advancing the page
    // or independently rebuilding the chart from a later quote.
    static RemoteViews lockStarFrame(Context context, int id) {
        RemoteViews frame = lockStarFrames.get(id);
        if (frame != null) return frame;
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        Bundle options = manager.getAppWidgetOptions(id);
        if (!isLockStarHost(options)) return null;
        frame = PhoneWidgetRenderer.render(context, id, latest(context), options, false);
        // After process recreation, bring the platform's retained widget and the screensaver
        // to the same saved page and cached quote on their first shared frame.
        if (!Boolean.TRUE.equals(wallpaperCoversWidget)) manager.updateAppWidget(id, frame);
        lockStarFrames.put(id, frame);
        return frame;
    }

    static void refreshLockStarPageIfDue(Context context, int id) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        Bundle options = manager.getAppWidgetOptions(id);
        if (isLockStarHost(options) && !Boolean.TRUE.equals(wallpaperCoversWidget)
                && PhoneWidgetRenderer.advancePassivePageIfDue(context, id, options)) {
            render(context, manager, new int[]{id}, latest(context));
        }
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
        if (!PhoneDisplayStocks.minis(settings).contains(symbol) && "1d".equals(PhoneLiveSchedule.displayPeriod(context)) && daily.containsKey(symbol))
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
        PhoneWidgetRefreshService.schedule(context, true);
    }

    @Override public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int id : ids) {
            PhoneWidgetRenderer.passivePages.forget(id);
            lockStarFrames.remove(id);
            editor.remove(PhoneWidgetRenderer.PAGE + id).remove("widget_passive_since_" + id);
        }
        editor.apply();
        lockStarChanged();
    }

    @Override public void onDisabled(Context context) {
        PhoneWidgetRenderer.passivePages.stop();
        lockStarFrames.clear();
        lockStarChanged();
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
        boolean lockStarUpdated = false;
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            RemoteViews frame = PhoneWidgetRenderer.render(context, id, quote, options, true);
            manager.updateAppWidget(id, frame);
            if (isLockStarHost(options)) {
                lockStarFrames.put(id, frame);
                lockStarUpdated = true;
            } else if (lockStarFrames.indexOfKey(id) >= 0) {
                lockStarFrames.remove(id);
                lockStarUpdated = true;
            }
        }
        if (lockStarUpdated) lockStarChanged();
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
        publishBatch(context, java.util.Collections.singletonList(quote), false);
    }

    static void publishBatch(Context context, Iterable<JSONObject> quotes, boolean stream) {
        if (!stream && PhoneLiveMarketService.isStreaming()) return;
        updateBatch(context, quotes, stream);
    }

    static void publishPresentationQuotes(Context context, Iterable<JSONObject> quotes) {
        if (!PhoneLiveMarketService.isStreaming()) updateBatch(context, quotes, true);
    }

    private static void updateBatch(Context context, Iterable<JSONObject> quotes, boolean throttleCacheWrites) {
        for (JSONObject quote : quotes) update(context, quote, throttleCacheWrites);
        updateTheme(context);
    }

    private static void update(Context context, JSONObject quote, boolean throttleCacheWrites) {
        if (quote == null || quote.optJSONArray("bars") == null) return;
        SharedPreferences settings = prefs(context);
        String symbol = quote.optString("symbol");
        java.util.List<String> selected = PhoneDisplayStocks.symbols(settings);
        if (settings.getBoolean("paused", false) || !selected.contains(symbol)) return;
        minutes.keySet().retainAll(selected);
        daily.keySet().retainAll(selected);
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
        if (throttleCacheWrites && !newStock && now < nextCache.getOrDefault(symbol,0L)) return;
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
