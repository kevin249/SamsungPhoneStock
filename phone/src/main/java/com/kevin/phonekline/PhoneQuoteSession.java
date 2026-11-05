package com.kevin.phonekline;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Main-thread subscriptions. A visible Activity and wallpaper share one set of network requests. */
final class PhoneQuoteSession {
    interface Listener { void onUpdate(JSONObject snapshot, String status); }

    private static final Set<Feed> FEEDS = new CopyOnWriteArraySet<>();
    private final Listener listener;
    private volatile Feed feed;
    PhoneQuoteSession(Listener listener) { this.listener = listener; }

    void start(String symbol, String url, String key, int hz, JSONObject restored) {
        attach(symbol,url,key,new PhoneMarketDemand(Math.max(1,Math.min(10,hz)),false),restored);
    }

    void startDisplay(String symbol, String url, String key, int hz, JSONObject restored) {
        attach(symbol,url,key,new PhoneMarketDemand(Math.max(1,Math.min(10,hz)),true),restored);
    }

    private void attach(String symbol,String url,String key,PhoneMarketDemand requested,JSONObject restored) {
        String code = PhoneRealtimeCandles.normalize(symbol);
        if (code.isEmpty()) { stop(); listener.onUpdate(new JSONObject(), "股票代码无效"); return; }
        String endpoint = url != null && url.startsWith("https://") && key!=null && !key.isEmpty() ? url : "";
        String secret = endpoint.isEmpty() ? "" : key;
        if (feed!=null && feed.matches(code,endpoint,secret)) {
            feed.subscribe(listener,requested);
            return;
        }
        stop();
        for (Feed existing:FEEDS) if (existing.matches(code,endpoint,secret)) { feed=existing; break; }
        if (feed==null) {
            feed=new Feed(code,endpoint,secret,requested,restored,PhoneMarketSampler.Run.NORMAL);
            FEEDS.add(feed);
        }
        feed.subscribe(listener,requested);
        listener.onUpdate(feed.chart,feed.status());
    }

    // A background widget borrows an existing visible feed if possible.
    // When it is the only consumer it needs at most one live poll per second.
    void startSnapshot(String symbol, String url, String key, JSONObject restored) {
        // Zero represents a cache-eligible, short-lived snapshot subscriber.
        attach(symbol,url,key,new PhoneMarketDemand(0,false),restored);
    }

    void stop() {
        if (feed==null) return;
        feed.listeners.remove(listener);
        if (feed.listeners.isEmpty()) { FEEDS.remove(feed); feed.stop(); }
        else feed.updateRate();
        feed=null;
    }

    void close() { stop(); }

    void setRate(int hz) {
        if (feed!=null) {
            PhoneMarketDemand old = feed.listeners.get(listener);
            if (old != null) feed.subscribe(listener,new PhoneMarketDemand(Math.max(1,Math.min(10,hz)),old.visibleDepthHz > 0));
        }
    }

    /** Explicit, bounded instrumentation measurement. Never joins or changes a user subscription. */
    void startProbe(String symbol,String url,String key,int hz,boolean benchmarkBoth) {
        stop();
        String code=PhoneRealtimeCandles.normalize(symbol);
        if (code.isEmpty() || url==null || !url.startsWith("https://") || key==null || key.isEmpty())
            throw new IllegalArgumentException("诊断需要有效代码和已配置的 HTTPS 行情接口");
        PhoneMarketDemand rate = new PhoneMarketDemand(Math.max(1,Math.min(10,hz)),true);
        Feed probe=new Feed(code,url,key,rate,null,benchmarkBoth ? PhoneMarketSampler.Run.BENCHMARK : PhoneMarketSampler.Run.DISPLAY_PROBE);
        feed=probe;
        probe.subscribe(listener,rate);
        probe.main.postDelayed(() -> { if (feed==probe) stop(); },PhoneMarketSampler.PROBE_LIMIT_MS);
    }

