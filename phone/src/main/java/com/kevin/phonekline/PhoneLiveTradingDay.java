package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kevin.market.TradingCalendar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Enable high-rate sampling only on a confirmed Shanghai trading date. */
final class PhoneLiveTradingDay {
    private static final String DATE = "live_calendar_date", OPEN = "live_calendar_open";
    private static final long DEADLINE_MS = 7_000, RETRY_MS = 60_000;
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    // Request coordination is confined to the main looper.
    private static Check pending;
    private static String lastDate = "";
    private static long attemptedAt;

    private PhoneLiveTradingDay() {}

    static boolean confirmed(Context context) {
        return knownToday(context, true);
    }

    static boolean closed(Context context) {
        return knownToday(context, false);
    }

    private static boolean knownToday(Context context, boolean open) {
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        return PhoneRealtimeCandles.now().toLocalDate().toString().equals(prefs.getString(DATE,""))
                && prefs.getBoolean(OPEN,false)==open;
    }

    static void refresh(Context context, Runnable done) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> start(app,main,done));
    }

    private static void start(Context app, Handler main, Runnable done) {
        LocalDate today = PhoneRealtimeCandles.now().toLocalDate();
        SharedPreferences prefs = app.getSharedPreferences("phone_dial",Context.MODE_PRIVATE);
        if (pending != null) {
            pending.callbacks.add(done);
            return;
        }
        long elapsed = SystemClock.elapsedRealtime();
        if (today.toString().equals(prefs.getString(DATE,""))
                || today.toString().equals(lastDate) && elapsed-attemptedAt < RETRY_MS) {
            if (done != null) done.run();
            return;
        }
        lastDate = today.toString();
        attemptedAt = elapsed;
        Check check = new Check(prefs,today,main);
        pending = check;
        check.callbacks.add(done);
        main.postDelayed(check.timeout,DEADLINE_MS);
        NETWORK.execute(check::load);
    }

    private static final class Check {
        final SharedPreferences prefs;
        final LocalDate date;
        final Handler main;
        final List<Runnable> callbacks = new ArrayList<>();
        volatile PhoneMcpClient client;
        volatile PhoneMarketClient publicMarket;
        volatile boolean expired;
        final Runnable timeout = () -> {
            expired = true;
            finish(TradingCalendar.Day.UNKNOWN);
            new Thread(this::cancelRequests,"PhoneCalendarCancel").start();
        };

        Check(SharedPreferences prefs, LocalDate date, Handler main) {
            this.prefs = prefs;
            this.date = date;
            this.main = main;
        }

        void load() {
            TradingCalendar.Day day = TradingCalendar.Day.UNKNOWN;
            try {
                LocalDateTime now = PhoneRealtimeCandles.now();
                String symbol = PhoneRealtimeCandles.normalize(prefs.getString("symbol","sz000063"));
                if (hasCurrentBar(prefs.getString("widget_candles",""),symbol,now)
                        || hasCurrentBar(prefs.getString("snapshot_"+symbol,""),symbol,now)) {
                    day = TradingCalendar.Day.OPEN;
                } else {
                    String url = prefs.getString("mcp_url","");
                    String key = url.startsWith("https://") ? PhoneSecretStore.load(prefs) : "";
                    if (!expired && !key.isEmpty()) {
                        client = new PhoneMcpClient(url,key);
                        if (!expired) day = lookup(client,date);
                    } else if (!expired && !symbol.isEmpty() && PhoneRealtimeCandles.tradingMinute(now)) {
                        publicMarket = new PhoneMarketClient();
                        if (!expired && hasCurrentBar(publicMarket.fetch(symbol),symbol,PhoneRealtimeCandles.now()))
                            day = TradingCalendar.Day.OPEN;
                    }
                }
            } catch (Exception unavailable) {
                // A failed call and a list ending before today both leave the day unknown.
            } finally {
                cancelRequests();
                TradingCalendar.Day result = day;
                main.post(() -> finish(result));
            }
        }

        void cancelRequests() {
            if (client != null) client.cancel();
            if (publicMarket != null) publicMarket.cancel();
        }

        void finish(TradingCalendar.Day day) {
            if (pending != this) return;
            pending = null;
            main.removeCallbacks(timeout);
            if (day != TradingCalendar.Day.UNKNOWN && date.equals(PhoneRealtimeCandles.now().toLocalDate()))
                prefs.edit().putString(DATE,date.toString()).putBoolean(OPEN,day == TradingCalendar.Day.OPEN).apply();
            for (Runnable callback : callbacks) if (callback != null) callback.run();
        }
    }

    private static TradingCalendar.Day lookup(PhoneMcpClient client, LocalDate date) throws Exception {
        LocalDate start = date.minusDays(35), end = date.plusDays(35);
        JSONObject result = client.call("get_tdx_trading_calendar",new JSONObject().put("market","SH")
                .put("start_date",start.toString()).put("end_date",end.toString()));
        if (!start.equals(TradingCalendar.parseDate(result.getString("start_date")))
                || !end.equals(TradingCalendar.parseDate(result.getString("end_date"))))
            return TradingCalendar.Day.UNKNOWN;
        return TradingCalendar.fromTdx(result).day(date);
    }

    static boolean hasCurrentBar(String cached, String symbol, LocalDateTime now) {
        if (cached.isEmpty() || symbol.isEmpty()) return false;
        try { return hasCurrentBar(new JSONObject(cached),symbol,now); }
        catch (org.json.JSONException invalid) { return false; }
    }

    private static boolean hasCurrentBar(JSONObject data, String symbol, LocalDateTime now) {
        try {
            if (!symbol.equals(data.optString("symbol")) || !now.toLocalDate().toString().equals(data.optString("date"))) return false;
            LocalDateTime quoteAt = PhoneRealtimeCandles.localTime(data.optString("quote_at"));
            if (!PhoneRealtimeCandles.tradingMinute(quoteAt) || !quoteAt.toLocalDate().equals(now.toLocalDate())
                    || quoteAt.isAfter(now)) return false;
            JSONArray bars = data.getJSONArray("bars");
            JSONObject last = bars.getJSONObject(bars.length()-1);
            LocalDateTime at = PhoneRealtimeCandles.localTime(last.optString("time"));
            return PhoneRealtimeCandles.tradingMinute(at) && at.toLocalDate().equals(now.toLocalDate())
                    && !at.isAfter(PhoneRealtimeCandles.barEnd(now))
                    && PhoneRealtimeCandles.positive(last.optDouble("close"));
        } catch (Exception invalid) { return false; }
    }
}
