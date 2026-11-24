package com.kevin.phonekline;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;
import org.json.JSONObject;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A visible wallpaper or charging screensaver is one subscriber to the shared quote feed. */
final class PhoneSystemDisplay implements SharedPreferences.OnSharedPreferenceChangeListener {
    final PhoneCandleView view;
    private PhoneWidgetPresentation presentation;
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Context context;
    private final java.util.function.Supplier<java.time.LocalDateTime> clock;
    private String currentPeriod;
    private final Map<String, PhoneDisplayFeed> feeds = new LinkedHashMap<>();
    private final Map<String, JSONObject> widgetQuotes = new LinkedHashMap<>();
    private final Runnable publishWidgetQuotes = this::publishWidgetQuotes;
    private long lastWidgetPublish;
    private final Consumer<JSONObject> minuteObserver;
    private List<String> visibleStocks = Collections.emptyList();
    private Set<String> minis = Collections.emptySet();
    private int page;
    private boolean rotating;
    private final Runnable nextPage = () -> {
        rotating = false;
        page++;
        configure();
    };
    private final Runnable paint;
    private final Runnable refresh = this::configure;
    private final Runnable widgetUpdate = this::onWidgetFrame;
    private final Runnable widgetObserver = () -> {
        handler.removeCallbacks(widgetUpdate);
        handler.post(widgetUpdate);
    };
    private final Runnable frame = this::drawFrame;
    private final PhoneSamplingRate rates;
    private boolean active, closed;
    private long paintedAt;
    private long frameDue = Long.MAX_VALUE;

    PhoneSystemDisplay(Context context, boolean wallpaper, Runnable paint) {
        this(context, wallpaper, paint, PhoneRealtimeCandles::now);
    }

    PhoneSystemDisplay(Context context, boolean wallpaper, Runnable paint,
                       java.util.function.Supplier<java.time.LocalDateTime> clock) {
        this(context,wallpaper,paint,clock,snapshot -> PhoneMarketWidgetProvider.visibleQuote(context,snapshot));
    }