    static final class RateSample {
        final int tradeSent,tradeOk,depthSent,depthOk;
        final long tradeSentTotal,tradeOkTotal,depthSentTotal,depthOkTotal;
        private RateSample(long[] counts) {
            tradeSent=(int)counts[0]; tradeOk=(int)counts[1]; depthSent=(int)counts[2]; depthOk=(int)counts[3];
            tradeSentTotal=counts[4]; tradeOkTotal=counts[5]; depthSentTotal=counts[6]; depthOkTotal=counts[7];
        }
    }
    RateSample sample() {
        long[] counts=new long[8]; Feed current=feed;
        if (current!=null) current.counts(counts);
        return new RateSample(counts);
    }
    static RateSample rates(String symbol) {
        long[] counts=new long[8];
        for (Feed current:FEEDS) if (current.symbol.equals(symbol)) current.counts(counts);
        return new RateSample(counts);
    }

    private enum Lane { PUBLIC, HISTORY, TRADE, SNAPSHOT, DEPTH, SIGNAL }

    private static final class Feed {
        private static final DateTimeFormatter RECEIPT_CLOCK = DateTimeFormatter
                .ofPattern("HH:mm:ss", Locale.ROOT).withZone(PhoneRealtimeCandles.SHANGHAI);
        final String symbol,url,key;
        final Handler main=new Handler(Looper.getMainLooper());
        final Map<Listener,PhoneMarketDemand> listeners=new HashMap<>();
        final ScheduledThreadPoolExecutor net;
        final PhoneMarketSampler sampler;
        final EnumMap<Lane,PhoneMcpClient> clients=new EnumMap<>(Lane.class);
        final PhoneMarketClient market=new PhoneMarketClient();
        volatile boolean active=true;
        JSONObject chart, lastBook, notificationBuffer, reference;
        String bookReceived = "";
        String tickStatus="", depthIssue="", deliveredStatus="";
        boolean publicUnavailable;

        Feed(String symbol,String url,String key,PhoneMarketDemand demand,JSONObject restore,PhoneMarketSampler.Run run) {
            this.symbol=symbol; this.url=url; this.key=key;
            chart=initial(restore);
            depthIssue=url.isEmpty()?"盘口待配置":"盘口连接中";
            try { chart.put("book_status",depthIssue); } catch (org.json.JSONException ignored) { }
            net=new ScheduledThreadPoolExecutor(url.isEmpty()?1:Lane.values().length);
            sampler=url.isEmpty() ? null : new PhoneMarketSampler(symbol,url,key,demand,run,main,
                    (depth,data,issue,receivedAt) -> finish(depth?Lane.DEPTH:Lane.TRADE,data,issue,0,receivedAt));
            if (sampler!=null) sampler.start();
            if (run != PhoneMarketSampler.Run.NORMAL) return;
            for (Lane kind:Lane.values()) {
                if (kind==Lane.TRADE || kind==Lane.DEPTH) continue;
                if (kind!=Lane.PUBLIC) {
                    if (url.isEmpty()) continue;
                    clients.put(kind,new PhoneMcpClient(url,key));
                }
            }
            for (Lane kind:Lane.values()) if (kind==Lane.PUBLIC || clients.containsKey(kind))
                schedule(kind,0);
        }

        JSONObject initial(JSONObject saved) {
            try {
                if (saved!=null && symbol.equals(saved.optString("symbol")) && saved.optJSONArray("bars")!=null) {
                    JSONObject restored=PhoneRealtimeCandles.history(symbol,saved.optString("name"),"已缓存",
                            saved.getJSONArray("bars"),PhoneRealtimeCandles.now());
                    if (restored.optString("date").equals(saved.optString("date"))) {
                        PhoneRealtimeCandles.references(restored,saved.optDouble("previous"),saved.optDouble("high"),saved.optDouble("low"));
                        LocalDateTime tick=PhoneRealtimeCandles.localTime(saved.optString("quote_at"));
                        if (tick!=null && tick.toLocalDate().toString().equals(restored.optString("date"))
                                && !tick.isAfter(PhoneRealtimeCandles.now())) restored.put("quote_at",PhoneRealtimeCandles.STAMP.format(tick));
                    }
                    return restored;
                }
            } catch (Exception ignored) { }
            JSONObject blank=new JSONObject();
            try { blank.put("symbol",symbol).put("name",symbol.substring(2)); } catch (Exception ignored) { }
            return blank;
        }

        boolean matches(String code,String address,String secret) {
            return symbol.equals(code) && url.equals(address) && key.equals(secret);
        }

        void subscribe(Listener listener,PhoneMarketDemand requested) {
            listeners.put(listener,requested);
            // The widget job can already be using a 1 Hz feed at market open.
            // A new visible or screen-off subscriber raises that same feed's poll rate.
            updateRate();
        }

