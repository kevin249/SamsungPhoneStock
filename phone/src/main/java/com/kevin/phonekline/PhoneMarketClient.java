package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/** 与手表相同的公开分钟 K 线来源；一次只请求当前股票。 */
final class PhoneMarketClient {
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    JSONObject fetch(String symbol) throws Exception {
        JSONObject primary = null;
        try {
            primary = eastmoney(symbol);
            if (PhoneRealtimeCandles.currentHistory(primary)) return primary;
        } catch (Exception unavailable) { if (cancelled) throw unavailable; }
        try {
            JSONObject other = tencent(symbol);
            return primary == null || other.getString("quote_at").compareTo(primary.getString("quote_at")) >= 0 ? other : primary;
        } catch (Exception unavailable) { if (primary == null) throw unavailable; return primary; }
    }

    JSONObject fetchDaily(String symbol) throws Exception {
        try {
            String secid = (symbol.startsWith("sh") ? "1." : "0.") + symbol.substring(2);
            JSONObject data = new JSONObject(get("https://push2his.eastmoney.com/api/qt/stock/kline/get?fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55&ut=7eea3edcaed734bea9cbfc24409ed989&klt=101&fqt=0&secid=" + secid + "&beg=0&end=20500101&lmt=120", "https://quote.eastmoney.com/")).getJSONObject("data");
            if (!symbol.substring(2).equals(data.optString("code"))) throw new IOException("标的不符");
            JSONArray rows = data.getJSONArray("klines"), bars = new JSONArray();
            for (int i=0;i<rows.length();i++) {
                String[] v=rows.getString(i).split(",");
                if (v.length>=5) bars.put(new JSONObject().put("time",v[0]).put("open",Double.parseDouble(v[1]))
                        .put("close",Double.parseDouble(v[2])).put("high",Double.parseDouble(v[3])).put("low",Double.parseDouble(v[4])));
            }
            return PhoneDailyCandles.history(symbol,data.optString("name"),bars);
        } catch (Exception unavailable) { if (cancelled) throw unavailable; }
        String param=URLEncoder.encode(symbol+",day,,,120,",StandardCharsets.UTF_8.name());
        JSONObject data=new JSONObject(get("https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param="+param,"https://gu.qq.com/"))
                .getJSONObject("data").getJSONObject(symbol);
        JSONArray rows=data.getJSONArray("day"), bars=new JSONArray();
        for (int i=0;i<rows.length();i++) {
            JSONArray v=rows.getJSONArray(i);
            bars.put(new JSONObject().put("time",v.getString(0)).put("open",v.getDouble(1)).put("close",v.getDouble(2))
                    .put("high",v.getDouble(3)).put("low",v.getDouble(4)));
        }
        JSONObject qt=data.optJSONObject("qt");
        JSONArray fields=qt==null?null:qt.optJSONArray(symbol);
        return PhoneDailyCandles.history(symbol,fields==null?"":fields.optString(1),bars);
    }

