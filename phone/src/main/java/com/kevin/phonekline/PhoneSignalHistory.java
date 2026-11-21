package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.TreeMap;

/** Date-scoped confirmed B/S markers. Notification expiry must not erase chart history. */
final class PhoneSignalHistory {
    static JSONArray read(JSONObject data, String symbol, String date) throws Exception {
        JSONArray out = new JSONArray();
        if (data == null || !data.optBoolean("available") || data.optBoolean("stale_version")
                || !symbol.equals(PhoneRealtimeCandles.normalize(data.optString("symbol")))
                || !date.equals(data.optString("trade_date"))) return out;
        JSONArray rows = data.optJSONArray("markers");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !row.optBoolean("l2_confirmed")) continue;
            String side = row.optString("signal");
            if (!("B".equals(side) || "S".equals(side))) continue;
            String time = row.optString("time");
            if (time.length() == 5) time = date + " " + time + ":00";
            out.put(new JSONObject().put("time",time).put("bar_time",time)
                    .put("side",side).put("price",row.optDouble("price",0)));
        }
        return merge(date, null, out);
    }

    static JSONArray merge(String date, JSONArray old, JSONArray incoming) throws Exception {
        return collect(date,old,incoming);
    }

    static JSONArray retain(JSONArray old,JSONArray incoming) throws Exception {
        return collect(null,old,incoming);
    }

    private static JSONArray collect(String date,JSONArray old,JSONArray incoming) throws Exception {
        TreeMap<String,JSONObject> marks = new TreeMap<>();
        for (JSONArray rows : new JSONArray[]{old,incoming}) {
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                java.time.LocalDateTime at = PhoneRealtimeCandles.localTime(row.optString("bar_time"));
                String side = row.optString("side");
                if (!PhoneRealtimeCandles.tradingMinute(at) || date != null && !at.toLocalDate().toString().equals(date)
                        || !("B".equals(side) || "S".equals(side))
                        || !PhoneRealtimeCandles.positive(row.optDouble("price"))) continue;
                String minute = PhoneRealtimeCandles.BAR.format(at);
                marks.put(minute + side, PhoneRealtimeCandles.copy(row).put("bar_time",minute));
            }
        }
        JSONArray out = new JSONArray();
        for (JSONObject row : marks.values()) out.put(row);
        return out;
    }
}