        void updateRate() {
            if (sampler!=null) sampler.setDemand(PhoneMarketDemand.combined(listeners.values()));
        }

        void counts(long[] out) {
            if (sampler==null) return;
            long now=SystemClock.elapsedRealtime();
            PhoneMarketSampler.Counter[] counters={sampler.trades.sent,sampler.trades.ok,sampler.depth.sent,sampler.depth.ok};
            for (int i=0;i<counters.length;i++) {
                out[i]+=counters[i].recent(now);
                out[i+4]+=counters[i].total();
            }
        }

        void stop() {
            active=false;
            if (sampler!=null) sampler.close();
            net.shutdownNow();
            market.cancel();
            for (PhoneMcpClient c:clients.values()) c.cancel();
            main.removeCallbacksAndMessages(null);
        }

        void schedule(Lane lane,long delay) {
            if (active) net.schedule(()->poll(lane),Math.max(0,delay),TimeUnit.MILLISECONDS);
        }

        long interval(Lane lane,boolean trading) {
            switch(lane) {
                case SIGNAL: return trading?5000:60000;
                case PUBLIC: return trading?20000:180000;
                default: return trading?20000:180000;
            }
        }

        void poll(Lane lane) {
            if (!active) return;
            long started=SystemClock.elapsedRealtime();
            try {
                long requestTime=System.currentTimeMillis();
                JSONObject result=fetch(lane);
                if (result!=null && (lane==Lane.PUBLIC || lane==Lane.HISTORY)) result.put("history_request",requestTime);
                main.post(()->finish(lane,result,null,started,0));
            } catch(Exception error) {
                main.post(()->finish(lane,null,error,started,0));
            }
        }

        JSONObject fetch(Lane lane) throws Exception {
            if (lane==Lane.PUBLIC) return market.fetch(symbol);
            PhoneMcpClient client=clients.get(lane);
            JSONObject args=PhoneMcpClient.arguments(symbol);
            switch(lane) {
                case HISTORY:
                    LocalDateTime requested = PhoneRealtimeCandles.now();
                    JSONObject result=client.call("get_tdx_kline",args.put("period","1m").put("days",15).put("limit",241));
                    if (!symbol.equals(PhoneRealtimeCandles.normalize(result.optString("symbol"))) || !result.optBoolean("available",true))
                        throw new java.io.IOException("无分钟行情");
                    return PhoneMarketClient.tdxHistory(symbol,result.getJSONArray("bars"),requested);
                case SNAPSHOT: return client.call("get_tdx_snapshot",args).getJSONObject("snapshot");
                case SIGNAL: return client.notifications();
                default: throw new IllegalArgumentException("unexpected lane " + lane);
            }
        }

