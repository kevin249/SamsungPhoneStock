package com.kevin.watchmcp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/** 手表直接获取公开 K 线，按东财、腾讯、新浪降级，与 MCP 连接独立。 */
final class MarketDirectClient {
    private volatile HttpURLConnection connection;
    private final Map<String, JSONObject> cache = new HashMap<>();
    private final Map<String, Long> times = new HashMap<>();
    private final java.util.function.BooleanSupplier selected;

    MarketDirectClient() { this(() -> true); }
    MarketDirectClient(java.util.function.BooleanSupplier selected) { this.selected=selected; }

    JSONObject fetch(String symbol, String period) throws Exception {
        String key = symbol + ":" + period;
        long ttl = period.endsWith("m") ? 20000 : 60000;
        if (cache.containsKey(key) && System.currentTimeMillis() - times.get(key) < ttl) return cache.get(key);
        JSONObject data;
        try { data = eastmoney(symbol, period, 48); }
        catch (Exception primary) {
            try { data = tencent(symbol, period, 48); }
            catch (Exception secondary) { data = sina(symbol, period); }
        }
        if (!selected.getAsBoolean()) throw new java.io.InterruptedIOException("Stock changed");
        cache.put(key, data);
        times.put(key, System.currentTimeMillis());
        return data;
    }

    private String get(String address, String referer) throws Exception {
        if (!selected.getAsBoolean()) throw new java.io.InterruptedIOException("Stock changed");
        HttpURLConnection conn = WifiNetworkPolicy.open(new URL(address));
        connection = conn;
        try {
            if (!selected.getAsBoolean()) throw new java.io.InterruptedIOException("Stock changed");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Referer", referer);
            if (conn.getResponseCode() != 200) throw new IOException("Market unavailable");
            return McpDirectClient.readText(conn.getInputStream());
        } finally { conn.disconnect(); connection = null; }
    }

    private JSONObject envelope(String symbol, String period, String name, String source, String adjust, JSONArray bars) throws Exception {
        if (bars.length() == 0) throw new IOException("Empty market data");
        return new JSONObject().put("symbol", symbol).put("period", period).put("name", name.isEmpty() ? symbol.substring(2) : name)
                .put("period_label", "1d".equals(period) ? "日 K" : "1w".equals(period) ? "周 K" : "5m".equals(period) ? "5 分钟" : "1 分钟")
                .put("source_label", source).put("adjust", adjust).put("bars", bars);
    }

    private JSONObject bar(String stamp, Object opening, Object closing, Object high, Object low, Object volume) throws Exception {
        if (stamp.matches("\\d{12}")) stamp = stamp.substring(0, 4) + "-" + stamp.substring(4, 6) + "-" + stamp.substring(6, 8) + " " + stamp.substring(8, 10) + ":" + stamp.substring(10, 12);
        double open = Double.parseDouble(opening.toString()), close = Double.parseDouble(closing.toString());
        double highest = Double.parseDouble(high.toString()), lowest = Double.parseDouble(low.toString());
        if (!Double.isFinite(open) || !Double.isFinite(close) || !Double.isFinite(highest) || !Double.isFinite(lowest) || lowest <= 0 || highest < Math.max(open, close) || lowest > Math.min(open, close)) throw new IOException("Invalid candle");
        return new JSONObject().put("time", stamp).put("open", open).put("close", close).put("high", highest).put("low", lowest).put("volume", Double.parseDouble(volume.toString()));
    }

    JSONObject sessionBars(String symbol) throws Exception {
        try { return eastmoney(symbol, "1m", 241); }
        catch (Exception ignored) { return tencent(symbol, "1m", 241); }
    }

    private JSONObject eastmoney(String symbol, String period, int limit) throws Exception {
        int scale = "1d".equals(period) ? 101 : "1w".equals(period) ? 102 : "5m".equals(period) ? 5 : 1;
        String secid = (symbol.startsWith("sh") ? "1." : "0.") + symbol.substring(2);
        String address = "https://push2his.eastmoney.com/api/qt/stock/kline/get?fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56&ut=7eea3edcaed734bea9cbfc24409ed989&klt=" + scale + "&fqt=1&secid=" + secid + "&beg=0&end=20500101&lmt=" + limit;
        JSONObject data = new JSONObject(get(address, "https://quote.eastmoney.com/")).getJSONObject("data");
        JSONArray rows = data.getJSONArray("klines"), bars = new JSONArray();
        for (int i = Math.max(0, rows.length() - limit); i < rows.length(); i++) {
            String[] values = rows.getString(i).split(",");
            try { if (values.length >= 6) bars.put(bar(values[0], values[1], values[2], values[3], values[4], values[5])); }
            catch (Exception ignored) { }
        }
        return envelope(symbol, period, data.optString("name"), "东方财富", "qfq", bars);
    }

    private JSONObject tencent(String symbol, String period, int limit) throws Exception {
        boolean minute = period.endsWith("m");
        String kind = minute ? "m" + period.substring(0, period.length() - 1) : "1w".equals(period) ? "week" : "day";
        String param = symbol + "," + kind + (minute ? ",," + limit : ",,," + limit + ",qfq");
        String endpoint = minute ? "https://ifzq.gtimg.cn/appstock/app/kline/mkline" : "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
        JSONObject block = new JSONObject(get(endpoint + "?param=" + URLEncoder.encode(param, "UTF-8"), "https://gu.qq.com/")).getJSONObject("data").getJSONObject(symbol);
        JSONArray rows = block.optJSONArray("qfq" + kind);
        boolean adjusted = minute || rows != null;
        if (rows == null) rows = block.getJSONArray(kind);
        JSONArray bars = new JSONArray();
        for (int i = Math.max(0, rows.length() - limit); i < rows.length(); i++) {
            JSONArray row = rows.getJSONArray(i);
            try { bars.put(bar(row.getString(0), row.get(1), row.get(2), row.get(3), row.get(4), row.get(5))); }
            catch (Exception ignored) { }
        }
        String name = "";
        JSONObject qt = block.optJSONObject("qt");
        if (qt != null && qt.optJSONArray(symbol) != null) name = qt.getJSONArray(symbol).optString(1);
        return envelope(symbol, period, name, "腾讯", adjusted ? "qfq" : "none", bars);
    }

    private JSONObject sina(String symbol, String period) throws Exception {
        if ("1m".equals(period) || "1w".equals(period)) throw new IOException("No available market source");
        String body = get("https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData?symbol=" + symbol + "&scale=" + ("1d".equals(period) ? 240 : 5) + "&ma=no&datalen=48", "https://finance.sina.com.cn/");
        JSONArray rows = new JSONArray(body.replaceAll("([{,]\\s*)([A-Za-z_]\\w*)\\s*:", "$1\"$2\":")), bars = new JSONArray();
        for (int i = Math.max(0, rows.length() - 48); i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            try { bars.put(bar(row.getString("day"), row.get("open"), row.get("close"), row.get("high"), row.get("low"), row.getDouble("volume") / 100)); }
            catch (Exception ignored) { }
        }
        return envelope(symbol, period, "", "新浪", "none", bars);
    }

    void cancel() { HttpURLConnection conn = connection; if (conn != null) conn.disconnect(); }
}
