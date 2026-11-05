package com.kevin.phonekline;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Dated, unadjusted exchange prices. Published JSON objects are treated as immutable. */
final class PhoneRealtimeCandles {
    static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter BAR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private PhoneRealtimeCandles() { }

    static LocalDateTime now() { return LocalDateTime.now(SHANGHAI); }
    static boolean tradingWindowNow() { return tradingMinute(now()); }

    static boolean tradingMinute(LocalDateTime at) {
        if (at == null || at.getDayOfWeek().getValue() >= 6) return false;
        int m = at.getHour() * 60 + at.getMinute();
        return m >= 570 && m <= 690 || m >= 780 && m <= 900;
    }

    static LocalDateTime barEnd(LocalDateTime at) {
        LocalDateTime end = at.truncatedTo(ChronoUnit.MINUTES);
        if (end.toLocalTime().equals(LocalTime.of(11,30)) || end.toLocalTime().equals(LocalTime.of(15,0))) return end;
        return end.plusMinutes(1);
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.matches("(sh|sz|bj)[0-9]{6}")) return s;
        if (s.matches("[0-9]{6}\\.(sh|sz|bj)")) return s.substring(7) + s.substring(0,6);
        if (s.matches("[0-9]{6}")) return (s.matches("(00|30|20|15|16|18|39).*") ? "sz" :
                s.matches("(43|83|87|88|92).*") ? "bj" : "sh") + s;
        return "";
    }

    static LocalDateTime localTime(String stamp) {
        String iso = stamp.replace(' ', 'T');
        try { return OffsetDateTime.parse(iso).atZoneSameInstant(SHANGHAI).toLocalDateTime(); }
        catch (RuntimeException ignored) {
            try { return LocalDateTime.parse(iso); } catch (RuntimeException invalid) { return null; }
        }
    }

    static boolean positive(double x) { return x > 0 && Double.isFinite(x); }

    static JSONObject copy(JSONObject src) throws JSONException {
        JSONObject out = new JSONObject();
        for (Iterator<String> keys = src.keys(); keys.hasNext();) {
            String k = keys.next();
            out.put(k, src.get(k));
        }
        return out;
    }

    static JSONObject history(String symbol, String name, String source, JSONArray rows, LocalDateTime now) throws Exception {
        TreeMap<String,JSONObject> sorted = new TreeMap<>();
        for (int i=0; i<rows.length(); i++) {
            JSONObject v = rows.optJSONObject(i);
            if (v == null) continue;
            LocalDateTime at = localTime(v.optString("time",v.optString("date")));
            if (!tradingMinute(at) || at.isAfter(now.plusMinutes(1))) continue;
            double o=v.optDouble("open"), c=v.optDouble("close"), h=v.optDouble("high"), l=v.optDouble("low");
            if (!positive(l) || !positive(h) || !positive(o) || !positive(c) || h<Math.max(c,o) || l>Math.min(o,c)) continue;
            String minute = BAR.format(at);
            JSONObject bar=new JSONObject().put("time",minute).put("open",o).put("high",h).put("low",l).put("close",c);
            PhoneSessionAverage.copyInputs(v,bar);
            LocalDateTime firstTick=localTime(v.optString("first_tick_at"));
            if (tradingMinute(firstTick) && BAR.format(barEnd(firstTick)).equals(minute))
                bar.put("first_tick_at",firstTick.toString());
            sorted.put(minute,bar);
        }
        if (sorted.isEmpty()) throw new IOException("没有有日期的一分钟K线");
        String end = sorted.lastKey(), day = end.substring(0,10);
        JSONArray bars = new JSONArray();
        double high = 0, low = Double.POSITIVE_INFINITY;
        for (JSONObject v : sorted.tailMap(day).values()) {
            high=Math.max(high,v.getDouble("high")); low=Math.min(low,v.getDouble("low"));
            bars.put(v);
        }
        PhoneSessionAverage.sealHistory(bars, now);
        LocalDateTime last = localTime(end);
        LocalDateTime receivedMinute = now.truncatedTo(ChronoUnit.MINUTES);
        String stamp = STAMP.format(last.isAfter(receivedMinute) ? receivedMinute : last);
        JSONObject out = new JSONObject().put("symbol",symbol).put("name",name.isEmpty()?symbol.substring(2):name)
                .put("source",source).put("date",day).put("bars",bars).put("quote_at",stamp)
                .put("price",sorted.lastEntry().getValue().getDouble("close"));
        // A truncated or gapped window is evidence of visible extrema only.
        int lastIndex = -1;
        boolean complete = true;
        for (int i=0;i<bars.length();i++) {
            LocalDateTime minute = localTime(bars.getJSONObject(i).getString("time"));
            int clock = minute.getHour()*60+minute.getMinute();
            int index = clock <= 690 ? clock-570 : clock-660;
            if ((i==0 ? index>1 : index>lastIndex+1) || bars.getJSONObject(i).has("first_tick_at")) complete=false;
            lastIndex=index;
        }
        if (complete) references(out,Double.NaN,high,low);
        return out;
    }

    static void references(JSONObject out, double previous, double high, double low) throws JSONException {
        if (positive(previous)) out.put("previous",previous);
        if (positive(high) && positive(low) && high >= low) {
            out.put("high",Math.max(high,out.optDouble("high",high)));
            out.put("low",Math.min(low,out.optDouble("low",low)));
        }
    }

    static boolean currentHistory(JSONObject data) {
        LocalDateTime at = localTime(data.optString("quote_at")), now = now();
        if (at==null || !at.toLocalDate().equals(now.toLocalDate())) return false;
        LocalTime wall = now.toLocalTime();
        LocalTime expected = wall.isAfter(LocalTime.of(15,0)) ? LocalTime.of(15,0) :
                wall.isAfter(LocalTime.of(11,30)) && wall.isBefore(LocalTime.of(13,0)) ? LocalTime.of(11,30) : wall;
        return !at.isBefore(now.toLocalDate().atTime(expected).minusMinutes(2));
    }

    static JSONObject mergeHistory(JSONObject current, JSONObject hist) throws Exception {
        if (!hist.optString("symbol").equals(current.optString("symbol"))) return current;
        int dayOrder=hist.getString("date").compareTo(current.optString("date"));
        if (dayOrder<0) return current;
        if (dayOrder>0) {
            if (hist.getString("name").equals(hist.getString("symbol").substring(2)) && current.has("name")) {
                JSONObject named=copy(hist);
                named.put("name",current.getString("name"));
                return named;
            }
            return hist;
        }
        JSONObject out = copy(current);
        JSONArray previous=current.optJSONArray("bars"), fresh=hist.getJSONArray("bars");
        TreeMap<String,JSONObject> ordered=new TreeMap<>();
        if (previous!=null) for (int i=0;i<previous.length();i++) {
            JSONObject row=previous.getJSONObject(i);
            ordered.put(row.getString("time"),row);
        }
        String previousStamp=current.optString("quote_at"), newStamp=hist.optString("quote_at");
        boolean newer = newStamp.compareTo(previousStamp)>0 || !current.has("price") ||
                newStamp.equals(previousStamp) && !current.optString("source").equals("MCP 逐笔成交")
                && hist.optLong("history_request",0)>=current.optLong("history_request",0);
        for (int i=0;i<fresh.length();i++) {
            JSONObject row=fresh.getJSONObject(i);
            String key=row.getString("time");
            JSONObject old=ordered.get(key);
            // A truncated feed or an older cache may have OHLC only. Preserve the other
            // feed's verified session VWAP for this same completed trading minute.
            if (old!=null && !row.has(PhoneSessionAverage.AVERAGE) && old.has(PhoneSessionAverage.AVERAGE)) {
                row=copy(row);
                row.put(PhoneSessionAverage.AVERAGE,old.get(PhoneSessionAverage.AVERAGE));
            }
            // A tick can be newer than a late REST reply even within the same minute.
            if (old!=null && i==fresh.length()-1) {
                row=copy(row);
                row.put("high",Math.max(old.getDouble("high"),row.getDouble("high")));
                row.put("low",Math.min(old.getDouble("low"),row.getDouble("low")));
                if (!newer) row.put("close",old.getDouble("close"));
            }
            ordered.put(key,row);
        }
        JSONArray bars=new JSONArray();
        for (JSONObject row : ordered.values()) bars.put(row);
        out.put("bars",bars);
        if (newer) out.put("quote_at",newStamp).put("price",hist.getDouble("price")).put("source",hist.optString("source"))
                .put("history_request",hist.optLong("history_request",0));
        if (!hist.getString("name").equals(hist.getString("symbol").substring(2)) || !out.has("name")) out.put("name",hist.getString("name"));
        references(out,hist.optDouble("previous"),hist.optDouble("high"),hist.optDouble("low"));
        // If a verified full session joins newer ticks, retain subsequent highs and lows.
        if (out.has("high") && out.has("low")) for (JSONObject row:ordered.values())
            references(out,Double.NaN,row.getDouble("high"),row.getDouble("low"));
        return out;
    }

    static JSONObject apply(JSONObject chart, JSONObject payload, String symbol) throws Exception {
        return apply(chart,payload,symbol,now());
    }

    static JSONObject apply(JSONObject chart, JSONObject payload, String symbol, LocalDateTime now) throws Exception {
        if (!symbol.equals(chart.optString("symbol")) || !symbol.equals(normalize(payload.optString("symbol"))) ||
                !payload.optBoolean("available",true) || !payload.optBoolean("ok",true)) return null;
        JSONArray trades=payload.optJSONArray("trades");
        if (trades==null) return null;
        List<JSONObject> sorted=new ArrayList<>();
        for (int i=0;i<trades.length();i++) {
            JSONObject trade=trades.optJSONObject(i);
            if (trade==null || !positive(trade.optDouble("price"))) continue;
            LocalDateTime at=localTime(trade.optString("source_at"));
            if (!tradingMinute(at) || !at.toLocalDate().equals(now.toLocalDate()) || at.isAfter(now.plusSeconds(3))
                    || at.isBefore(now.minusMinutes(2))) continue;
            sorted.add(trade);
        }
        sorted.sort(Comparator.comparing((JSONObject t)->localTime(t.optString("source_at"))).thenComparingLong(t->t.optLong("sequence",-1)));
        JSONObject out=null, last=null;
        JSONArray bars=null;
        LocalDateTime previous=localTime(chart.optString("quote_at"));
        LocalDateTime exact=localTime(chart.optString("last_trade_at"));
        if (exact!=null && (previous==null || exact.isAfter(previous))) previous=exact;
        long seq=chart.optLong("trade_sequence",-1);
        for (JSONObject trade:sorted) {
            LocalDateTime at=localTime(trade.getString("source_at"));
            long n=trade.optLong("sequence",-1);
            if (previous!=null && (at.isBefore(previous) || at.equals(previous) && chart.has("trade_sequence") && n<=seq)) continue;
            String day=at.toLocalDate().toString();
            if (day.compareTo(chart.optString("date"))<0) continue;
            if (out==null) {
                out=day.equals(chart.optString("date"))?copy(chart):new JSONObject().put("name",chart.optString("name",symbol.substring(2)));
                bars=new JSONArray();
                JSONArray old=out.optJSONArray("bars");
                if (old!=null) for(int i=0;i<old.length();i++) bars.put(old.get(i));
                last=bars.optJSONObject(bars.length()-1);
            }
            String end=BAR.format(barEnd(at));
            if (last!=null && last.getString("time").compareTo(end)>0) continue;
            double p=trade.getDouble("price");
            if (last!=null && end.equals(last.getString("time"))) {
                last=copy(last);
                // The bounded latest-trades window cannot prove all exchange turnover.
                last.remove(PhoneSessionAverage.AVERAGE);
                last.put("high",Math.max(p,last.getDouble("high"))).put("low",Math.min(p,last.getDouble("low"))).put("close",p);
                bars.put(bars.length()-1,last);
            } else {
                last=new JSONObject().put("time",end).put("open",p).put("high",p).put("low",p).put("close",p)
                        .put("first_tick_at",at.toString());
                bars.put(last);
            }
            if (out.has("high") && out.has("low")) references(out,Double.NaN,p,p);
            out.put("symbol",symbol).put("date",day).put("bars",bars).put("quote_at",STAMP.format(at))
                    .put("source","MCP 逐笔成交").put("price",p).put("trade_sequence",n).put("last_trade_at",at.toString());
            previous=at; seq=n;
        }
        return out!=null && out.optJSONArray("bars")==bars ? out : null;
    }

    static JSONObject snapshot(JSONObject current, JSONObject data) throws Exception {
        if (data==null || !current.optString("symbol").equals(normalize(data.optString("symbol")))) return current;
        LocalDateTime at=localTime(data.optString("datetime"));
        // TDX timestamps snapshots at acquisition. Only supplement the matching, independently dated bars.
        if (at==null || !at.toLocalDate().toString().equals(current.optString("date"))
                || !at.toLocalDate().equals(now().toLocalDate())) return current;
        JSONObject out=copy(current);
        references(out,data.optDouble("pre_close"),data.optDouble("high_price"),data.optDouble("low_price"));
        return out;
    }

    static final class DepthUnavailable extends IOException {
        DepthUnavailable(String reason) { super(reason); }
    }

    static JSONObject book(JSONObject data, String symbol, LocalDateTime now) throws Exception {
        return book(data,symbol,now,new BookCache());
    }

    static JSONObject book(JSONObject data, String symbol, LocalDateTime now, BookCache cache) throws Exception {
        if (!data.optBoolean("available") || !data.optBoolean("ok",true))
            throw new DepthUnavailable("千档数据源不可用");
        if (!symbol.equals(normalize(data.optString("symbol")))) throw new DepthUnavailable("盘口代码不符");
        if (data.optInt("requested_depth")!=1000 || !data.optBoolean("verified",true)
                || !data.optBoolean("service_ready",true)
                || !(data.optBoolean("distribution_ready") || data.optBoolean("full_depth_verified") && !data.optBoolean("depth_limit_reached")))
            throw new DepthUnavailable("千档分布未核验");
        LocalDateTime at=localTime(data.optString("source_at"));
        if (at==null || at.isAfter(now.plusSeconds(5))) throw new DepthUnavailable("盘口时间无效");
        if (data.optBoolean("stale") || tradingMinute(now) && at.isBefore(now.minusSeconds(30)))
            throw new DepthUnavailable("盘口已过期");
        return cache.snapshot(data.optJSONArray("levels"),STAMP.format(at),data.optString("source_time_kind"));
    }

    static final class BookCache {
        private JSONArray raw;
        private JSONObject last;

        synchronized JSONObject snapshot(JSONArray levels, String at, String kind) throws Exception {
            boolean same=last!=null && sameOrders(levels,raw);
            if (same && at.equals(last.optString("source_at")) && kind.equals(last.optString("source_time_kind")))
                return last;
            JSONArray normalized=same ? last.getJSONArray("levels") : normalizeOrders(levels);
            raw=levels;
            last=new JSONObject().put("source_at",at).put("source_time_kind",kind)
                    .put("requested_depth",1000).put("levels",normalized);
            return last;
        }

        private boolean sameOrders(JSONArray a, JSONArray b) {
            if (a==null || b==null || a.length()!=b.length()) return false;
            for (int i=0;i<a.length();i++) {
                JSONObject left=a.optJSONObject(i), right=b.optJSONObject(i);
                if (left==null || right==null || left.optDouble("price")!=right.optDouble("price")
                        || left.optDouble("volume")!=right.optDouble("volume")
                        || !left.optString("side").equals(right.optString("side"))) return false;
            }
            return true;
        }
    }

    private static JSONArray normalizeOrders(JSONArray levels) throws Exception {
        TreeMap<Double,Double> bids=new TreeMap<>(Comparator.reverseOrder()), asks=new TreeMap<>();
        for (int i=0;levels!=null && i<levels.length();i++) {
            JSONObject v=levels.optJSONObject(i);
            if (v==null) continue;
            double p=v.optDouble("price"), qty=v.optDouble("volume");
            if (!positive(p) || !positive(qty)) continue;
            String side=v.optString("side");
            if ("bid".equals(side)) bids.merge(p,qty,Double::sum);
            else if ("ask".equals(side)) asks.merge(p,qty,Double::sum);
        }
        JSONArray normalized=new JSONArray();
        addLevels(normalized,bids,"bid");
        addLevels(normalized,asks,"ask");
        if (normalized.length()==0) throw new DepthUnavailable("盘口无有效档位");
        return normalized;
    }

    static boolean bookFitsChart(JSONObject book, JSONObject chart, LocalDateTime now) {
        if (book==null) return false;
        LocalDateTime observed=localTime(book.optString("source_at"));
        LocalDateTime latest=localTime(chart.optString("quote_at"));
        if (observed==null || latest==null || !observed.toLocalDate().toString().equals(chart.optString("date"))) return false;
        // At lunch or after close an old morning book must not look like a closing book.
        LocalDateTime reference=tradingMinute(now)?now:latest;
        return !observed.isBefore(reference.minusSeconds(30));
    }

    private static void addLevels(JSONArray target, Map<Double,Double> px, String side) throws Exception {
        int count=0;
        for (Map.Entry<Double,Double> entry:px.entrySet()) {
            if (++count>1000) break;
            target.put(new JSONObject().put("price",entry.getKey()).put("volume",entry.getValue()).put("side",side));
        }
    }

    static JSONArray signals(JSONObject data, String symbol, String date) throws Exception {
        JSONArray rows=data.getJSONArray("notifications"), out=new JSONArray();
        Set<String> ids=new HashSet<>();
        for (int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if (row==null || !"trading.holding_t_signal".equals(row.optString("event_type"))) continue;
            JSONObject p=row.optJSONObject("payload");
            if (p==null || !symbol.equals(normalize(p.optString("code"))) || !positive(p.optDouble("price"))) continue;
            LocalDateTime at=localTime(p.optString("bar_at"));
            if (!tradingMinute(at) || !at.toLocalDate().toString().equals(date)) continue;
            String side=p.optString("direction",p.optString("type")), id=row.optString("event_id");
            if (id.isEmpty() || !("buy_first".equals(side) || "sell_first".equals(side)) || !ids.add(id)) continue;
            out.put(new JSONObject().put("event_id",id).put("time",STAMP.format(at)).put("bar_time",BAR.format(at))
                    .put("price",p.getDouble("price")).put("side","buy_first".equals(side)?"B":"S"));
        }
        return out;
    }
}
