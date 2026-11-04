package com.kevin.watchmcp;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 同一交易日的分钟线为基准。快照和盘口只叠加在与其日期相符的图上。 */
final class DialMarket {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService DETAILS = Executors.newSingleThreadExecutor();
    private static boolean loading;
    private static boolean detailLoading;
    private static String attemptedKey = "";
    private static long attemptedAt;
    private static McpDirectClient currentClient;
    private static String clientCode = "", clientUrl = "", clientKey = "";
    private static String detailedCode = "";
    private static long detailedAt;
    private static String selectedCode = "", latestQuote = "";
    private static long selection;

    static int minute(String time) {
        try {
            int h = Integer.parseInt(time.substring(0, 2)), m = Integer.parseInt(time.substring(3, 5));
            if (m < 0 || m > 59) return -1;
            int clock = h * 60 + m;
            if (clock >= 570 && clock <= 690) return clock - 570;
            if (clock >= 780 && clock <= 900) return clock - 660;
        } catch (RuntimeException ignored) { }
        return -1;
    }

    static String localStamp(String text) {
        String iso = text.replace(' ', 'T');
        try { return OffsetDateTime.parse(iso).atZoneSameInstant(SHANGHAI).toLocalDateTime().toString(); }
        catch (RuntimeException ignored) {
            try { return LocalDateTime.parse(iso).toString(); } catch (RuntimeException invalid) { return ""; }
        }
    }

    static String symbol(SharedPreferences prefs) { return prefs.getString("symbol", "sh600519"); }

