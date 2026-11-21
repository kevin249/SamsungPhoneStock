package com.kevin.watchmcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 同一交易日的分钟线为基准。快照和盘口只叠加在与其日期相符的图上。 */
final class DialMarket {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService DETAILS = Executors.newSingleThreadExecutor();
    private static final ExecutorService BOOKS = Executors.newSingleThreadExecutor();
    private static boolean loading;
    private static boolean retryOnWake;
    private static boolean detailLoading;
    private static boolean bookLoading;
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

    private static String quoteStamp(JSONObject quote) {
        String stamp = localStamp(quote.optString("datetime"));
        JSONObject raw = quote.optJSONObject("raw");
        if (raw == null || stamp.isEmpty()) return stamp;
        // 网关 datetime 是读取时间。旧版 RefreshNum 在午后仍可能停在早盘收盘值。
        int clock = raw.optInt("RefreshTime", -1);
        if (clock <= 0) return "";
        try {
            LocalDateTime read = LocalDateTime.parse(stamp);
            LocalDateTime market = read.toLocalDate().atTime(clock / 10000, clock / 100 % 100, clock % 100);
            // 早盘尚未切日的上一交易日收盘快照不能当作今天的报价。
            return market.isAfter(read) ? "" : market.toString();
        } catch (java.time.DateTimeException invalid) { return ""; }
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

    static synchronized void onTradingWake(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE);
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        if (!DialRefreshPolicy.isTradingWindow(now, DialCalendar.day(prefs, now.toLocalDate()))) return;
        JSONObject cached = saved(prefs);
        android.util.Log.d("WatchDial", "wake " + symbol(prefs) + " chart=" + cached.optString("time")
                + " quote=" + cached.optString("quote_at") + " saved=" + cached.optLong("refreshed_at"));
        // 睡眠期间挂起的旧连接不占用下一次亮屏请求；仍只保留一个在途快照。
        if (loading && currentClient != null && android.os.SystemClock.elapsedRealtime() - attemptedAt >= 10000) {
            retryOnWake = true;
            closeClient(currentClient);
        }
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
        PowerManager.WakeLock quoteLock = holdForFetch(app, trading && !awake);
        NETWORK.execute(() -> {
            try {
                requireSelected(prefs, code);
                McpDirectClient client = client(prefs, code);
                JSONObject args = new JSONObject().put("symbol", code.substring(2) + "." + code.substring(0, 2).toUpperCase(Locale.ROOT));
                startDetails(app,prefs,client,args,code,identity,elapsed,trading && !awake);
                if (client == null) return;
                JSONObject quote;
                try { quote=fetchQuote(client,args,prefs,code,trading); }
                catch (Exception unavailable) { closeClient(client); throw unavailable; }
                synchronized (DialMarket.class) {
                    if (identity!=selection || !code.equals(symbol(prefs))) return;
                    JSONObject data=saved(prefs);
                    if (!acceptQuote(data, quote, code, now, trading)) return;
                    latestQuote=quote.toString();
                    save(prefs,code,data);
                }
                DialProvider.redraw(app);
                android.util.Log.d("WatchDial", "refresh " + code + " ms=" + (android.os.SystemClock.elapsedRealtime()-elapsed));
            } catch (java.io.InterruptedIOException cancelled) { /* 切股后丢弃旧请求。 */ }
            catch (Exception error) { android.util.Log.w("WatchDial", "行情刷新失败 " + error.getClass().getSimpleName()); }
            finally {
                release(quoteLock);
                boolean retry;
                synchronized (DialMarket.class) {
                    loading = false;
                    retry = retryOnWake;
                    retryOnWake = false;
                }
                if (retry || !code.equals(symbol(prefs))) refresh(app);
            }
        });
    }

    static PowerManager.WakeLock holdForFetch(Context context, boolean needed) {
        if (!needed) return null;
        PowerManager.WakeLock lock = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchMcp:dial-fetch");
        lock.setReferenceCounted(false);
        lock.acquire(20000);
        return lock;
    }

