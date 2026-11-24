package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fills a missing snapshot after a foreground selection, even while scheduled refresh is idle. */
final class PhoneStockSelectionRefresh {
    private static final String TAG = "PhoneSelectionRefresh";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    // Managed on the main thread. The request can finish after the user locks the phone.
    private static final Set<String> PENDING = new HashSet<>();

    private PhoneStockSelectionRefresh() {}

    static void refresh(Context context, String code) {
        if (code.isEmpty() || PhoneLiveSchedule.canStartSession(context)) return;
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        boolean daily = !PhoneDisplayStocks.minis(prefs).contains(code) && "1d".equals(PhoneLiveSchedule.displayPeriod(app));
        String cacheKey = (daily ? "daily_snapshot_" : "snapshot_") + code;
        if (!needsSnapshot(prefs, code, cacheKey) || !PENDING.add(cacheKey)) return;
        NETWORK.execute(() -> {
            JSONObject result = null;
            try {
                if (needsSnapshot(prefs, code, cacheKey)) {
                    PhoneMarketClient market = new PhoneMarketClient();
                    String address = prefs.getString("mcp_url", "");
                    String secret = daily && address.startsWith("https://") ? PhoneSecretStore.load(prefs) : "";
                    result = daily ? PhoneDisplayFeed.fetchDaily(code, market,
                            secret.isEmpty() ? null : new PhoneMcpClient(address, secret)) : market.fetch(code);
                }
            } catch (Exception unavailable) {
                Log.d(TAG, "Snapshot unavailable for " + code);
            }
            JSONObject snapshot = result;
            MAIN.post(() -> {
                PENDING.remove(cacheKey);
                if (hasBars(snapshot) && code.equals(snapshot.optString("symbol"))
                        && needsSnapshot(prefs, code, cacheKey)) {
                    prefs.edit().putString(cacheKey, snapshot.toString()).apply();
                    PhoneMarketWidgetProvider.updateTheme(app);
                    Log.d(TAG, "Stored " + (daily ? "daily" : "minute") + " snapshot for " + code);
                }
            });
        });
    }

    private static boolean needsSnapshot(SharedPreferences prefs, String code, String key) {
        return !prefs.getBoolean("paused", false)
                && PhoneDisplayStocks.symbols(prefs).contains(code)
                && !hasBars(PhoneDisplayFeed.cached(prefs, key, code));
    }

    private static boolean hasBars(JSONObject snapshot) {
        JSONArray bars = snapshot == null ? null : snapshot.optJSONArray("bars");
        return bars != null && bars.length() > 0;
    }
}
