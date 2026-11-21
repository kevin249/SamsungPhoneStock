package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.PrintWriter;

/** A visible wallpaper or charging screensaver is one subscriber to the shared quote feed. */
final class PhoneSystemDisplay implements SharedPreferences.OnSharedPreferenceChangeListener {
    final PhoneCandleView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final Context context;
    private final java.util.function.Supplier<java.time.LocalDateTime> clock;
    private String currentPeriod;
    private final PhoneDisplayFeed feed;
    private final PhoneDisplayFeed lowerFeed;
    private final Runnable paint;
    private final Runnable refresh = this::configure;
    private final Runnable frame = this::drawFrame;
    private String symbol = "";
    private JSONObject shownQuote;
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
        feed = new PhoneDisplayFeed(prefs,(snapshot, status) -> {
            if (!active) return;
            view.setFeedStatus(status);
            if (snapshot != null && snapshot != shownQuote && symbol.equals(snapshot.optString("symbol"))) {
                shownQuote = snapshot;
                view.setMarket(snapshot, symbol);

            }
            requestFrame();
        }, minuteObserver);
        lowerFeed = new PhoneDisplayFeed(prefs,(snapshot,status) -> {
            if (!active) return;
            view.setLowerMarket(snapshot,PhoneDisplayStocks.second(prefs),status,
                    currentPeriod);
            requestFrame();
        });
        rates=new PhoneSamplingRate(context,prefs,hz -> {
            feed.setRate(hz);
            lowerFeed.setRate(hz);
            view.setRefreshState(hz,prefs.getBoolean("paused",false));
            requestFrame();
        });
    }

    void show() {
        if (active || closed) return;
        active = true;
        rates.start();
        prefs.registerOnSharedPreferenceChangeListener(this);
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
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(frame);
        frameDue = Long.MAX_VALUE;
        feed.stop();
        lowerFeed.stop();
    }

    void close() {
        if (closed) return;
        hide();
        closed = true;
        feed.stop();
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
        if (key == null || "symbol".equals(key) || "paused".equals(key)
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
        String prefix = "1d".equals(currentPeriod) ? "daily_snapshot_" : "snapshot_";
        return key.equals(prefix + symbol) || key.equals(prefix + PhoneDisplayStocks.second(prefs));
    }

    private void applyTheme() {
        view.setTheme(prefs.getInt("lock_theme", PhoneCandleView.LOCK_BLACK));
    }

    private void configure() {
        if (!active) return;
        if (PhoneLiveSchedule.displayHours(context,clock.get())) PhoneLiveTradingDay.refresh(context, null);
        symbol = prefs.getString("symbol", "sz000063");
        int rate = rates.current();
        view.setRefreshState(rate,prefs.getBoolean("paused",false));
        java.time.LocalDateTime now = clock.get();
        String period = PhoneLiveSchedule.displayPeriod(context, now);
        currentPeriod = period;
        view.setUpperPeriod(period);
        boolean offline = !PhoneLiveSchedule.displayHours(context,now) || PhoneLiveTradingDay.closed(context);
        feed.start(symbol,period,rate,false,offline);
        String second = PhoneDisplayStocks.second(prefs);
        if (second.isEmpty()) view.setLowerMarket(null,"","");
        lowerFeed.start(second,period,rate,false,offline);
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 60_050L - now.getSecond() * 1000L - now.getNano() / 1_000_000L);
        requestFrame();
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
        PhoneQuoteSession.RateSample counts = feed.sample();
        out.println("  market active=" + active + " rateHz=" + rates.current()
                + " depthSent=" + counts.depthSentTotal + " depthOk=" + counts.depthOkTotal
                + " tradesSent=" + counts.tradeSentTotal + " paintedAtUptimeMs=" + paintedAt);
        PhoneQuoteSession.RateSample lowerCounts = lowerFeed.sample();
        out.println("  stocks upper=" + symbol + " lower=" + PhoneDisplayStocks.second(prefs)
                + " period=" + currentPeriod + " lowerDepthSent=" + lowerCounts.depthSentTotal
                + " lowerDepthOk=" + lowerCounts.depthOkTotal);
        if (active) out.println("  frame=" + view.getContentDescription());
    }
}