    PhoneSystemDisplay(Context context, boolean wallpaper, Runnable paint,
                       java.util.function.Supplier<java.time.LocalDateTime> clock,
                       java.util.function.Consumer<JSONObject> minuteObserver) {
        this.clock = clock;
        this.context = context;
        prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        this.paint = paint;
        view = new PhoneCandleView(context);
        view.setSurface(wallpaper ? PhoneCandleView.Surface.LOCK : PhoneCandleView.Surface.DREAM);
        view.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (active && b - t != oldB - oldT) {
                handler.removeCallbacks(refresh);
                handler.post(refresh);
            }
        });
        this.minuteObserver = minuteObserver;
        rates=new PhoneSamplingRate(context,prefs,hz -> {
            for (PhoneDisplayFeed feed : feeds.values()) feed.setRate(hz);
            view.setRefreshState(hz,prefs.getBoolean("paused",false));
            requestFrame();
        });
    }

    // A dream or activity can host the passive widget views. A WallpaperService renders
    // to its own SurfaceHolder, while the real lock-screen widget is hosted by SystemUI.
    PhoneWidgetPresentation contentView() {
        if (presentation == null) presentation = new PhoneWidgetPresentation(context, view);
        return presentation;
    }

    void show() {
        if (active || closed) return;
        active = true;
        rates.start();
        prefs.registerOnSharedPreferenceChangeListener(this);
        if (presentation != null) PhoneMarketWidgetProvider.addLockStarObserver(widgetObserver);
        applyTheme();
        view.refreshChartOptions();
        configure();
        if (PhoneLiveSchedule.displayHours(context,clock.get())) PhoneLiveTradingDay.refresh(context, () -> { if (active) configure(); });
    }

    void hide() {
        if (!active) return;
        active = false;
        rates.stop();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (presentation != null) PhoneMarketWidgetProvider.removeLockStarObserver(widgetObserver);
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) PhoneWidgetRenderer.passivePages.forget(widgetId);
        widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
        handler.removeCallbacks(widgetUpdate);
        handler.removeCallbacks(publishWidgetQuotes);
        widgetQuotes.clear();
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(frame);
        frameDue = Long.MAX_VALUE;
        handler.removeCallbacks(nextPage);
        rotating = false;
        page = 0;
        for (PhoneDisplayFeed feed : feeds.values()) feed.stop();
        feeds.clear();
    }

    void close() {
        if (closed) return;
        hide();
        closed = true;
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences changed, String key) {
        if (!active) return;
        if (key == null || "lock_theme".equals(key) || PhoneCandleView.LOCK_BACKGROUND_LIFT.equals(key)) {
            applyTheme();
            requestFrame();
        }
        if (key == null || "chart_mode".equals(key) || "show_depth".equals(key) || "show_signals".equals(key)) {
            view.refreshChartOptions();
            requestFrame();
        }
        boolean layoutChanged = key == null || PhoneDisplayStocks.ORDER.equals(key) || PhoneDisplayStocks.MINI.equals(key)
                || key.startsWith(PhoneDisplayStocks.HEIGHT_PREFIX);
        if (layoutChanged) page = 0;
        if (layoutChanged || "symbol".equals(key) || "paused".equals(key)
                || "first_chart_mode".equals(key) || "chart_mode".equals(key) || "second_symbol".equals(key) || "second_chart_mode".equals(key)
                || key.startsWith("accepted_signals_") || PhoneDisplayWindow.AUTOMATIC.equals(key)
                || offlineSnapshotChanged(key)
                || "live_calendar_date".equals(key) || "live_calendar_open".equals(key)
                || "show_depth".equals(key) || "show_signals".equals(key) || "mcp_url".equals(key) || "mcp_key_iv".equals(key) || "mcp_key_cipher".equals(key)) {
            handler.removeCallbacks(refresh);
            handler.post(refresh);
        }
    }

    private boolean offlineSnapshotChanged(String key) {
        if (PhoneLiveSchedule.displayHours(context, clock.get()) && !PhoneLiveTradingDay.closed(context)) return false;
        for (String code : visibleStocks) {
            String period = PhoneDisplayStocks.period(minis, code, currentPeriod);
            if (key.equals(("1d".equals(period) ? "daily_snapshot_" : "snapshot_") + code)) return true;
        }
        return false;
    }

    private void applyTheme() {
        view.setTheme(prefs.getInt("lock_theme", PhoneCandleView.LOCK_BLACK));
    }

    private int showWidgetFrame() {
        if (presentation == null) return AppWidgetManager.INVALID_APPWIDGET_ID;
        int id = PhoneMarketWidgetProvider.lockStarWidgetId(context);
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            RemoteViews frame = PhoneMarketWidgetProvider.lockStarFrame(context, id);
            if (frame != null) {
                presentation.showFrame(frame, PhoneWidgetRenderer.widgetWidth(context, id),
                        PhoneWidgetRenderer.widgetHeight(context, id));
                return id;
            }
        }
        presentation.clearFrame();
        return AppWidgetManager.INVALID_APPWIDGET_ID;
    }

    private void onWidgetFrame() {
        if (!active) return;
        int id = showWidgetFrame();
        if (id != widgetId || (id != AppWidgetManager.INVALID_APPWIDGET_ID
                && !visibleStocks.equals(PhoneWidgetRenderer.visibleStocks(context, prefs, id)))) {
            configure();
        }
    }

    private void configure() {
        if (!active) return;
        if (PhoneLiveSchedule.displayHours(context,clock.get())) PhoneLiveTradingDay.refresh(context, null);
        int rate = rates.current();
        view.setRefreshState(rate,prefs.getBoolean("paused",false));
        java.time.LocalDateTime now = clock.get();
        String period = PhoneLiveSchedule.displayPeriod(context, now);
        currentPeriod = period;
        boolean offline = !PhoneLiveSchedule.displayHours(context,now) || PhoneLiveTradingDay.closed(context);
        minis = PhoneDisplayStocks.minis(prefs);
        int previousWidgetId = widgetId;
        widgetId = showWidgetFrame();
        boolean hasWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID;
        boolean rotateCanvas = false;
        if (hasWidget) {
            if (widgetId != previousWidgetId) PhoneWidgetRenderer.passivePages.forget(widgetId);
            PhoneMarketWidgetProvider.refreshLockStarPageIfDue(context, widgetId);
            visibleStocks = PhoneWidgetRenderer.visibleStocks(context, prefs, widgetId);
        } else {
            List<PhoneStockLayout.Page> pages = view.layoutPages(PhoneDisplayStocks.symbols(prefs), minis);
            page = Math.floorMod(page, pages.size());
            PhoneStockLayout.Page current = pages.get(page);
            visibleStocks = current.stocks;
            rotateCanvas = pages.size() > 1;
            view.setPage(current, rotateCanvas ? (page + 1) + " / " + pages.size() : "");
        }
        widgetQuotes.keySet().retainAll(visibleStocks);
        for (String code : new ArrayList<>(feeds.keySet())) {
            if (!visibleStocks.contains(code)) feeds.remove(code).stop();
        }
        for (String code : visibleStocks) {
            PhoneDisplayFeed feed = feeds.computeIfAbsent(code, stock -> new PhoneDisplayFeed(prefs, (snapshot, status) -> {
                int index = visibleStocks.indexOf(stock);
                if (!active || index < 0) return;
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    queueWidgetQuote(stock, snapshot);
                    return;
                }
                view.setStock(index, snapshot, stock, status, PhoneDisplayStocks.period(minis, stock, currentPeriod), minis.contains(stock));
                requestFrame();
            }, quote -> {
                if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) minuteObserver.accept(quote);
            }));
            feed.start(code, PhoneDisplayStocks.period(minis, code, period), rate, false, offline);
        }
        if (rotateCanvas && !rotating) {
            handler.postDelayed(nextPage, 15_000);
            rotating = true;
        } else if (!rotateCanvas) {
            handler.removeCallbacks(nextPage);
            rotating = false;
        }
        handler.removeCallbacks(refresh);
        long untilMinute = 60_050L - now.getSecond() * 1000L - now.getNano() / 1_000_000L;
        handler.postDelayed(refresh, hasWidget ? Math.min(untilMinute, 30_000L) : untilMinute);
        requestFrame();
    }

    private void queueWidgetQuote(String stock, JSONObject quote) {
        if (PhoneLiveMarketService.isStreaming() || quote == null || quote.optJSONArray("bars") == null) return;
        boolean scheduled = !widgetQuotes.isEmpty();
        widgetQuotes.put(stock, quote);
        if (!scheduled) handler.postAtTime(publishWidgetQuotes, Math.max(SystemClock.uptimeMillis(), lastWidgetPublish + 1000));
    }

    private void publishWidgetQuotes() {
        if (!widgetQuotes.isEmpty() && active && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            lastWidgetPublish = SystemClock.uptimeMillis();
            PhoneMarketWidgetProvider.publishPresentationQuotes(context, widgetQuotes.values());
        }
        widgetQuotes.clear();
    }

    private void requestFrame() {
        if (!active) return;
        scheduleFrame(Math.max(SystemClock.uptimeMillis(), paintedAt + 1000 / rates.current()));
    }

    private void scheduleFrame(long when) {
        // A burst of completed requests must not continuously replace an already due frame.
        if (frameDue <= when) return;
        handler.removeCallbacks(frame);
        frameDue = when;
        handler.postAtTime(frame, when);
    }

    private void drawFrame() {
        frameDue = Long.MAX_VALUE;
        if (!active) return;
        paintedAt = SystemClock.uptimeMillis();
        long now = System.currentTimeMillis();
        view.setFrameTime(now);
        paint.run();
        // These designs show hours and minutes. Market events redraw sooner when a quote changes.
        scheduleFrame(paintedAt + 60_000L - Math.floorMod(now, 60_000L));
    }

    void dumpMarket(PrintWriter out) {
        out.println("  market active=" + active + " rateHz=" + rates.current() + " period=" + currentPeriod);
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) out.println("  lockStarWidgetId=" + widgetId);
        for (Map.Entry<String, PhoneDisplayFeed> stock : feeds.entrySet()) {
            PhoneQuoteSession.RateSample counts = stock.getValue().sample();
            out.println("  symbol=" + stock.getKey() + " depthSent=" + counts.depthSentTotal
                    + " depthOk=" + counts.depthOkTotal + " tradesSent=" + counts.tradeSentTotal);
        }
        if (active) out.println("  frame=" + view.getContentDescription());
    }
}
