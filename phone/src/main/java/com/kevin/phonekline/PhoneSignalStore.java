package com.kevin.phonekline;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Accepted signal history is independent of replaceable price snapshots and subscription lifetimes. */
final class PhoneSignalStore {
    private static final String PREFIX = "accepted_signals_";

    private PhoneSignalStore() { }

    static void migrate(SharedPreferences prefs,String symbol) {
        String migrated = PREFIX + "migrated_" + symbol;
        if (prefs.getBoolean(migrated,false)) return;
        for (String key : new String[]{"snapshot_"+symbol,"daily_snapshot_"+symbol,"widget_candles"}) {
            try {
                JSONObject saved = new JSONObject(prefs.getString(key,"{}"));
                if (symbol.equals(PhoneRealtimeCandles.normalize(saved.optString("symbol")))) remember(prefs,saved);
            } catch (Exception invalid) { /* Other usable caches can still migrate. */ }
        }
        prefs.edit().putBoolean(migrated,true).apply();
    }

    static void remember(SharedPreferences prefs,JSONObject snapshot) {
        if (snapshot == null) return;
        String symbol = PhoneRealtimeCandles.normalize(snapshot.optString("symbol"));
        JSONArray incoming = snapshot.optJSONArray("fills");
        if (symbol.isEmpty() || incoming == null || incoming.length()==0) return;
        try {
            String saved = prefs.getString(PREFIX+symbol,"[]");
            String merged = PhoneSignalHistory.retain(read(prefs,symbol),incoming).toString();
            // Write as soon as a new signal is accepted, before quote/frame throttling or stop().
            if (!saved.equals(merged)) prefs.edit().putString(PREFIX+symbol,merged).commit();
        } catch (Exception invalid) { /* Invalid input cannot erase previously accepted signals. */ }
    }

    static JSONObject restore(SharedPreferences prefs,JSONObject snapshot) {
        if (snapshot == null) return null;
        String symbol = PhoneRealtimeCandles.normalize(snapshot.optString("symbol"));
        if (symbol.isEmpty()) return snapshot;
        try {
            JSONArray all = PhoneSignalHistory.retain(read(prefs,symbol),snapshot.optJSONArray("fills"));
            JSONArray visible = "1d".equals(snapshot.optString("period")) ? all
                    : PhoneSignalHistory.merge(snapshot.optString("date"),null,all);
            String previous = snapshot.optJSONArray("fills")==null ? "[]" : snapshot.optJSONArray("fills").toString();
            if (previous.equals(visible.toString())) return snapshot;
            return PhoneRealtimeCandles.copy(snapshot).put("fills",visible);
        } catch (Exception invalid) { return snapshot; }
    }

    static JSONObject accept(SharedPreferences prefs,JSONObject snapshot) {
        remember(prefs,snapshot);
        return restore(prefs,snapshot);
    }

    private static JSONArray read(SharedPreferences prefs,String symbol) {
        try { return new JSONArray(prefs.getString(PREFIX+symbol,"[]")); }
        catch (Exception invalid) { return new JSONArray(); }
    }
}
