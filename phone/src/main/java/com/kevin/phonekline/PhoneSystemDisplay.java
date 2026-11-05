package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.PrintWriter;

/** A visible wallpaper or charging screensaver is one subscriber to the shared quote feed. */
final class PhoneSystemDisplay implements SharedPreferences.OnSharedPreferenceChangeListener {
    final PhoneCandleView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final PhoneQuoteSession feed;
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
        prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        this.paint = paint;
        view = new PhoneCandleView(context);
        view.setSurface(wallpaper ? PhoneCandleView.Surface.LOCK : PhoneCandleView.Surface.DREAM);
        feed = new PhoneQuoteSession((snapshot, status) -> {
            if (!active) return;
            view.setFeedStatus(status);
            if (snapshot != null && snapshot != shownQuote && symbol.equals(snapshot.optString("symbol"))) {
                shownQuote = snapshot;
                view.setMarket(snapshot, symbol);
                PhoneMarketWidgetProvider.visibleQuote(context, snapshot);
            }
            requestFrame();
        });
        rates=new PhoneSamplingRate(context,prefs,hz -> {
            feed.setRate(hz);
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
    }

    void close() {
        if (closed) return;
        hide();
        closed = true;
        feed.close();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences changed, String key) {
        if (!active) return;
        if (key == null || "lock_theme".equals(key)) {
            applyTheme();
            requestFrame();
        }
        if (key == null || "chart_mode".equals(key) || "show_depth".equals(key) || "show_signals".equals(key)) {
            view.refreshChartOptions();
            requestFrame();
        }
        if (key == null || "symbol".equals(key) || "paused".equals(key)
                || "mcp_url".equals(key) || "mcp_key_iv".equals(key) || "mcp_key_cipher".equals(key)) {
            handler.removeCallbacks(refresh);
            handler.post(refresh);
        }
    }

    private void applyTheme() {
        view.setTheme(prefs.getInt("lock_theme", PhoneCandleView.LOCK_BLACK));
    }

    private void configure() {
        if (!active) return;
        symbol = prefs.getString("symbol", "sz000063");
        int rate = rates.current();
        boolean paused = prefs.getBoolean("paused", false);
        JSONObject restored = cachedCandles();
        shownQuote = restored;
        view.setRefreshState(rate, paused);
        view.setMarket(restored, symbol);
        view.setFeedStatus(paused ? "已暂停" : "等待行情");
        if (paused) {
            feed.stop();
        } else {
            String url = prefs.getString("mcp_url", "");
            feed.startDisplay(symbol, url, url.isEmpty() ? "" : PhoneSecretStore.load(prefs), rate, restored);
        }
        requestFrame();
    }

    private JSONObject cachedCandles() {
        try {
            JSONObject restored = new JSONObject(prefs.getString("snapshot_" + symbol, "{}"));
            if (!symbol.equals(restored.optString("symbol"))) return null;
            restored.remove("book");
            restored.remove("fills");
            return restored;
        } catch (JSONException invalidCache) {
            return null;
        }
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
        if (active) out.println("  frame=" + view.getContentDescription());
    }
}