    static void release(PowerManager.WakeLock lock) {
        if (lock != null && lock.isHeld()) lock.release();
    }

    private static void save(SharedPreferences prefs, String code, JSONObject data) throws Exception {
        data.put("refreshed_at",System.currentTimeMillis());
        prefs.edit().putString("dial_snapshot_"+code,data.toString()).apply();
    }

    // K 线到达就交给表盘；千档另行读取，绝不把请求前的整张图写回。
    private static synchronized void startDetails(Context app, SharedPreferences prefs, McpDirectClient client,
            JSONObject args, String code, long identity, long elapsed, boolean sleepingInSession) {
        if (identity!=selection || detailLoading || code.equals(detailedCode) && elapsed-detailedAt<10000) return;
        detailLoading=true;
        detailedCode=code; detailedAt=elapsed;
        PowerManager.WakeLock historyLock = holdForFetch(app, sleepingInSession);
        DETAILS.execute(() -> {
            try {
                requireSelected(prefs,code);
                JSONObject data=fetchBars(client,args,code,prefs);
                synchronized (DialMarket.class) {
                    if (identity!=selection || !code.equals(symbol(prefs))) return;
                    if (!mergeHistory(data, saved(prefs))) return;
                    if (!latestQuote.isEmpty()) applySnapshot(data,new JSONObject(latestQuote));
                    save(prefs,code,data);
                }
                DialProvider.redraw(app);
                android.util.Log.d("WatchDial","history " + code + " ms=" + (android.os.SystemClock.elapsedRealtime()-elapsed));
                if (client != null) {
                    startBook(app, prefs, client, args, code, identity, data);
                    try { loadReferenceClose(app, prefs, client, args, code, identity, data); }
                    catch (Exception unavailable) { android.util.Log.d("WatchDial", "快照昨收暂不可用"); }
                }
            } catch (java.io.InterruptedIOException cancelled) { /* 切股后丢弃旧请求。 */ }
            catch (Exception error) { android.util.Log.w("WatchDial","分钟历史暂未更新 " + error.getClass().getSimpleName()); }
            finally {
                release(historyLock);
                synchronized (DialMarket.class) { detailLoading=false; }
            }
        });
    }

    // 逐笔成交没有昨收。单独补一次同日快照的昨收，除权日也沿用行情源的涨跌幅基准。
    private static void loadReferenceClose(Context app, SharedPreferences prefs, McpDirectClient client,
            JSONObject args, String code, long identity, JSONObject chart) throws Exception {
        String date = chart.getString("date");
        if (chart.optBoolean("has_reference_close") || !date.equals(java.time.LocalDate.now(SHANGHAI).toString())) return;
        JSONObject quote = tool(client, "get_tdx_snapshot", args, prefs, code).getJSONObject("snapshot");
        double previous = quote.optDouble("pre_close");
        if (!(previous > 0) || !Double.isFinite(previous) || !code.equals(Watchlist.normalize(quote.optString("symbol")))
                || !localStamp(quote.optString("datetime")).startsWith(date)) return;
        synchronized (DialMarket.class) {
            if (identity != selection || !code.equals(symbol(prefs))) return;
            JSONObject current = saved(prefs);
            if (!date.equals(current.optString("date"))) return;
            current.put("previous", previous).put("has_reference_close", true);
            prefs.edit().putString("dial_snapshot_" + code, current.toString()).apply();
        }
        DialProvider.redraw(app);
    }

