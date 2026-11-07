package com.kevin.phonekline;

import android.os.Handler;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Actual trade and depth RPCs with independently bounded in-flight requests. */
final class PhoneMarketSampler {
    enum Run { NORMAL, DISPLAY_PROBE, BENCHMARK }
    interface Sink { void accept(boolean depth, JSONObject result, Exception error, long receivedAt); }
    static final long PROBE_LIMIT_MS = 16_000;
    private static final int MAX_IN_FLIGHT = 12;
    private final Handler main;
    private final Sink sink;
    private final String symbol, url, key;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(0, 2 * MAX_IN_FLIGHT,
            30, TimeUnit.SECONDS, new SynchronousQueue<>());
    private final Run run;
    final Channel trades = new Channel(false), depth = new Channel(true);
    private PhoneMarketDemand demand;
    private volatile boolean active = true;
    private ScheduledFuture<?> scheduled;
    private long scheduleVersion, lastTick, lastDepth = -PhoneMarketDemand.IDLE_DEPTH_MS;

    PhoneMarketSampler(String symbol, String url, String key, PhoneMarketDemand demand, Run run, Handler main, Sink sink) {
        this.symbol=symbol; this.url=url; this.key=key; this.demand=demand; this.run=run;
        this.main=main; this.sink=sink;
    }

    synchronized void start() {
        if (run != Run.NORMAL) timer.schedule(this::close, PROBE_LIMIT_MS, TimeUnit.MILLISECONDS);
        scheduleAt(SystemClock.elapsedRealtime());
    }

    synchronized void setDemand(PhoneMarketDemand next) {
        if (!active || (next.marketHz == demand.marketHz && next.visibleDepthHz == demand.visibleDepthHz)) return;
        demand = next;
        if (scheduled == null) return;
        scheduled.cancel(false);
        scheduleAt(lastTick + demand.period(trading(), System.currentTimeMillis()));
    }

    private boolean trading() { return run == Run.BENCHMARK || PhoneRealtimeCandles.tradingWindowNow(); }

