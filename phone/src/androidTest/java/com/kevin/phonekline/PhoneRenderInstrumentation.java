package com.kevin.phonekline;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;

/** Exercises the same native Canvas on every selected face, without touching user preferences or feeds. */
public final class PhoneRenderInstrumentation extends Instrumentation {
    private final int[] themes = {95,96,100,101,102,104,108,PhoneCandleView.LOCK_BLACK,PhoneCandleView.LOCK_GRAPHITE};
    private final int[][] charts = {{29,571,1051,909},{280,530,1033,742},{29,1494,1051,1744},
            {29,1508,1051,1744},{29,431,1051,741},{81,1090,999,1435},{81,1016,999,1361},
            {42,855,1038,1590},{62,902,1018,1587}};
    private Context isolated;
    private SharedPreferences options;
    private boolean checkConfiguredDepth, checkFiveHz, checkPowerRate;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        checkConfiguredDepth = arguments != null && "true".equals(arguments.getString("checkConfiguredDepth"));
        checkFiveHz = arguments != null && "true".equals(arguments.getString("checkFiveHz"));
        checkPowerRate = arguments != null && "true".equals(arguments.getString("checkPowerRate"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(() -> {
                try {
                    options = getContext().getSharedPreferences("plot-options-test", Context.MODE_PRIVATE);
                    options.edit().clear().commit();
                    isolated = new ContextWrapper(getTargetContext()) {
                        @Override public Context getApplicationContext() { return this; }
                        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                            return "phone_dial".equals(name) ? options : super.getSharedPreferences(name, mode);
                        }
                    };
                    for (int i=0;i<themes.length;i++) check(i);
                    for (int i=0;i<themes.length;i++) {
                        if (themes[i] == PhoneCandleView.LOCK_BLACK || themes[i] == PhoneCandleView.LOCK_GRAPHITE)
                            PhoneChargingChartInstrumentation.check(isolated, options, themes[i], charts[i]);
                    }
                    PhoneWidgetInstrumentation.check(getTargetContext(), getContext());
                }
                catch (Exception | AssertionError failure) { result.putString("error", failure.toString()); }
                finally { if (options != null) options.edit().clear().commit(); }
            });
            if (result.containsKey("error")) throw new AssertionError(result.getString("error"));
            String depth = checkConfiguredDepth ? "\nConfigured depth: " + checkDepth() : "";
            if (checkPowerRate) depth += "\nVisible lock/dream sampling policy: " + measureRequests(false);
            if (checkFiveHz) depth += "\nForced two-lane 5 Hz benchmark: " + measureRequests(true);
            result.putString("stream","PASS: clock-free lock wallpapers; shared charging themes with one clock; real-coordinate candle/fill/depth layers; empty-stock reset; clock-free RemoteViews on two host sizes, quote/range/axes, candle vs intraday, independent B/S and depth" + depth);
            finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            result.putString("stream","FAIL: "+failure);
            finish(Activity.RESULT_CANCELED,result);
        }
    }

    private String measureRequests(boolean benchmarkBoth) throws Exception {
        SharedPreferences prefs = getTargetContext().getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        if (!benchmarkBoth && prefs.getBoolean("paused", false))
            return "paused=true; measurement skipped";
        String symbol = PhoneRealtimeCandles.normalize(prefs.getString("symbol", ""));
        String url = prefs.getString("mcp_url", ""), key = PhoneSecretStore.load(prefs);
        if (symbol.isEmpty() || !url.startsWith("https://") || key.isEmpty())
            throw new AssertionError("Live test needs configured MCP");
        PhoneQuoteSession live = new PhoneQuoteSession((quote, status) -> { });
        android.content.Intent battery = getTargetContext().registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean plugged = battery != null && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,0) != 0;
        int hz = benchmarkBoth ? 5 : PhoneSamplingRate.value(prefs,plugged);
        android.os.PowerManager power = getTargetContext().getSystemService(android.os.PowerManager.class);
        android.os.PowerManager.WakeLock cpu = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "phonekline:rate-test");
        try {
            cpu.acquire(PhoneMarketSampler.PROBE_LIMIT_MS);
            boolean tradingAtStart = PhoneRealtimeCandles.tradingWindowNow();
            runOnMainSync(() -> live.startProbe(symbol, url, key, hz, benchmarkBoth));
            Thread.sleep(4_000);
            PhoneQuoteSession.RateSample start = live.sample();
            long began = android.os.SystemClock.elapsedRealtime();
            boolean beganInteractive = power.isInteractive();
            StringBuilder intervals = new StringBuilder();
            PhoneQuoteSession.RateSample end = start;
            for (int i = 1; i <= 8; i++) {
                long deadline = began + i * 1_000L;
                Thread.sleep(Math.max(0, deadline - android.os.SystemClock.elapsedRealtime()));
                end = live.sample();
                intervals.append(" ").append(end.tradeSent).append('/').append(end.tradeOk)
                        .append(':').append(end.depthSent).append('/').append(end.depthOk);
            }
            long elapsed = android.os.SystemClock.elapsedRealtime() - began;
            if (elapsed > 9_000 || end.tradeSentTotal < start.tradeSentTotal || end.depthSentTotal < start.depthSentTotal)
                throw new AssertionError("Probe suspended or stopped before the eight-second measurement completed");
            boolean tradingAtEnd = PhoneRealtimeCandles.tradingWindowNow();
            String counts = "plugged=" + plugged + " target_hz=" + hz + " trading=" + tradingAtStart + "/" + tradingAtEnd
                    + " interactive=" + beganInteractive + "/" + power.isInteractive()
                    + " elapsed_ms=" + elapsed
                    + " trade(sent/ok)=" + (end.tradeSentTotal - start.tradeSentTotal) + "/" + (end.tradeOkTotal - start.tradeOkTotal)
                    + " depth(sent/ok)=" + (end.depthSentTotal - start.depthSentTotal) + "/" + (end.depthOkTotal - start.depthOkTotal)
                    + " per_sec[trade:depth]=" + intervals;
            if (!benchmarkBoth && !tradingAtStart && !tradingAtEnd && end.tradeSentTotal != 0)
                throw new AssertionError("Visible policy sent off-hours trade requests including warm-up: "
                        + end.tradeSentTotal + "; " + counts);
            return counts;
        } finally {
            runOnMainSync(live::stop);
            if (cpu.isHeld()) cpu.release();
        }
    }

    // Explicit on-device integration probe. Only the counts, plot status and sample times leave the phone.
    private String checkDepth() throws Exception {
        Context app = getTargetContext();
        SharedPreferences prefs = app.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        String symbol = PhoneRealtimeCandles.normalize(prefs.getString("symbol", ""));
        String url = prefs.getString("mcp_url", "");
        String key = PhoneSecretStore.load(prefs);
        if (symbol.isEmpty() || !url.startsWith("https://") || key.isEmpty())
            throw new AssertionError("Real depth probe needs a selected stock and configured MCP");
        JSONObject cached = PhoneMarketWidgetProvider.latest(app);
        if (cached == null) throw new AssertionError("Missing real quote");
        JSONObject frame = PhoneRealtimeCandles.copy(cached);
        PhoneMcpClient client = new PhoneMcpClient(url, key);
        String first = "", last = "", label = "";
        int count = 0;
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (attempt > 0) Thread.sleep(attempt * 3000L);
                JSONObject response = client.call("get_tdx_depth",
                        PhoneMcpClient.arguments(symbol).put("requested_depth", 1000));
                JSONObject book = PhoneRealtimeCandles.book(response, symbol, PhoneRealtimeCandles.now());
                if (!PhoneRealtimeCandles.bookFitsChart(book, frame, PhoneRealtimeCandles.now()))
                    throw new AssertionError("Real book and quote times do not match");
                PhoneMarketPlot actual = new PhoneChartOptions(prefs).build(frame.put("book", book));
                if (!actual.hasBook()) throw new AssertionError("Real book disabled or absent from plot");
                last = book.getString("source_at");
                count = book.getJSONArray("levels").length();
                label = actual.bookLabel();
                if (attempt == 0) first = last;
                else if (last.compareTo(first) > 0) break;
            }
        } finally {
            client.cancel();
        }
        return symbol + " levels=" + count + " samples=" + first + " / " + last + " plot=" + label;
    }

    private void check(int i) throws Exception {
        int theme = themes[i];
        PhoneCandleView view = new PhoneCandleView(isolated);
        boolean ambient = theme==104 || theme==108;
        view.setSurface(ambient ? PhoneCandleView.Surface.AMBIENT : PhoneCandleView.Surface.LOCK);
        view.setTheme(theme);
        view.layout(0,0,540,1170);
        JSONObject snapshot = fixture();
        view.setMarket(snapshot, "sz000001");
        long start = LocalDateTime.of(2026,11,5,10,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        view.setFrameTime(start);
        Bitmap before = render(view);
        view.setFrameTime(start + 4 * 3_600_000 + 32 * 60_000);
        Bitmap later = render(view);
        int[] whole = {0,0,1080,2340};
        if (ambient) {
            if (changed(before,later,new int[]{60,350,1020,700}) < 200) throw new AssertionError("static ambient clock "+theme);
        } else {
            if (changed(before,later,whole) != 0) throw new AssertionError("wallpaper drew another clock "+theme);
            view.setSurface(PhoneCandleView.Surface.DREAM);
            Bitmap dream = render(view);
            int dreamClockPixels = changed(dream,later,new int[]{240,150,840,372});
            if (dreamClockPixels < 200 || dreamClockPixels != changed(dream,later,whole))
                throw new AssertionError("dream must share the artwork and market, adding exactly the clock area "+theme);
            dream.recycle();
            view.setSurface(PhoneCandleView.Surface.LOCK);
        }
        if ((ambient || theme == PhoneCandleView.LOCK_BLACK) && (before.getPixel(0,0) != Color.BLACK || before.getPixel(532,1129) != Color.BLACK))
            throw new AssertionError("OLED background "+theme);
        before.recycle();
        view.setFrameTime(start);
        JSONArray levels = new JSONArray()
                .put(new JSONObject().put("side","bid").put("price",9.98).put("volume",4500))
                .put(new JSONObject().put("side","ask").put("price",10.04).put("volume",3500));
        snapshot.put("book",new JSONObject().put("levels",levels).put("source_at","2026-11-05 10:29:01"));
        view.setMarket(snapshot,"sz000001");
        Bitmap book=render(view);
        snapshot.remove("book"); view.setMarket(snapshot,"sz000001");
        Bitmap plain=render(view);
        int[] chart=charts[i];
        int[] edge={740,chart[1],chart[2],chart[3]};
        if (changed(book,plain,edge)<35) throw new AssertionError("missing book overlay "+theme);
        if (!ambient && changed(book,plain,new int[]{0,1808,1080,2340})!=0)
            throw new AssertionError("wallpaper plot in system control area "+theme);
        book.recycle(); later.recycle();
        snapshot.put("high",10.06).put("low",9.95);
        view.setMarket(snapshot,"sz000001");
        Bitmap intraday=render(view);
        int[] highCorner={chart[0],chart[1],chart[0]+160,chart[1]+60};
        int[] lowCorner={chart[0],chart[3]-96,chart[0]+160,chart[3]-39};
        if (changed(intraday,plain,highCorner)<8 || changed(intraday,plain,lowCorner)<8)
            throw new AssertionError("missing daily prices inside left chart corners "+theme);
        if (changed(intraday,plain,new int[]{0,0,1080,chart[1]})!=0)
            throw new AssertionError("duplicate daily prices above the chart "+theme);
        intraday.recycle();
        snapshot.put("high",10.63).put("low",9.15);
        snapshot.put("fills",new JSONArray().put(new JSONObject().put("bar_time","2026-11-05 10:08")
                .put("side","B").put("price",10.0)).put(new JSONObject().put("bar_time","2026-11-05 10:19")
                .put("side","S").put("price",10.02)));
        view.setMarket(snapshot,"sz000001");
        Bitmap marked=render(view);
        if (changed(marked,plain,chart)<25) throw new AssertionError("missing B/S "+theme);
        if (theme == 104) checkSwitches(view, snapshot, levels, chart, edge, plain);
        marked.recycle();
        view.setMarket(snapshot,"sz000002");
        if (view.getContentDescription().toString().contains("10.03")) throw new AssertionError("stale symbol "+theme);
        Bitmap empty=render(view);
        if (changed(plain,empty,chart)<500) throw new AssertionError("stale candles "+theme);
        plain.recycle(); empty.recycle();
    }

    private void checkSwitches(PhoneCandleView view, JSONObject snapshot, JSONArray levels, int[] chart,
                               int[] edge, Bitmap candles) throws Exception {
        snapshot.put("book", new JSONObject().put("levels", levels).put("source_at", "2026-11-05 10:29:01"));
        view.setMarket(snapshot, "sz000001");
        options.edit().putBoolean("show_signals", false).putBoolean("show_depth", false).apply();
        view.refreshChartOptions();
        Bitmap noLayers = render(view);
        if (changed(candles, noLayers, edge) > 0) throw new AssertionError("depth remains when disabled");
        options.edit().putString("chart_mode", "realtime").apply();
        view.refreshChartOptions();
        Bitmap line = render(view);
        if (changed(noLayers, line, chart) < 100) throw new AssertionError("intraday unchanged from candles");
        options.edit().putBoolean("show_depth", true).apply();
        view.refreshChartOptions();
        Bitmap depth = render(view);
        if (changed(depth, line, edge) < 25) throw new AssertionError("intraday depth missing");
        options.edit().putBoolean("show_signals", true).apply();
        view.refreshChartOptions();
        Bitmap both = render(view);
        if (changed(depth, both, chart) < 12) throw new AssertionError("intraday signals missing");
        options.edit().clear().apply();
        noLayers.recycle(); line.recycle(); depth.recycle(); both.recycle();
    }

    private static Bitmap render(PhoneCandleView view) {
        Bitmap result=Bitmap.createBitmap(540,1170,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(result));
        return result;
    }

    private static int changed(Bitmap first, Bitmap second, int[] box) {
        int w=(box[2]-box[0])/2, h=(box[3]-box[1])/2, x=box[0]/2, y=box[1]/2;
        int[] a=new int[w*h], b=new int[w*h];
        first.getPixels(a,0,w,x,y,w,h); second.getPixels(b,0,w,x,y,w,h);
        int count=0;
        for (int k=0;k<a.length;k++) if (a[k]!=b[k]) count++;
        return count;
    }

    private static JSONObject fixture() throws Exception {
        JSONArray bars=new JSONArray();
        for (int i=0;i<60;i++) {
            String time=String.format(Locale.ROOT,"2026-11-05 %02d:%02d",9+(30+i)/60,(30+i)%60);
            double close = 10 + .03*Math.sin(i*.33);
            bars.put(new JSONObject().put("time",time).put("open",10).put("high",10.06)
                    .put("low",9.95).put("close",close));
        }
        return new JSONObject().put("date","2026-11-05").put("symbol","sz000001").put("name","渲染检验")
                .put("high",10.63).put("low",9.15).put("previous",9.89).put("price",10.03).put("bars",bars);
    }
}
