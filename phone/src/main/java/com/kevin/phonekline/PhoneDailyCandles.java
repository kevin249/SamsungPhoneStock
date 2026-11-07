package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.TreeMap;

/** Unadjusted daily OHLC, kept separate from minute caches and their trading-session axis. */
final class PhoneDailyCandles {
    static JSONObject history(String symbol, String name, JSONArray rows) throws Exception {
        TreeMap<String, JSONObject> sorted = new TreeMap<>();
        String today = PhoneRealtimeCandles.now().toLocalDate().toString();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String time = row.optString("time",row.optString("date"));
            if (time.length() < 10) continue;
            String day = time.substring(0, 10);
            try { LocalDate.parse(day); } catch (Exception invalid) { continue; }
            double o = row.optDouble("open"), h = row.optDouble("high"), l = row.optDouble("low"), c = row.optDouble("close");
            if (day.compareTo(today) > 0 || !PhoneRealtimeCandles.positive(o) || !PhoneRealtimeCandles.positive(c)
                    || !PhoneRealtimeCandles.positive(l) || !Double.isFinite(h) || l > Math.min(o,c) || h < Math.max(o,c)) continue;
            sorted.put(day, PhoneRealtimeCandles.copy(row).put("time", day + " 15:00"));
        }
        if (sorted.isEmpty()) throw new java.io.IOException("暂无日 K 行情");
        while (sorted.size() > 120) sorted.pollFirstEntry();
        JSONArray bars = new JSONArray();
        for (JSONObject row : sorted.values()) bars.put(row);
        JSONObject last = bars.getJSONObject(bars.length()-1);
        return new JSONObject().put("symbol",symbol).put("name",name).put("period","1d").put("bars",bars)
                .put("date",sorted.lastKey()).put("quote_at",last.getString("time"))
                .put("price",last.getDouble("close")).put("high",last.getDouble("high")).put("low",last.getDouble("low"))
                .put("previous",bars.length()>1 ? bars.getJSONObject(bars.length()-2).getDouble("close") : JSONObject.NULL);
    }

    static JSONObject merge(JSONObject daily, JSONObject live) throws Exception {
        if (daily == null || live == null || !daily.optString("symbol").equals(live.optString("symbol"))) return daily;
        JSONObject result = PhoneRealtimeCandles.copy(daily);
        JSONArray rows = new JSONArray(daily.getJSONArray("bars").toString());
        JSONObject tail = rows.getJSONObject(rows.length()-1);
        String day = live.optString("date");
        double price = live.optDouble("price");
        if (day.length()!=10 || day.compareTo(daily.optString("date"))<0 || !PhoneRealtimeCandles.positive(price)) return result;
        boolean sameDay = tail.getString("time").startsWith(day);
        if (!sameDay) {
            JSONArray minutes = live.optJSONArray("bars");
            if (minutes == null || minutes.length()==0) return result;
            JSONObject first = minutes.getJSONObject(0);
            // Do not invent a daily open from a late intraday window.
            String stamp = first.optString("time");
            if (!stamp.startsWith(day) || stamp.substring(11).compareTo("09:31")>0) return result;
            tail = new JSONObject().put("time",day+" 15:00").put("open",first.getDouble("open"));
            rows.put(tail);
        }
        double high = live.optDouble("high"), low = live.optDouble("low");
        if (!PhoneRealtimeCandles.positive(high) || !PhoneRealtimeCandles.positive(low)) return result;
        tail.put("close",price).put("high",Math.max(price,Math.max(high,tail.optDouble("high",high))))
                .put("low",Math.min(price,Math.min(low,tail.optDouble("low",low))));
        result = history(daily.getString("symbol"),live.optString("name",daily.optString("name")),rows);
        for (String key : new String[]{"quote_at","previous","book","book_status","fills"})
            if (live.has(key)) result.put(key,live.get(key));
        return result;
    }
}
