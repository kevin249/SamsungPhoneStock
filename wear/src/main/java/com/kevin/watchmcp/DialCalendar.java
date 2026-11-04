package com.kevin.watchmcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 手表直连 MCP 读取实际交易日历；日历单独缓存，不查询其他股票。 */
final class DialCalendar {
    static final String CACHE="dial_trading_calendar_v1";
    private static final ExecutorService NETWORK=Executors.newSingleThreadExecutor();
    private static String cachedText="", attemptedKey="";
    private static TradingCalendar cached;
    private static boolean loading;
    private static long attemptedAt;

    static TradingCalendar parse(JSONObject payload) throws Exception {
        if (!payload.optBoolean("ok") || !"tdx".equals(payload.optString("source"))
                || !"SH".equals(payload.optString("market"))) throw new IOException("Invalid calendar source");
        JSONArray rows=payload.getJSONArray("dates");
        if (rows.length()==0) throw new IOException("Empty calendar");
        if (rows.length()!=payload.getInt("row_count")) throw new IOException("Incomplete calendar");
        List<String> dates=new ArrayList<>();
        for (int i=0;i<rows.length();i++) dates.add(rows.getString(i));
        return new TradingCalendar(TradingCalendar.parseDate(payload.getString("start_date")),
                TradingCalendar.parseDate(payload.getString("end_date")),dates);
    }

    static synchronized TradingCalendar.Day day(SharedPreferences prefs, LocalDate date) {
        String text=prefs.getString(CACHE,"");
        if (!text.equals(cachedText)) {
            cachedText=text;
            cached=null;
            try { cached=parse(new JSONObject(text)); } catch (Exception ignored) { }
        }
        return cached==null ? TradingCalendar.Day.UNKNOWN : cached.day(date);
    }

    static void save(SharedPreferences prefs, JSONObject payload) throws Exception {
        // 完整验证后才覆盖，错误或半截响应不破坏可用的离线日历。
        parse(payload);
        JSONObject stored=new JSONObject(payload.toString()).put("fetched_at",System.currentTimeMillis());
        prefs.edit().putString(CACHE,stored.toString()).apply();
    }

    static synchronized void refresh(Context context) {
        Context app=context.getApplicationContext();
        SharedPreferences prefs=app.getSharedPreferences("watch",Context.MODE_PRIVATE);
        LocalDateTime now=LocalDateTime.now(DialMarket.SHANGHAI);
        TradingCalendar.Day today=day(prefs,now.toLocalDate());
        boolean session=DialRefreshPolicy.isSessionTime(now);
        // 已确认当天日历每天校验；未覆盖的新交易日开盘后每分钟尝试确认。
        long interval=today==TradingCalendar.Day.UNKNOWN ? (session ? 60000 : 900000) : 86400000;
        String key=now.toLocalDate()+":"+(today==TradingCalendar.Day.UNKNOWN && session);
        long elapsed=SystemClock.elapsedRealtime();
        if (loading || key.equals(attemptedKey) && elapsed-attemptedAt<interval) return;
        loading=true; attemptedKey=key; attemptedAt=elapsed;
        NETWORK.execute(() -> {
            McpDirectClient client=null;
            try {
                String address=prefs.getString("mcp_url",""), token=SecretStore.load(prefs);
                if (address.isEmpty() || token.isEmpty()) return;
                client=new McpDirectClient(address,token,null,WifiNetworkPolicy::open);
                client.negotiate();
                LocalDate date=LocalDate.now(DialMarket.SHANGHAI);
                LocalDate start=date.minusDays(400), end=date.plusDays(35);
                JSONObject result=client.tool("get_tdx_trading_calendar",new JSONObject().put("market","SH")
                        .put("start_date",start.toString()).put("end_date",end.toString()));
                if (!start.equals(TradingCalendar.parseDate(result.getString("start_date")))
                        || !end.equals(TradingCalendar.parseDate(result.getString("end_date"))))
                    throw new IOException("Calendar range mismatch");
                save(prefs,result);
                synchronized (DialCalendar.class) {
                    LocalDateTime confirmed=LocalDateTime.now(DialMarket.SHANGHAI);
                    attemptedKey=confirmed.toLocalDate()+":"+(day(prefs,confirmed.toLocalDate())==TradingCalendar.Day.UNKNOWN
                            && DialRefreshPolicy.isSessionTime(confirmed));
                }
                android.util.Log.i("WatchCalendar","tdx calendar saved entries="+result.getInt("row_count")+" today="+day(prefs,date));
            } catch (Exception error) {
                android.util.Log.w("WatchCalendar","交易日历暂不可用，保留缓存 " + error.getClass().getSimpleName());
            } finally {
                if (client!=null) client.stop();
                synchronized (DialCalendar.class) { loading=false; }
            }
        });
    }
}