        void finish(Lane lane,JSONObject result,Exception error,long started,long receivedAt) {
            if (!active) return;
            JSONObject old=chart;
            boolean forbidden=error instanceof PhoneMcpClient.RpcError &&
                    (((PhoneMcpClient.RpcError)error).code==401 || ((PhoneMcpClient.RpcError)error).code==403);
            boolean tradeMissing=lane==Lane.TRADE && (result==null || !result.optBoolean("available",true)
                    || result.optJSONArray("trades")==null || result.optJSONArray("trades").length()==0);
            try {
                if (lane==Lane.PUBLIC) publicUnavailable=error!=null;
                if (lane==Lane.TRADE) {
                    tickStatus=forbidden?"实时接口未授权":error!=null?"逐笔接口暂不可用":tradeMissing?"暂无逐笔":"";
                    if (result!=null) {
                        JSONObject updated=PhoneRealtimeCandles.apply(chart,result,symbol);
                        if (updated!=null) chart=updated;
                    }
                } else if (lane==Lane.HISTORY || lane==Lane.PUBLIC) {
                    if (result!=null) chart=PhoneRealtimeCandles.mergeHistory(chart,result);
                } else if (lane==Lane.DEPTH) {
                    depthIssue=depthError(error,forbidden);
                    // A denied/invalid book must disappear. A transport hiccup can
                    // still use the previous timestamped sample within its lifetime.
                    if (forbidden || error instanceof PhoneRealtimeCandles.DepthUnavailable) lastBook=null;
                    else if (result!=null && (lastBook==null || result.optString("source_at").compareTo(lastBook.optString("source_at"))>=0)) {
                        lastBook=result;
                        bookReceived=RECEIPT_CLOCK.format(Instant.ofEpochMilli(receivedAt));
                    }
                } else if (lane==Lane.SIGNAL) {
                    notificationBuffer=result;
                } else if (lane==Lane.SNAPSHOT) reference=result;
                if (reference!=null && (lane==Lane.SNAPSHOT || !old.optString("date").equals(chart.optString("date"))))
                    chart=PhoneRealtimeCandles.snapshot(chart,reference);
                LocalDateTime bookAt=lastBook==null?null:PhoneRealtimeCandles.localTime(lastBook.optString("source_at"));
                if (bookAt!=null && PhoneRealtimeCandles.tradingWindowNow() && bookAt.isBefore(PhoneRealtimeCandles.now().minusSeconds(30))) {
                    lastBook=null;
                    if (depthIssue.isEmpty()) depthIssue="盘口已过期";
                }
                JSONObject book=PhoneRealtimeCandles.bookFitsChart(lastBook,chart,PhoneRealtimeCandles.now())?lastBook:null;
                String depthStatus=depthIssue;
                if (depthStatus.isEmpty() && book==null)
                    depthStatus=chart.has("date")?"盘口时间与行情不符":"盘口等待分钟行情";
                if (chart.optJSONObject("book")!=book || !chart.optString("book_status").equals(depthStatus)) {
                    chart=PhoneRealtimeCandles.copy(chart);
                    chart.put("book",book).put("book_status",depthStatus);
                }
                if (lane==Lane.SIGNAL || !old.optString("date").equals(chart.optString("date"))) {
                    JSONArray fills=notificationBuffer==null?null:PhoneRealtimeCandles.signals(notificationBuffer,symbol,chart.optString("date"));
                    if (fills!=null || chart.has("fills")) {
                        chart=PhoneRealtimeCandles.copy(chart);
                        chart.put("fills",fills);
                    }
                }
            } catch(Exception malformed) { error=malformed; }
            String status=status();
            if (old!=chart || !status.equals(deliveredStatus)) {
                deliveredStatus=status;
                for (Listener listener:new ArrayList<>(listeners.keySet())) if (listeners.containsKey(listener)) listener.onUpdate(chart,status);
            }
            if (lane==Lane.TRADE || lane==Lane.DEPTH) return;
            long period=interval(lane,PhoneRealtimeCandles.tradingWindowNow());
            if (forbidden) period=Math.max(period,60000);
            else if (error!=null || tradeMissing) period=Math.max(period,2500);
            // Re-arm only after delivery; a slow connection never creates queued quote requests.
            schedule(lane,Math.max(10,period-(SystemClock.elapsedRealtime()-started)));
        }

        String depthError(Exception error, boolean forbidden) {
            if (error==null) return "";
            if (forbidden) return "盘口未授权";
            if (error instanceof PhoneRealtimeCandles.DepthUnavailable) return error.getMessage();
            if (error instanceof java.net.SocketTimeoutException) return "盘口请求超时";
            if (error instanceof java.net.ConnectException || error instanceof java.net.UnknownHostException) return "盘口连接失败";
            return "盘口接口异常";
        }

        String status() {
            LocalDateTime now=PhoneRealtimeCandles.now();
            LocalDateTime at=PhoneRealtimeCandles.localTime(chart.optString("quote_at"));
            String head;
            if (at==null) head=publicUnavailable?"分钟行情暂不可用":"等待行情";
            else if (!at.toLocalDate().equals(now.toLocalDate())) head="最近交易日 "+at.toLocalDate();
            else if (!PhoneRealtimeCandles.tradingWindowNow()) head="休市";
            else if (at.isBefore(now.minusMinutes(2))) head="行情延迟";
            else head=chart.optString("source");
            if (at!=null) head+=" "+at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
            if (!tickStatus.isEmpty() && PhoneRealtimeCandles.tradingWindowNow()) head+=" · "+tickStatus;
            if (!url.isEmpty()) {
                JSONObject book=chart.optJSONObject("book");
                if (book!=null && !bookReceived.isEmpty()) head+=" · 取回 "+bookReceived;
                String issue=chart.optString("book_status");
                if (!issue.isEmpty()) head+=" · "+issue;
            }
            return url.isEmpty()?head+" · 公开分钟行情":head;
        }
    }
}
