package com.kevin.phonekline;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A cancellable per-stock display subscription. Daily history never enters the minute/AOD cache. */
final class PhoneDisplayFeed {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final PhoneQuoteSession.Listener listener;
    private final PhoneQuoteSession live;
    private ScheduledThreadPoolExecutor worker;
    private PhoneMarketClient publicClient;
    private PhoneMcpClient mcp;
    private JSONObject daily, latest;
    private String symbol = "", mode = "", status = "等待行情", dailyStatus = "日 K 加载中";
    private int generation;
    private boolean started, paused, cacheOnly, snapshotOnly, minuteOnly;
    private String address = "", secret = "";
    private final java.util.function.Consumer<JSONObject> minuteObserver;

    PhoneDisplayFeed(SharedPreferences prefs, PhoneQuoteSession.Listener listener) {
        this(prefs,listener,quote -> { });
    }

    PhoneDisplayFeed(SharedPreferences prefs, PhoneQuoteSession.Listener listener, java.util.function.Consumer<JSONObject> minuteObserver) {
        this.minuteObserver = minuteObserver;
        this.prefs = prefs;
        this.listener = listener;
        live = new PhoneQuoteSession((quote, message) -> {
            if (!symbol.equals(quote.optString("symbol"))) return;
            latest = PhoneSignalStore.accept(prefs,quote);
            minuteObserver.accept(latest);
            status = message;
            emit();
        });
    }

    void start(String symbol, String mode, int hz) { start(symbol, mode, hz, false); }

    void start(String symbol, String mode, int hz, boolean snapshotOnly) {
        start(symbol, mode, hz, snapshotOnly, false);
    }

    void start(String symbol, String mode, int hz, boolean snapshotOnly, boolean cacheOnly) {
        String url = prefs.getString("mcp_url", "");
        String key = url.isEmpty() ? "" : PhoneSecretStore.load(prefs);
        boolean nextPaused = prefs.getBoolean("paused",false);
        boolean nextMinuteOnly = PhoneDisplayStocks.minis(prefs).contains(symbol) && !"1d".equals(mode);
        if (started && minuteOnly == nextMinuteOnly && this.symbol.equals(symbol) && this.mode.equals(mode) && paused == nextPaused
                && address.equals(url) && secret.equals(key) && this.cacheOnly == cacheOnly && this.snapshotOnly == snapshotOnly) {
            if (cacheOnly) {
                JSONObject saved = cached("snapshot_" + symbol);
                if (saved != null && (latest == null
                        || saved.optString("quote_at").compareTo(latest.optString("quote_at")) > 0)) latest = saved;
                if ("1d".equals(mode)) daily = cached("daily_snapshot_" + symbol);
            }
            setRate(hz);
            emit();
            return;
        }
        JSONObject retained = this.symbol.equals(symbol) ? latest : null;
        stop();
        this.cacheOnly = cacheOnly;
        this.snapshotOnly = snapshotOnly;
        minuteOnly = nextMinuteOnly;
        started = true; paused = nextPaused; address = url; secret = key;
        this.symbol = symbol;
        this.mode = mode;
        if (symbol.isEmpty()) return;
        PhoneSignalStore.migrate(prefs,symbol);
        status = paused ? "已暂停" : cacheOnly ? "休市 · 已停止后台更新" : "等待行情";
        dailyStatus = cacheOnly ? "" : "日 K 加载中";
        latest = PhoneSignalStore.restore(prefs,retained != null ? retained : cached("snapshot_" + symbol));
        daily = "1d".equals(mode) ? cached("daily_snapshot_" + symbol) : null;
        emit();
        if (paused || cacheOnly) return;
        if (snapshotOnly) live.startSnapshot(symbol,url,key,latest);
        else live.startDisplay(symbol,url,key,hz,latest);
        // Full charts switch to cached daily history at the close. Mini rows always use intraday data.
        if (!minuteOnly) {
            publicClient = new PhoneMarketClient();
            mcp = url.isEmpty() || key.isEmpty() ? null : new PhoneMcpClient(url,key);
            worker = new ScheduledThreadPoolExecutor(1);
            poll(generation, symbol, publicClient, mcp, worker);
        }
    }

    private JSONObject cached(String key) {
        return cached(prefs, key, symbol);
    }

    static JSONObject cached(SharedPreferences prefs, String key, String symbol) {
        try {
            JSONObject data = new JSONObject(prefs.getString(key,"{}"));
            if (!symbol.equals(data.optString("symbol"))) return null;
            data.remove("book");
            return data;
        } catch (Exception ignored) { return null; }
    }

    static JSONObject fetchDaily(String code, PhoneMarketClient market, PhoneMcpClient remote) throws Exception {
        if (remote != null) {
            try {
                JSONObject response = remote.call("get_tdx_kline",PhoneMcpClient.arguments(code)
                        .put("period","1d").put("dividend_type","none").put("days",240).put("limit",120));
                if (!code.equals(PhoneRealtimeCandles.normalize(response.optString("symbol")))
                        || !response.optBoolean("available",true)) throw new java.io.IOException("日 K 不可用");
                return PhoneDailyCandles.history(code, response.optString("name"), response.getJSONArray("bars"));
            } catch (Exception unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
            }
        }
        return market.fetchDaily(code);
    }

    private void poll(int token, String code, PhoneMarketClient market, PhoneMcpClient remote, ScheduledThreadPoolExecutor executor) {
        executor.execute(() -> {
            JSONObject result = null;
            try {
                result = fetchDaily(code, market, remote);
            } catch (Exception unavailable) { /* Keep last successful daily history. */ }
            JSONObject received = result;
            main.post(() -> {
                if (token != generation) return;
                if (received != null) {
                    daily = received;
                    dailyStatus = "";
                    prefs.edit().putString("daily_snapshot_" + code,daily.toString()).apply();
                } else dailyStatus = daily == null ? "日 K 暂不可用，正在重试" : "日 K 已缓存，正在重试";
                emit();
                if ("1d".equals(mode) && !executor.isShutdown()) executor.schedule(() -> poll(token,code,market,remote,executor),
                        received == null ? 30 : 60,TimeUnit.SECONDS);
            });
        });
    }

    private void emit() {
        if (symbol.isEmpty()) return;
        try {
            JSONObject data = "1d".equals(mode) ? PhoneDailyCandles.merge(daily,latest) : latest;
            if (data == null) data = new JSONObject().put("symbol",symbol).put("period",mode);
            String message = "1d".equals(mode) && !dailyStatus.isEmpty() ? status + " · " + dailyStatus : status;
            listener.onUpdate(PhoneSignalStore.restore(prefs,data),message);
        } catch (Exception invalid) { listener.onUpdate(daily,"日 K 等待更新"); }
    }

    PhoneQuoteSession.RateSample sample() { return live.sample(); }

    void setRate(int hz) { live.setRate(hz); }

    void stop() {
        started = false;
        generation++;
        live.stop();
        if (latest != null && symbol.equals(latest.optString("symbol"))) {
            try {
                JSONObject saved = PhoneRealtimeCandles.copy(latest);
                saved.remove("book");
                prefs.edit().putString("snapshot_" + symbol,saved.toString()).apply();
            } catch (org.json.JSONException ignored) { }
        }
        symbol = ""; latest = null; daily = null;
        if (worker != null) worker.shutdownNow();
        if (publicClient != null) publicClient.cancel();
        if (mcp != null) mcp.cancel();
        main.removeCallbacksAndMessages(null);
        worker = null; publicClient = null; mcp = null;
    }
}
