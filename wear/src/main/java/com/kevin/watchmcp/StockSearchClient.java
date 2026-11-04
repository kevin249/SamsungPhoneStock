package com.kevin.watchmcp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 手表直连公开搜索接口；腾讯支持全拼，东财补充代码、名称和首字母搜索。 */
final class StockSearchClient {
    static final class Stock {
        final String symbol, name;
        Stock(String symbol, String name) { this.symbol = symbol; this.name = name; }
    }

    private static final Pattern HINT = Pattern.compile("\\bv_hint\\s*=\\s*(\"(?:\\\\.|[^\"\\\\])*\")\\s*;?\\s*$");

    List<Stock> search(String query) throws Exception {
        String text = query.trim().toLowerCase(Locale.ROOT);
        String normalized = Watchlist.normalize(text);
        if (normalized != null) text = normalized.substring(2);
        String encoded = URLEncoder.encode(text, "UTF-8");
        boolean available = false;
        try {
            List<Stock> rows = parseTencent(get("https://smartbox.gtimg.cn/s3/?q=" + encoded + "&t=all&v=2", "https://gu.qq.com/"));
            available = true;
            rows = exactMarket(rows, normalized);
            if (!rows.isEmpty()) return rows;
        } catch (Exception ignored) { }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try {
            List<Stock> rows = parseEastmoney(get("https://searchapi.eastmoney.com/api/suggest/get?input=" + encoded + "&type=14&count=12", "https://quote.eastmoney.com/"));
            available = true;
            rows = exactMarket(rows, normalized);
            if (!rows.isEmpty()) return rows;
        } catch (Exception ignored) { }
        if (!available) throw new IOException("搜索暂不可用，请检查网络后重试");
        return new ArrayList<>();
    }

    private static List<Stock> exactMarket(List<Stock> rows, String normalized) {
        if (normalized == null) return rows;
        rows.removeIf(row -> !normalized.equals(row.symbol));
        return rows;
    }

    static List<Stock> parseTencent(String text) throws Exception {
        Matcher match = HINT.matcher(text);
        if (!match.find()) throw new IOException("搜索响应格式异常");
        Object decoded = new JSONTokener(match.group(1)).nextValue();
        if (!(decoded instanceof String)) throw new IOException("搜索响应格式异常");
        List<Stock> rows = new ArrayList<>();
        for (String row : ((String) decoded).split("\\^")) {
            String[] fields = row.split("~");
            if (fields.length < 5 || !fields[0].matches("sh|sz|bj")
                    || !(fields[4].equals("GP-A") || fields[4].startsWith("GP-A-"))) continue;
            add(rows, fields[0] + fields[1], fields[2]);
        }
        return rows;
    }

    static List<Stock> parseEastmoney(String text) throws Exception {
        JSONObject table = new JSONObject(text).getJSONObject("QuotationCodeTable");
        if (table.optInt("Status", -1) != 0) throw new IOException("搜索响应格式异常");
        List<Stock> rows = new ArrayList<>();
        JSONArray data = table.optJSONArray("Data");
        if (data == null) return rows;
        for (int i = 0; i < data.length(); i++) {
            JSONObject row = data.optJSONObject(i);
            if (row == null || !"AStock".equals(row.optString("Classify"))) continue;
            String code = row.optString("Code"), market = row.optString("MktNum");
            if (!market.matches("0|1")) continue;
            String symbol = Watchlist.normalize(code);
            if (symbol != null && !symbol.startsWith("bj")) symbol = (market.equals("1") ? "sh" : "sz") + code;
            add(rows, symbol, row.optString("Name"));
        }
        return rows;
    }

    private static void add(List<Stock> rows, String symbol, String name) {
        if (symbol == null || !symbol.matches("(sh|sz|bj)(60|68|00|30|43|83|87|88|92)[0-9]{4}") || name.trim().isEmpty()) return;
        for (Stock row : rows) if (row.symbol.equals(symbol)) return;
        if (rows.size() < 12) rows.add(new Stock(symbol, name.trim()));
    }

    private String get(String address, String referer) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        HttpURLConnection connection = WifiNetworkPolicy.open(new URL(address));
        try {
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Referer", referer);
            if (connection.getResponseCode() != 200) throw new IOException("搜索暂不可用");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (output.size() + length > 262144) throw new IOException("搜索响应过大");
                    output.write(buffer, 0, length);
                }
                byte[] bytes = output.toByteArray();
                try {
                    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes)).toString();
                } catch (CharacterCodingException ignored) {
                    return new String(bytes, Charset.forName("GB18030"));
                }
            }
        } finally { connection.disconnect(); }
    }
}
