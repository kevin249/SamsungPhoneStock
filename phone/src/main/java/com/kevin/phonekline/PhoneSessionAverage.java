package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** Session turnover / traded shares, sealed at completed minute boundaries. */
final class PhoneSessionAverage {
    static final String AVERAGE = "session_average";
    private static final String SHARES = "volume_shares", YUAN = "amount_yuan";

    private PhoneSessionAverage() {}

    static void minuteTotals(JSONObject bar, double shares, double yuan) throws JSONException {
        if (Double.isFinite(shares) && Double.isFinite(yuan) && shares >= 0 && yuan >= 0
                && (shares == 0) == (yuan == 0)) {
            bar.put(SHARES, shares).put(YUAN, yuan);
        }
    }

    static void copyInputs(JSONObject from, JSONObject to) throws JSONException {
        minuteTotals(to, from.optDouble(SHARES), from.optDouble(YUAN));
        double average = from.optDouble(AVERAGE);
        if (PhoneRealtimeCandles.positive(average)) to.put(AVERAGE, average);
    }

    // Calculate per feed, before merging. Tencent separates 09:30 auction volume while
    // Eastmoney's first 09:31 bar already includes it. Mixing increments double-counts it.
    static void sealHistory(JSONArray bars, LocalDateTime requested) throws JSONException {
        double shares = 0, yuan = 0;
        boolean continuous = true;
        int lastSlot = -1;
        String cutoff = PhoneRealtimeCandles.BAR.format(requested);
        for (int i = 0; i < bars.length(); i++) {
            JSONObject row = bars.getJSONObject(i);
            String key = row.getString("time");
            int slot = PhoneMarketPlot.sessionMinute(key);
            double count = row.optDouble(SHARES), amount = row.optDouble(YUAN);
            row.remove(SHARES);
            row.remove(YUAN);
            if (slot < 0 || key.compareTo(cutoff) > 0 || row.has("first_tick_at")) {
                row.remove(AVERAGE);
                continuous = false;
                continue;
            }
            continuous &= (i == 0 ? slot == 0 || slot == 1 : slot == lastSlot + 1)
                    && Double.isFinite(count) && Double.isFinite(amount);
            lastSlot = slot;
            if (continuous) {
                shares += count;
                yuan += amount;
                double vwap = yuan / shares;
                if (PhoneRealtimeCandles.positive(vwap)) row.put(AVERAGE, vwap);
            }
        }
    }

    static Map<String, Double> prices(JSONObject snapshot) {
        Map<String, Double> result = new HashMap<>();
        if (snapshot == null) return result;
        String day = snapshot.optString("date");
        if (day.length() != 10) return result;
        String prefix = day + " ";
        LocalDateTime through = PhoneRealtimeCandles.localTime(snapshot.optString("quote_at"));
        String cutoff = through == null ? null : PhoneRealtimeCandles.BAR.format(through);
        JSONArray bars = snapshot.optJSONArray("bars");
        for (int i = 0; bars != null && i < bars.length(); i++) {
            JSONObject row = bars.optJSONObject(i);
            if (row == null) continue;
            double average = row.optDouble(AVERAGE);
            String key = row.optString("time");
            if (!PhoneRealtimeCandles.positive(average) || key.length() != 16 || !key.startsWith(prefix)) continue;
            if (PhoneMarketPlot.sessionMinute(key) >= 0 && (cutoff == null || key.compareTo(cutoff) <= 0)) {
                result.put(key, average);
            }
        }
        return result;
    }
}