    private static synchronized void startBook(Context app, SharedPreferences prefs, McpDirectClient client,
            JSONObject args, String code, long identity, JSONObject chart) {
        if (identity != selection || bookLoading) return;
        bookLoading = true;
        BOOKS.execute(() -> {
            try {
                JSONObject book = fetchBook(client, args, code, prefs, chart);
                if (book == null) return;
                synchronized (DialMarket.class) {
                    if (identity != selection || !code.equals(symbol(prefs))) return;
                    JSONObject current = saved(prefs);
                    if (!chart.getString("date").equals(current.optString("date"))) return;
                    current.put("book", book);
                    // 盘口的到达时间不能冒充 K 线的刷新时间。
                    prefs.edit().putString("dial_snapshot_" + code, current.toString()).apply();
                }
                DialProvider.redraw(app, DialProvider.Book.class);
            } catch (Exception error) {
                android.util.Log.d("WatchDial", "盘口暂未更新 " + error.getClass().getSimpleName());
            } finally {
                synchronized (DialMarket.class) { bookLoading = false; }
            }
        });
    }

    private static void requireSelected(SharedPreferences prefs, String code) throws java.io.InterruptedIOException {
        if (!code.equals(symbol(prefs))) throw new java.io.InterruptedIOException("Stock changed");
    }

    private static JSONObject tool(McpDirectClient client, String name, JSONObject args, SharedPreferences prefs, String code) throws Exception {
        requireSelected(prefs,code);
        return client.tool(name,new JSONObject(args.toString()));
    }

    // 盘中用最近一笔真实成交的时间与价格配对。它可以独立于分钟历史和千档盘口返回。
    private static JSONObject fetchQuote(McpDirectClient client, JSONObject args, SharedPreferences prefs,
                                         String code, boolean trading) throws Exception {
        if (trading) try {
            JSONObject result = tool(client, "get_stock_active_trades", new JSONObject(args.toString()).put("limit", 1), prefs, code);
            JSONObject trade = tradeQuote(result, code);
            if (trade != null) return trade;
        } catch (Exception unavailable) { requireSelected(prefs, code); }
        return tool(client, "get_tdx_snapshot", args, prefs, code).getJSONObject("snapshot");
    }

    static JSONObject tradeQuote(JSONObject result, String code) throws Exception {
        if (!code.equals(Watchlist.normalize(result.optString("symbol")))) return null;
        JSONArray trades = result.optJSONArray("trades");
        JSONObject trade = trades == null ? null : trades.optJSONObject(0);
        if (trade == null) return null;
        String stamp = localStamp(trade.optString("source_at"));
        double price = trade.optDouble("price");
        if (stamp.isEmpty() || !(price > 0) || !Double.isFinite(price)) return null;
        return new JSONObject().put("symbol", code).put("datetime", stamp).put("last_price", price);
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
        LocalDateTime expected = DialRefreshPolicy.latestCompletedMinute(now, DialCalendar.day(prefs, now.toLocalDate()));
        String normalizedLast = localStamp(last);
        if (normalizedLast.isEmpty() || expected != null && LocalDateTime.parse(normalizedLast).isBefore(expected)) try {
            requireSelected(prefs,code);
            JSONObject live = new MarketDirectClient(() -> code.equals(symbol(prefs))).sessionBars(code, expected);
            JSONArray newer = live.getJSONArray("bars");
            String end = newer.getJSONObject(newer.length()-1).getString("time").replace(' ','T');
            if (localStamp(end).compareTo(normalizedLast) >= 0) {
                all = newer; last = end; fallbackName = live.optString("name",fallbackName);
            }
        } catch (Exception ignored) { }
        if (last.length()<16) throw new java.io.IOException("No dated session");
        String date = last.substring(0,10);
        JSONArray bars = new JSONArray();
        double high = 0, low = Double.POSITIVE_INFINITY, previous = 0;
        String lastAccepted = "";
        for (int i = 0; i < all.length(); i++) {
            JSONObject row = all.getJSONObject(i);
            String stamp = row.optString("date",row.optString("time"));
            if (stamp.length() < 16) continue;
            if (!stamp.startsWith(date)) { previous = row.getDouble("close"); continue; }
            int m = minute(stamp.substring(11));
            double o = row.optDouble("open"), h = row.optDouble("high"), l = row.optDouble("low"), c = row.optDouble("close");
            if (m < 0 || !Double.isFinite(h) || !(l > 0 && h >= Math.max(c,o) && l <= Math.min(c,o))) continue;
            bars.put(new JSONArray().put(m).put(o).put(h).put(l).put(c));
            lastAccepted = stamp;
            high = Math.max(h, high);
            low = Math.min(l, low);
        }
        if (bars.length() == 0) throw new java.io.IOException("No minute bars");
        double price = bars.getJSONArray(bars.length()-1).getDouble(4);
        JSONObject out = new JSONObject().put("symbol",code).put("date",date).put("bars",bars);
        String name = fallbackName;
        try { name = new JSONObject(prefs.getString("stock_names","{}")).optString(code,name); }
        catch (Exception ignored) { }
        try {
            JSONObject chart = new JSONObject(prefs.getString("cache","{}")).getJSONObject("chart").getJSONObject("data");
            if (code.equals(Watchlist.normalize(chart.optString("symbol")))) name = chart.optString("name", name);
        } catch (Exception ignored) { }
        // 有的源用该分钟结束时间标记尚在形成的蜡烛，只把已过去的整分钟当作下界。
        LocalDateTime barEnd = LocalDateTime.parse(localStamp(lastAccepted));
        LocalDateTime receivedMinute = LocalDateTime.now(SHANGHAI).withSecond(0).withNano(0);
        String through = (barEnd.isAfter(receivedMinute) ? receivedMinute : barEnd).toString();
        out.put("history_through", through).put("time", through.substring(11,16));
        return out.put("name",name).put("price",price).put("high",Math.max(price,high)).put("low",Math.min(price,low)).put("previous",previous);
    }