    private String get(String address, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        connection = conn;
        try {
            if (cancelled) throw new java.io.InterruptedIOException("会话已停止");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", referer);
            if (conn.getResponseCode() != 200) throw new IOException("行情接口不可用");
            try (InputStream stream = conn.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int size;
                while ((size = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, size);
                    if (buffer.size() > 2 * 1024 * 1024) throw new IOException("行情响应过大");
                }
                return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { conn.disconnect(); connection = null; }
    }

    private JSONObject bar(String stamp, String open, String close, String high, String low) throws Exception {
        if (stamp.matches("\\d{12}")) stamp = stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-" + stamp.substring(6, 8) + " " + stamp.substring(8, 10) + ":" + stamp.substring(10, 12);
        double o = Double.parseDouble(open), c = Double.parseDouble(close), h = Double.parseDouble(high), l = Double.parseDouble(low);
        if (!Double.isFinite(o) || !Double.isFinite(c) || !Double.isFinite(h) || !Double.isFinite(l)
                || l <= 0 || h < Math.max(o, c) || l > Math.min(o, c)) throw new IOException("无效 K 线");
        return new JSONObject().put("time", stamp).put("open", o).put("close", c).put("high", h).put("low", l);
    }

    static JSONObject tdxHistory(String symbol, JSONArray rows, LocalDateTime requested) throws Exception {
        JSONArray bars = new JSONArray();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject raw = rows.optJSONObject(i);
            if (raw == null) continue;
            JSONObject bar = PhoneRealtimeCandles.copy(raw);
            // The MCP adapter returns get_market_data history in shares and canonical yuan.
            PhoneSessionAverage.minuteTotals(bar, raw.optDouble("volume"), raw.optDouble("amount"));
            bars.put(bar);
        }
        return PhoneRealtimeCandles.history(symbol, "", "通达信", bars, requested);
    }

    private JSONObject eastmoney(String symbol) throws Exception {
        String secid = (symbol.startsWith("sh") ? "1." : "0.") + symbol.substring(2);
        String address = "https://push2his.eastmoney.com/api/qt/stock/kline/get?fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57&ut=7eea3edcaed734bea9cbfc24409ed989&klt=1&fqt=0&secid=" + secid + "&beg=0&end=20500101&lmt=241";
        LocalDateTime requested = PhoneRealtimeCandles.now();
        JSONObject data = new JSONObject(get(address, "https://quote.eastmoney.com/")).getJSONObject("data");
        if (!symbol.substring(2).equals(data.optString("code"))) throw new IOException("标的不符");
        JSONArray rows = data.getJSONArray("klines"), bars = new JSONArray();
        for (int i = Math.max(0, rows.length() - 241); i < rows.length(); i++) {
            String[] values = rows.getString(i).split(",");
            try {
                if (values.length < 5) continue;
                JSONObject bar = bar(values[0], values[1], values[2], values[3], values[4]);
                bars.put(bar);
                if (values.length >= 7) {
                    PhoneSessionAverage.minuteTotals(bar, Double.parseDouble(values[5]) * 100, Double.parseDouble(values[6]));
                }
            }
            catch (Exception ignored) { }
        }
        JSONObject history = PhoneRealtimeCandles.history(symbol, data.optString("name"), "东方财富", bars, requested);
        // preKPrice is the reference before the first returned K. Use it as
        // previous close only when the response begins at this session's open.
        if (bars.length() > 0 && history.has("high")
                && bars.getJSONObject(0).getString("time").startsWith(history.getString("date"))) {
            double previous = data.optDouble("preKPrice");
            if (PhoneRealtimeCandles.positive(previous)) history.put("previous", previous);
        }
        return history;
    }

    private JSONObject tencent(String symbol) throws Exception {
        LocalDateTime requested = PhoneRealtimeCandles.now();
        String param = URLEncoder.encode(symbol + ",m1,,241", StandardCharsets.UTF_8.name());
        JSONObject data = new JSONObject(get("https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=" + param,
                "https://gu.qq.com/")).getJSONObject("data").getJSONObject(symbol);
        JSONArray rows = data.getJSONArray("m1"), bars = new JSONArray();
        for (int i = Math.max(0, rows.length() - 241); i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            try { bars.put(bar(row.getString(0), row.getString(1), row.getString(2), row.getString(3), row.getString(4))); }
            catch (Exception ignored) { }
        }
        String name = "";
        JSONObject quote = data.optJSONObject("qt");
        if (quote != null && quote.optJSONArray(symbol) != null) name = quote.optJSONArray(symbol).optString(1);
        // m1 contains minute volume but no turnover. The dated intraday feed carries
        // cumulative lots and yuan, including the opening auction. Join only actual minute keys.
        try {
            JSONObject intraday = new JSONObject(get("https://ifzq.gtimg.cn/appstock/app/minute/query?code=" + symbol,
                    "https://gu.qq.com/")).getJSONObject("data").getJSONObject(symbol).getJSONObject("data");
            attachTencentAverages(bars, intraday);
        } catch (Exception unavailable) {
            if (cancelled) throw unavailable;
        }
        JSONObject history = PhoneRealtimeCandles.history(symbol, name, "腾讯", bars, requested);
        JSONArray fields = quote == null ? null : quote.optJSONArray(symbol);
        if (fields != null && symbol.substring(2).equals(fields.optString(2))) {
            String asOf = fields.optString(30);
            if (asOf.length() >= 8 && asOf.substring(0,8).equals(history.getString("date").replace("-", ""))) {
                PhoneRealtimeCandles.references(history, fields.optDouble(4), fields.optDouble(33), fields.optDouble(34));
            }
        }
        return history;
    }

    static void attachTencentAverages(JSONArray bars, JSONObject intraday) throws Exception {
        String date = intraday.optString("date");
        JSONArray rows = intraday.optJSONArray("data");
        if (!date.matches("[0-9]{8}") || rows == null) return;
        String day = date.substring(0,4) + "-" + date.substring(4,6) + "-" + date.substring(6);
        Map<String, Double> prices = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            String[] fields = rows.optString(i).trim().split("\\s+");
            if (fields.length < 4 || !fields[0].matches("[0-9]{4}")) continue;
            String minute = day + " " + fields[0].substring(0,2) + ":" + fields[0].substring(2);
            if (PhoneMarketPlot.sessionMinute(minute) < 0) continue;
            try {
                double shares = Double.parseDouble(fields[2]) * 100;
                double yuan = Double.parseDouble(fields[3]);
                if (PhoneRealtimeCandles.positive(shares) && PhoneRealtimeCandles.positive(yuan)) {
                    double average = yuan / shares;
                    if (PhoneRealtimeCandles.positive(average)) prices.put(minute, average);
                }
            } catch (NumberFormatException absentTotals) { /* Keep this minute unquoted. */ }
        }
        for (int i = 0; i < bars.length(); i++) {
            JSONObject bar = bars.getJSONObject(i);
            Double average = prices.get(bar.optString("time"));
            if (average != null) bar.put(PhoneSessionAverage.AVERAGE, average);
        }
    }

    void cancel() { cancelled = true; HttpURLConnection current = connection; if (current != null) current.disconnect(); }
}