    static JSONObject saved(SharedPreferences prefs) {
        try {
            String code = symbol(prefs);
            JSONObject data = new JSONObject(prefs.getString("dial_snapshot_" + code, prefs.getString("dial_snapshot", "{}")));
            if (symbol(prefs).equals(data.optString("symbol"))) return data;
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    static synchronized void refresh(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences("watch", Context.MODE_PRIVATE);
        DialCalendar.refresh(app);
        String code = symbol(prefs);
        // 切股即关闭旧股票的请求；不会遍历或预取自选列表。
        if (!code.equals(selectedCode)) {
            selectedCode=code;
            latestQuote="";
            selection++;
            if (currentClient != null) closeClient(currentClient);
        }
        if (loading || code.length() != 8 || android.os.Build.VERSION.SDK_INT < 33) return;
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        boolean trading = DialRefreshPolicy.isTradingWindow(now,DialCalendar.day(prefs,now.toLocalDate()));
        long elapsed = android.os.SystemClock.elapsedRealtime();
        boolean awake = app.getSystemService(android.os.PowerManager.class).isInteractive();
        // 休市时反复亮屏不能绕过低频限制；切股和开/收盘仍各触发一次刷新。
        String key = code + (trading && awake) + trading;
        long interval = DialRefreshPolicy.marketInterval(trading,awake,saved(prefs).has("bars"));
        if (key.equals(attemptedKey) && elapsed - attemptedAt < interval) return;
        loading = true;
        attemptedKey = key;
        attemptedAt = elapsed;
        long identity=selection;
        NETWORK.execute(() -> {
            try {
                requireSelected(prefs, code);
                McpDirectClient client = client(prefs, code);
                JSONObject args = new JSONObject().put("symbol", code.substring(2) + "." + code.substring(0, 2).toUpperCase(Locale.ROOT));
                startDetails(app,prefs,client,args,code,identity,elapsed);
                if (client == null) return;
                JSONObject quote;
                try { quote=tool(client,"get_tdx_snapshot",args,prefs,code).getJSONObject("snapshot"); }
                catch (Exception unavailable) { closeClient(client); throw unavailable; }
                synchronized (DialMarket.class) {
                    if (identity!=selection || !code.equals(symbol(prefs))) return;
                    latestQuote=quote.toString();
                    JSONObject data=saved(prefs);
                    if (!data.has("bars")) return;
                    applySnapshot(data,quote);
                    save(prefs,code,data);
                }
                DialProvider.redraw(app);
                android.util.Log.d("WatchDial", "refresh " + code + " ms=" + (android.os.SystemClock.elapsedRealtime()-elapsed));
            } catch (java.io.InterruptedIOException cancelled) { /* 切股后丢弃旧请求。 */ }
            catch (Exception error) { android.util.Log.w("WatchDial", "行情刷新失败 " + error.getClass().getSimpleName()); }
            finally {
                synchronized (DialMarket.class) { loading = false; }
                if (!code.equals(symbol(prefs))) refresh(app);
            }
        });
    }

    private static void save(SharedPreferences prefs, String code, JSONObject data) throws Exception {
        data.put("refreshed_at",System.currentTimeMillis());
        prefs.edit().putString("dial_snapshot_"+code,data.toString()).apply();
    }

    // 历史/千档有时耗时数秒，独立请求，不能挡住当前股票的一秒快照。
    private static synchronized void startDetails(Context app, SharedPreferences prefs, McpDirectClient client,
            JSONObject args, String code, long identity, long elapsed) {
        if (identity!=selection || detailLoading || code.equals(detailedCode) && elapsed-detailedAt<10000) return;
        detailLoading=true;
        detailedCode=code; detailedAt=elapsed;
        DETAILS.execute(() -> {
            try {
                requireSelected(prefs,code);
                JSONObject data=fetchBars(client,args,code,prefs);
                synchronized (DialMarket.class) {
                    if (identity!=selection || !code.equals(symbol(prefs))) return;
                    if (!latestQuote.isEmpty()) applySnapshot(data,new JSONObject(latestQuote));
                }
                if (client!=null) updateBook(client,args,code,prefs,data);
                synchronized (DialMarket.class) {
                    if (identity!=selection || !code.equals(symbol(prefs))) return;
                    JSONObject previous=saved(prefs);
                    if (data.optString("date").equals(previous.optString("date"))) {
                        if (!data.has("book") && previous.has("book")) data.put("book",previous.get("book"));
                        mergeCurrentCandle(data,previous);
                    }
                    if (!latestQuote.isEmpty()) applySnapshot(data,new JSONObject(latestQuote));
                    save(prefs,code,data);
                }
                DialProvider.redraw(app);
                android.util.Log.d("WatchDial","history " + code + " ms=" + (android.os.SystemClock.elapsedRealtime()-elapsed));
            } catch (java.io.InterruptedIOException cancelled) { /* 切股后丢弃旧请求。 */ }
            catch (Exception error) { android.util.Log.w("WatchDial","分钟历史暂未更新 " + error.getClass().getSimpleName()); }
            finally { synchronized (DialMarket.class) { detailLoading=false; } }
        });
    }

    private static void requireSelected(SharedPreferences prefs, String code) throws java.io.InterruptedIOException {
        if (!code.equals(symbol(prefs))) throw new java.io.InterruptedIOException("Stock changed");
    }

    private static JSONObject tool(McpDirectClient client, String name, JSONObject args, SharedPreferences prefs, String code) throws Exception {
        requireSelected(prefs,code);
        return client.tool(name,new JSONObject(args.toString()));
    }

    private static synchronized void closeClient(McpDirectClient client) {
        client.stop();
        if (currentClient==client) currentClient=null;
    }

    private static McpDirectClient client(SharedPreferences prefs, String code) throws Exception {
        String url=prefs.getString("mcp_url",""), token=SecretStore.load(prefs);
        McpDirectClient next;
        synchronized (DialMarket.class) {
            if (currentClient!=null && code.equals(clientCode) && url.equals(clientUrl) && token.equals(clientKey)) return currentClient;
            if (currentClient!=null) closeClient(currentClient);
            if (url.isEmpty() || token.isEmpty()) return null;
            requireSelected(prefs,code);
            next=new McpDirectClient(url,token,null,address -> {
                requireSelected(prefs,code);
                return WifiNetworkPolicy.open(address);
            });
            currentClient=next; clientCode=code; clientUrl=url; clientKey=token;
        }
        try { next.negotiate(); return next; }
        catch (Exception unavailable) { closeClient(next); return null; }
    }

    private static JSONObject fetchBars(McpDirectClient client, JSONObject args, String code, SharedPreferences prefs) throws Exception {
        JSONArray all = new JSONArray();
        if (client != null) try {
            all = tool(client,"get_tdx_kline",new JSONObject(args.toString()).put("period","1m").put("days",15).put("limit",241),prefs,code).getJSONArray("bars");
        } catch (Exception ignored) { }
        String last = all.length()==0 ? "" : all.getJSONObject(all.length()-1).getString("date");
        String fallbackName = code.substring(2);
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        // 本地通达信分钟库有时收盘后才落盘。盘中用带日期的分钟行情补齐。
        if (last.isEmpty() || java.time.Duration.between(LocalDateTime.parse(last.replace(' ','T')), now).toMinutes()>2
                && DialRefreshPolicy.isTradingWindow(now,DialCalendar.day(prefs,now.toLocalDate()))) try {
            requireSelected(prefs,code);
            JSONObject live = new MarketDirectClient(() -> code.equals(symbol(prefs))).sessionBars(code);
            JSONArray newer = live.getJSONArray("bars");
            String end = newer.getJSONObject(newer.length()-1).getString("time").replace(' ','T');
            if (end.compareTo(last.replace(' ','T')) >= 0) {
                all = newer; last = end; fallbackName = live.optString("name",fallbackName);
            }
        } catch (Exception ignored) { }
        if (last.length()<16) throw new java.io.IOException("No dated session");
        String date = last.substring(0,10);
        JSONArray bars = new JSONArray();
        double high = 0, low = Double.POSITIVE_INFINITY, previous = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.getJSONObject(i);
            String stamp = row.optString("date",row.optString("time"));
            if (!stamp.startsWith(date)) { previous = row.getDouble("close"); continue; }
            int m = minute(stamp.substring(11));
            double o = row.getDouble("open"), h = row.getDouble("high"), l = row.getDouble("low"), c = row.getDouble("close");
            if (m < 0 || !(l > 0 && h >= Math.max(c,o) && l <= Math.min(c,o))) continue;
            bars.put(new JSONArray().put(m).put(o).put(h).put(l).put(c));
            high = Math.max(h, high);
            low = Math.min(l, low);
        }
        if (bars.length() == 0) throw new java.io.IOException("No minute bars");
        double price = bars.getJSONArray(bars.length()-1).getDouble(4);
        JSONObject out = new JSONObject().put("symbol",code).put("date",date).put("bars",bars).put("time",last.substring(11,16));
        String name = fallbackName;
        try { name = new JSONObject(prefs.getString("stock_names","{}")).optString(code,name); }
        catch (Exception ignored) { }
        try {
            JSONObject chart = new JSONObject(prefs.getString("cache","{}")).getJSONObject("chart").getJSONObject("data");
            if (code.equals(Watchlist.normalize(chart.optString("symbol")))) name = chart.optString("name", name);
        } catch (Exception ignored) { }
        return out.put("name",name).put("price",price).put("high",Math.max(price,high)).put("low",Math.min(price,low)).put("previous",previous);
    }

    private static void updateBook(McpDirectClient client, JSONObject args, String code, SharedPreferences prefs, JSONObject data) {
        try {
            JSONObject book = tool(client,"get_tdx_depth",new JSONObject(args.toString()).put("requested_depth",DialBook.REQUESTED_DEPTH),prefs,code);
            String stamp = localStamp(book.optString("source_at"));
            String date=data.optString("date");
            LocalDateTime now=LocalDateTime.now(SHANGHAI);
            // 开盘前图表仍显示上一交易日，盘口独立记录最新快照的采集时间。
            boolean beforeAuction = now.toLocalTime().isBefore(java.time.LocalTime.of(9,15))
                    && stamp.startsWith(now.toLocalDate().toString()) && date.compareTo(now.toLocalDate().toString()) < 0;
            if (book.optBoolean("available") && book.optInt("requested_depth") == DialBook.REQUESTED_DEPTH
                    && (book.optBoolean("distribution_ready") || book.optBoolean("full_depth_verified"))
                    && code.equals(Watchlist.normalize(book.optString("symbol"))) && (stamp.startsWith(date) || beforeAuction)) {
                JSONObject profile = DialBook.profile(book.optJSONArray("levels"),data.optDouble("low"),data.optDouble("high"));
                if (profile != null) data.put("book",profile.put("source_at",stamp).put("requested_depth",DialBook.REQUESTED_DEPTH));
            }
        } catch (Exception ignored) { }
    }

    private static void mergeCurrentCandle(JSONObject data, JSONObject previous) throws Exception {
        JSONArray old=previous.optJSONArray("bars"), bars=data.optJSONArray("bars");
        if (old==null || old.length()==0 || bars==null || bars.length()==0) return;
        JSONArray last=old.getJSONArray(old.length()-1), current=bars.getJSONArray(bars.length()-1);
        if (last.getInt(0)>current.getInt(0)) bars.put(new JSONArray(last.toString()));
        else if (last.getInt(0)==current.getInt(0)) {
            current.put(2,Math.max(current.getDouble(2),last.getDouble(2)));
            current.put(3,Math.min(current.getDouble(3),last.getDouble(3)));
        }
    }

    // 一秒快照同步到当前一分钟蜡烛，而不是只改顶部数字或分时末端。
    static void applySnapshot(JSONObject data, JSONObject quote) throws Exception {
        String date=data.optString("date"), stamp=localStamp(quote.optString("datetime"));
        double price=quote.optDouble("last_price");
        if (date.isEmpty() || !stamp.startsWith(date) || !(price>0) || !Double.isFinite(price)
                || stamp.compareTo(data.optString("quote_at"))<0) return;
        String quotedSymbol=Watchlist.normalize(quote.optString("symbol"));
        if (quotedSymbol!=null && !quotedSymbol.equals(data.optString("symbol"))) return;
        double high=Math.max(data.optDouble("high",price),price), low=Math.min(data.optDouble("low",price),price);
        double qhigh=quote.optDouble("high_price"), qlow=quote.optDouble("low_price");
        if (Double.isFinite(qhigh) && qhigh>0) high=Math.max(high,qhigh);
        if (Double.isFinite(qlow) && qlow>0) low=Math.min(low,qlow);
        data.put("price",price).put("high",high).put("low",low).put("quote_at",stamp);
        double previous=quote.optDouble("pre_close");
        if (Double.isFinite(previous) && previous>0) data.put("previous",previous);
        int m=minute(stamp.substring(11));
        if (m<0) return;
        data.put("tick",m).put("time",stamp.substring(11,16));
        JSONArray bars=data.optJSONArray("bars");
        if (bars==null || bars.length()==0) return;
        int end=m==240 || (m==120 && stamp.substring(11,13).equals("11")) ? m : m+1;
        JSONArray last=bars.getJSONArray(bars.length()-1);
        if (last.getInt(0)>end) return;
        if (last.getInt(0)<end) bars.put(new JSONArray().put(end).put(price).put(price).put(price).put(price));
        else last.put(2,Math.max(last.getDouble(2),price)).put(3,Math.min(last.getDouble(3),price)).put(4,price);
    }
}