    private static JSONObject fetchBook(McpDirectClient client, JSONObject args, String code, SharedPreferences prefs, JSONObject data) throws Exception {
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
            if (profile != null) return profile.put("source_at",stamp).put("requested_depth",DialBook.REQUESTED_DEPTH);
        }
        return null;
    }

    static boolean mergeHistory(JSONObject data, JSONObject previous) throws Exception {
        int dayOrder = data.getString("date").compareTo(previous.optString("date"));
        if (dayOrder < 0) return false;
        if (dayOrder > 0 || !previous.has("bars")) return true;
        if (previous.has("book")) data.put("book", previous.get("book"));
        if (previous.optBoolean("has_reference_close"))
            data.put("previous", previous.getDouble("previous")).put("has_reference_close", true);
        else if (data.optDouble("previous", 0) <= 0 && previous.has("previous")) data.put("previous", previous.get("previous"));
        JSONArray old = previous.getJSONArray("bars"), fresh = data.getJSONArray("bars");
        if (old.length() == 0) return true;
        TreeMap<Integer, JSONArray> minutes = new TreeMap<>();
        for (int i = 0; i < old.length(); i++) {
            JSONArray bar = old.getJSONArray(i);
            minutes.put(bar.getInt(0), bar);
        }
        for (int i = 0; i < fresh.length(); i++) {
            JSONArray bar = fresh.getJSONArray(i);
            minutes.put(bar.getInt(0), bar);
        }
        JSONArray lastOld = old.getJSONArray(old.length()-1), lastFresh = fresh.getJSONArray(fresh.length()-1);
        if (lastOld.getInt(0) == lastFresh.getInt(0)) {
            lastFresh.put(2, Math.max(lastOld.getDouble(2), lastFresh.getDouble(2)));
            lastFresh.put(3, Math.min(lastOld.getDouble(3), lastFresh.getDouble(3)));
        }
        boolean newerTick = lastOld.getInt(0) == lastFresh.getInt(0)
                && previous.optString("quote_at").compareTo(data.optString("history_through")) > 0;
        if (newerTick) lastFresh.put(4, lastOld.getDouble(4));
        if (lastOld.getInt(0) > lastFresh.getInt(0) || newerTick) {
            for (String key : new String[]{"time", "price", "tick"}) {
                if (previous.has(key)) data.put(key, previous.get(key));
            }
        }
        if (previous.has("quote_at")) data.put("quote_at", previous.get("quote_at"));
        if (previous.optString("history_through").compareTo(data.optString("history_through")) > 0)
            data.put("history_through", previous.get("history_through"));
        data.put("low", Math.min(data.getDouble("low"), previous.optDouble("low", data.getDouble("low"))));
        data.put("high", Math.max(data.getDouble("high"), previous.optDouble("high", data.getDouble("high"))));
        data.put("bars", new JSONArray(minutes.values()));
        return true;
    }

    static boolean acceptQuote(JSONObject data, JSONObject quote, String code, LocalDateTime now, boolean trading) throws Exception {
        String stamp = quoteStamp(quote);
        double price = quote.optDouble("last_price");
        if (stamp.length() < 16 || !(price > 0) || !Double.isFinite(price)) return false;
        if (quote.has("symbol") && !code.equals(Watchlist.normalize(quote.optString("symbol")))) return false;
        String date = stamp.substring(0, 10);
        if (!date.equals(data.optString("date")) || !data.has("bars")) {
            // 早盘第一笔报价先建当前蜡烛；只补实际观察到的点，分钟历史到达后再补齐。
            if (!trading || !date.equals(now.toLocalDate().toString()) || minute(stamp.substring(11)) < 0
                    || date.compareTo(data.optString("date")) < 0) return false;
            data.remove("book");
            data.remove("quote_at");
            data.remove("history_through");
            data.remove("previous");
            data.remove("has_reference_close");
            data.remove("low");
            data.remove("high");
            data.put("date", date).put("bars", new JSONArray()).put("symbol", code);
            if (!data.has("name")) data.put("name", code.substring(2));
        }
        return applySnapshot(data, quote, stamp);
    }

    // 一秒快照同步到当前一分钟蜡烛，而不是只改顶部数字或分时末端。
    static boolean applySnapshot(JSONObject data, JSONObject quote) throws Exception {
        return applySnapshot(data, quote, quoteStamp(quote));
    }

    private static boolean applySnapshot(JSONObject data, JSONObject quote, String stamp) throws Exception {
        String date=data.optString("date");
        double price=quote.optDouble("last_price");
        if (date.isEmpty() || !stamp.startsWith(date) || !(price>0) || !Double.isFinite(price)
                || stamp.compareTo(data.optString("quote_at"))<0 || stamp.compareTo(data.optString("history_through"))<0) return false;
        String quotedSymbol=Watchlist.normalize(quote.optString("symbol"));
        if (quotedSymbol!=null && !quotedSymbol.equals(data.optString("symbol"))) return false;
        int m=minute(stamp.substring(11));
        int end=m==240 || (m==120 && stamp.substring(11,13).equals("11")) ? m : m+1;
        JSONArray bars=data.optJSONArray("bars");
        JSONArray last=bars==null || bars.length()==0 ? null : bars.getJSONArray(bars.length()-1);
        if (m>=0 && last!=null && last.getInt(0)>end) return false;
        double high=Math.max(data.optDouble("high",price),price), low=Math.min(data.optDouble("low",price),price);
        double qhigh=quote.optDouble("high_price"), qlow=quote.optDouble("low_price");
        if (Double.isFinite(qhigh) && qhigh>0) high=Math.max(high,qhigh);
        if (Double.isFinite(qlow) && qlow>0) low=Math.min(low,qlow);
        data.put("price",price).put("high",high).put("low",low).put("quote_at",stamp);
        double previous=quote.optDouble("pre_close");
        if (Double.isFinite(previous) && previous>0) data.put("previous",previous).put("has_reference_close",true);
        if (m<0) return true;
        data.put("tick",m).put("time",stamp.substring(11,16));
        if (bars==null) return true;
        if (last==null || last.getInt(0)<end) bars.put(new JSONArray().put(end).put(price).put(price).put(price).put(price));
        else last.put(2,Math.max(last.getDouble(2),price)).put(3,Math.min(last.getDouble(3),price)).put(4,price);
        return true;
    }
}