    private void scheduleAt(long deadline) {
        long version = ++scheduleVersion;
        scheduled = timer.schedule(() -> tick(deadline, version),
                Math.max(0, deadline-SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
    }

    private synchronized void tick(long deadline, long version) {
        if (!active || version != scheduleVersion) return;
        long now=SystemClock.elapsedRealtime();
        lastTick = now;
        boolean trading = trading();
        int hz = demand.depthRate(trading);
        if (trading) trades.dispatch(now, false, demand.marketHz);
        if (demand.depthDue(trading, now-lastDepth)) {
            depth.dispatch(now, hz > 0, hz);
            lastDepth = now;
        }
        long period = demand.period(trading, System.currentTimeMillis());
        // Anchor deadlines to the previous slot. Skip missed slots instead of catching up in a burst.
        long nextSlot = deadline + period;
        scheduleAt(nextSlot <= now ? now + period : nextSlot);
    }

    synchronized void close() {
        if (!active) return;
        active=false;
        timer.shutdownNow();
        io.shutdownNow();
        List<PhoneMcpClient> clients=new ArrayList<>();
        trades.stop(clients); depth.stop(clients);
        // disconnect() on some Android transports waits for the socket; keep it off the UI thread.
        if (!clients.isEmpty()) new Thread(() -> { for (PhoneMcpClient c:clients) c.cancel(); },"PhoneQuoteCancel").start();
    }

    static final class Counter {
        private final ArrayDeque<Long> recent=new ArrayDeque<>();
        private long total;
        synchronized void add() {
            long now=SystemClock.elapsedRealtime();
            discardBefore(now-1000);
            recent.addLast(now);
            total++;
        }
        synchronized int recent(long now) { discardBefore(now-1000); return recent.size(); }
        synchronized long total() { return total; }
        private void discardBefore(long cutoff) {
            while (!recent.isEmpty() && recent.peekFirst() <= cutoff) recent.removeFirst();
        }
    }

    /** Count a failed batch once, and resume immediately when a newer request succeeds. */
    static final class RetryBackoff {
        private long deadline, failedThrough;
        private int failures;

        boolean allows(long now) { return now >= deadline; }

        void failed(long now, long ticket, long issued, int code) {
            if (ticket > failedThrough) {
                failedThrough = issued;
                failures = Math.min(5, failures + 1);
                deadline = Math.max(deadline, now + (1000L << failures));
            }
            if (code == 401 || code == 403) deadline = Math.max(deadline, now + 60_000);
            else if (code == 429) deadline = Math.max(deadline, now + 30_000);
        }

        void reset() {
            deadline = failedThrough = 0;
            failures = 0;
        }
    }

    final class Channel {
        final Counter sent=new Counter(), ok=new Counter();
        private final boolean book;
        private final ArrayDeque<PhoneMcpClient> idle=new ArrayDeque<>();
        private final List<PhoneMcpClient> pool=new ArrayList<>();
        private final PhoneRealtimeCandles.BookCache levels=new PhoneRealtimeCandles.BookCache();
        private long sequence, completed, displayed;
        private final RetryBackoff retry = new RetryBackoff();
        private JSONObject pendingBook;
        private long pendingBookTicket, pendingBookReceivedAt;
        private boolean bookDeliveryQueued;

        Channel(boolean book) { this.book=book; }

        synchronized void dispatch(long now,boolean force,int requested) {
            if (!active || !retry.allows(now)) return;
            // At 5 Hz a two-second round trip occupies ten slots. Leave two for jitter.
            // A fast peer reuses idle clients; unplugging immediately lowers the ceiling.
            int limit = Math.min(MAX_IN_FLIGHT, 2 * Math.max(1, requested) + 2);
            if (pool.size() - idle.size() >= limit) return;
            PhoneMcpClient client=idle.pollFirst();
            if (client==null) {
                client=new PhoneMcpClient(url,key,sent::add);
                pool.add(client);
            }
            final PhoneMcpClient slot=client;
            long ticket=++sequence;
            try { io.execute(() -> fetch(slot,ticket,force)); }
            catch (RejectedExecutionException busy) { if (active) idle.addFirst(slot); }
        }

        private void fetch(PhoneMcpClient client,long ticket,boolean force) {
            JSONObject value=null;
            Exception issue=null;
            try {
                if (!active) return;
                if (book) {
                    JSONObject args=PhoneMcpClient.arguments(symbol).put("requested_depth",1000);
                    if (force) args.put("force",true);
                    JSONObject reply=client.call("get_tdx_depth",args);
                    ok.add();
                    value=PhoneRealtimeCandles.book(reply,symbol,PhoneRealtimeCandles.now(),levels);
                } else {
                    value=client.latestTrade(symbol);
                    ok.add();
                }
            } catch (Exception failure) { issue=failure; }
            finish(client,ticket,value,issue,issue == null ? System.currentTimeMillis() : 0);
        }

        private synchronized void finish(PhoneMcpClient client,long ticket,JSONObject value,Exception issue,long receivedAt) {
            if (!active) return;
            if (ticket <= completed) { idle.addFirst(client); return; }
            completed=ticket;
            if (issue!=null && !(issue instanceof PhoneRealtimeCandles.DepthUnavailable)) {
                int code=issue instanceof PhoneMcpClient.RpcError ? ((PhoneMcpClient.RpcError)issue).code : 0;
                retry.failed(SystemClock.elapsedRealtime(), ticket, sequence, code);
            } else retry.reset();
            if (book && issue==null) {
                // Each depth result replaces the whole book. Retain only the newest
                // complete snapshot while the UI is painting the previous one.
                pendingBook=value;
                pendingBookTicket=ticket;
                pendingBookReceivedAt=receivedAt;
                idle.addFirst(client);
                if (!bookDeliveryQueued) {
                    bookDeliveryQueued=true;
                    main.post(this::deliverBook);
                }
                return;
            }
            // Preserve trades and book invalidations, bounding their backlog by the slot count.
            main.post(() -> {
                if (active && ticket>displayed) {
                    displayed=ticket;
                    sink.accept(book,value,issue,receivedAt);
                }
                synchronized (Channel.this) { if (active) idle.addFirst(client); }
            });
        }

        private void deliverBook() {
            JSONObject value;
            long ticket, receivedAt;
            synchronized (this) {
                value=pendingBook;
                ticket=pendingBookTicket;
                receivedAt=pendingBookReceivedAt;
                pendingBook=null;
                bookDeliveryQueued=false;
            }
            if (active && ticket>displayed) {
                displayed=ticket;
                sink.accept(true,value,null,receivedAt);
            }
        }

        synchronized void stop(List<PhoneMcpClient> clients) {
            for (PhoneMcpClient c:pool) c.preventRequests();
            clients.addAll(pool);
            idle.clear();
            pendingBook=null;
        }
    }
}
