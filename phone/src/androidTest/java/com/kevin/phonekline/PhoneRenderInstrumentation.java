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
import android.graphics.Paint;
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
    private android.os.PowerManager.WakeLock testWake;
    private Context isolated;
    private SharedPreferences options;
    private boolean checkConfiguredDepth, checkFiveHz, checkPowerRate, checkWidgetOnly, checkDaily, checkAutomatic, checkDisplayWindow, checkSignalPersistence, repairSignals;
    private boolean checkDailyArrows;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        checkSignalPersistence = arguments != null && "true".equals(arguments.getString("checkSignalPersistence"));
        repairSignals = arguments != null && "true".equals(arguments.getString("repairSignals"));
        checkDisplayWindow = arguments != null && "true".equals(arguments.getString("checkDisplayWindow"));
        checkAutomatic = arguments != null && "true".equals(arguments.getString("checkAutomatic"));
        checkDailyArrows = arguments != null && "true".equals(arguments.getString("checkDailyArrows"));
        checkDaily = arguments != null && "true".equals(arguments.getString("checkDaily"));
        checkConfiguredDepth = arguments != null && "true".equals(arguments.getString("checkConfiguredDepth"));
        checkFiveHz = arguments != null && "true".equals(arguments.getString("checkFiveHz"));
        checkPowerRate = arguments != null && "true".equals(arguments.getString("checkPowerRate"));
        checkWidgetOnly = arguments != null && "true".equals(arguments.getString("checkWidgetOnly"));
        start();
    }

    @Override public void onStart() {
        testWake=getTargetContext().getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"PhoneKline:Regression");
        testWake.acquire(120000);
        Bundle result = new Bundle();
        record("START automatic="+checkAutomatic+" widgetOnly="+checkWidgetOnly, false);
        Thread watchdog=new Thread(()->{
            try {
                Thread.sleep(20000);
                StringBuilder dump=new StringBuilder("WATCHDOG\n");
                for(java.util.Map.Entry<Thread,StackTraceElement[]> entry:Thread.getAllStackTraces().entrySet()) {
                    if(entry.getKey().getName().equals("main") || entry.getKey().getName().startsWith("Instr:")) {
                        dump.append(entry.getKey().getName()).append(" ").append(entry.getKey().getState()).append("\n");
                        for(StackTraceElement frame:entry.getValue()) dump.append(frame).append("\n");
                    }
                }
                record(dump.toString(), true);
            } catch(InterruptedException ignored) { }
        },"RegressionWatchdog");
        watchdog.setDaemon(true);watchdog.start();
        try {
            if (checkDailyArrows) {
                PhoneDailyArrowRegression.check(this);
                result.putString("stream", "PASS: lock, AOD and dream daily B/S hidden, both TDW arrow colors rendered");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (checkSignalPersistence) {
                PhoneSignalPersistenceRegression.check(this,repairSignals);
                result.putString("stream","PASS: persistent B/S migration, empty updates, subscription restart, offline daily and AOD restoration");
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (checkDisplayWindow) {
                PhoneDisplayWindowRegression.check(this);
                result.putString("stream", "PASS: unified power/surface schedule, manual/auto modes, lunch realtime, off-hours sockets stopped and resumed");
                finish(Activity.RESULT_OK,result);
                return;
            }
            if (checkAutomatic) {
                PhoneAutomaticRegression.check(this);
                result.putString("stream", "PASS: real-device timed lock transitions, AOD daily/realtime TDW, confirmed MCP B/S history and live feed");
                finish(Activity.RESULT_OK,result);
                return;
            }
            runOnMainSync(() -> {
                try {
                    options = getTargetContext().getSharedPreferences("plot-options-test", Context.MODE_PRIVATE);
                    options.edit().clear().commit();
                    isolated = new ContextWrapper(getTargetContext()) {
                        @Override public Context getApplicationContext() { return this; }
                        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                            return "phone_dial".equals(name) ? options : super.getSharedPreferences(name, mode);
                        }
                    };
                    for (int i=0; !checkWidgetOnly && i<themes.length; i++) check(i);
                    if (!checkWidgetOnly) checkSingleChartHeight();
                    if (!checkWidgetOnly) checkFittedCanvasCharts();
                    for (int i=0; !checkWidgetOnly && i<themes.length; i++) {
                        if (themes[i] == PhoneCandleView.LOCK_BLACK || themes[i] == PhoneCandleView.LOCK_GRAPHITE)
                            PhoneChargingChartInstrumentation.check(isolated, options, themes[i], charts[i]);
                    }
                    if (!checkWidgetOnly) PhoneDualChartInstrumentation.check(isolated,options);
                    PhoneStockListRegression.check(isolated, getContext(), options);
                    PhoneWidgetInstrumentation.check(getTargetContext(), getContext());
                }
                catch (Exception | AssertionError failure) { result.putString("error", failure.toString()); }
                finally { if (options != null) options.edit().clear().commit(); }
            });
            if (result.containsKey("error")) throw new AssertionError(result.getString("error"));
            if (checkWidgetOnly) {
                result.putString("stream", "PASS: widget geometry, depth, session axes, power sampling and host updates");
                finish(Activity.RESULT_OK, result);
                return;
            }
            String daily = checkDaily ? "\nDaily history: " + checkDailyHistory() : "";
            String depth = daily + (checkConfiguredDepth ? "\nConfigured depth: " + checkDepth() : "");
            if (checkPowerRate) depth += "\nVisible lock/dream sampling policy: " + measureRequests(false);
            if (checkFiveHz) depth += "\nForced two-lane 5 Hz benchmark: " + measureRequests(true);
            result.putString("stream","PASS: clock-free lock wallpapers; shared charging themes with one clock; real-coordinate candle/fill/depth layers; empty-stock reset; clock-free RemoteViews on two host sizes, quote/range/axes, candle vs intraday, independent B/S and depth" + depth);
            finish(Activity.RESULT_OK,result);
        } catch (Throwable failure) {
            result.putString("stream","FAIL: "+failure);
            finish(Activity.RESULT_CANCELED,result);
        }
    }


    void record(String message,boolean append) {
        try(java.io.FileOutputStream out=getTargetContext().openFileOutput("regression-result.txt",
                append?Context.MODE_APPEND:Context.MODE_PRIVATE)) {
            out.write((message+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch(Exception ignored) { }
    }

    @Override public void finish(int code,Bundle results) {
        record("FINISH "+code+" "+results.getString("stream",results.toString()),true);
        if(testWake!=null&&testWake.isHeld())testWake.release();
        super.finish(code,results);
    }

    private String checkDailyHistory() throws Exception {
        SharedPreferences preferences=getTargetContext().getSharedPreferences("phone_dial",Context.MODE_PRIVATE);
        String code=preferences.getString("symbol","sz000063");
        PhoneMarketClient market=new PhoneMarketClient();
        JSONObject publicDaily;
        try { publicDaily=market.fetchDaily(code); } finally { market.cancel(); }
        if(publicDaily.getJSONArray("bars").length()<2 || !"1d".equals(publicDaily.optString("period")))
            throw new AssertionError("Public daily history empty");
        String result="public bars="+publicDaily.getJSONArray("bars").length()+" date="+publicDaily.getString("date");
        String url=preferences.getString("mcp_url",""), key=PhoneSecretStore.load(preferences);
        if(!url.isEmpty() && !key.isEmpty()) {
            PhoneMcpClient remote=new PhoneMcpClient(url,key);
            try {
                JSONObject raw=remote.call("get_tdx_kline",PhoneMcpClient.arguments(code).put("period","1d")
                        .put("dividend_type","none").put("days",240).put("limit",120));
                JSONObject parsed=PhoneDailyCandles.history(code,"",raw.getJSONArray("bars"));
                result+="; tdx bars="+parsed.getJSONArray("bars").length()+" date="+parsed.getString("date");
            } finally { remote.cancel(); }
        }
        return result;
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
        // Baseline comparisons below require identical artwork on lock and dream.
        options.edit().putInt(PhoneCandleView.LOCK_BACKGROUND_LIFT,0)
                .putInt(PhoneDisplayStocks.HEIGHT_PREFIX + "sz000001", 0)
                .putInt(PhoneDisplayStocks.HEIGHT_PREFIX + "sz000002", 0).commit();
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
        options.edit().remove(PhoneCandleView.LOCK_BACKGROUND_LIFT).commit();
        Bitmap lifted = render(view);
        if (ambient ? changed(lifted,later,whole) != 0 : changed(lifted,later,whole) == 0)
            throw new AssertionError("background lift must affect only lock wallpaper "+theme);
        lifted.recycle();
        if (!ambient) {
            view.setSurface(PhoneCandleView.Surface.DREAM);
            Bitmap defaultDream = render(view);
            options.edit().putInt(PhoneCandleView.LOCK_BACKGROUND_LIFT,40).commit();
            Bitmap brightLockDream = render(view);
            if (changed(defaultDream,brightLockDream,whole) != 0)
                throw new AssertionError("lock brightness leaked into charging dream "+theme);
            defaultDream.recycle(); brightLockDream.recycle();
            view.setSurface(PhoneCandleView.Surface.LOCK);
        }
        options.edit().putInt(PhoneCandleView.LOCK_BACKGROUND_LIFT,0).commit();
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
        // The low label is at the main price plot bottom, above the existing TDW strip.
        int priceBottom=Math.round(chart[3]-44-(chart[3]-44-chart[1]-15)*.26f-5);
        int[] lowCorner={chart[0],priceBottom-40,chart[0]+160,priceBottom+4};
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

    private void checkSingleChartHeight() throws Exception {
        String symbol = "sz000001", key = PhoneDisplayStocks.HEIGHT_PREFIX + symbol;
        PhoneCandleView view = new PhoneCandleView(isolated);
        view.setTheme(95);
        view.setSurface(PhoneCandleView.Surface.DREAM);
        view.setFrameTime(LocalDateTime.of(2026, 11, 5, 10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        view.layout(0, 0, 540, 1170);
        JSONObject quote = fixture();
        options.edit().putInt(key, 0).apply();
        view.setMarket(quote, symbol);
        Bitmap automatic = render(view);
        try {
            float previousSpan = 0;
            for (int height : new int[]{104, 312}) {
                options.edit().putInt(key, height).apply();
                view.setMarket(quote, symbol);
                Bitmap fixed = Bitmap.createBitmap(540, 1170, Bitmap.Config.ARGB_8888);
                try {
                    float[] rangeY = {Float.NaN, Float.NaN};
                    Canvas canvas = new Canvas(fixed) {
                        @Override public void drawText(String text, float x, float y, Paint paint) {
                            super.drawText(text, x, y, paint);
                            if (paint.getTextAlign() == Paint.Align.LEFT && ("10.63".equals(text) || "9.15".equals(text))) {
                                float[] point = {x, y};
                                getMatrix().mapPoints(point);
                                rangeY["10.63".equals(text) ? 0 : 1] = point[1];
                            }
                        }
                    };
                    view.draw(canvas);
                    // Date, weekday and charging clock sit above the stock on the bookshop theme.
                    if (changed(automatic, fixed, new int[]{0, 80, 1080, 360}) != 0)
                        throw new AssertionError("fixed stock height changed themed date or clock at " + height);
                    float span = rangeY[1] - rangeY[0];
                    if (!(span > previousSpan + 1))
                        throw new AssertionError("day range labels at " + height + " dp span " + span + " px, previous " + previousSpan);
                    previousSpan = span;
                } finally { fixed.recycle(); }
            }
        } finally {
            automatic.recycle();
            options.edit().remove(key).apply();
        }
    }

    private void checkFittedCanvasCharts() throws Exception {
        PhoneCandleView view = new PhoneCandleView(isolated);
        view.setTheme(PhoneCandleView.LOCK_BLACK);
        java.util.List<String> stocks = java.util.Arrays.asList("sz300001", "sh600001", "sz300002");
        java.util.Set<String> minis = java.util.Collections.singleton(stocks.get(1));
        float density = isolated.getResources().getDisplayMetrics().density;
        // The multi-stock artwork reserves 1540 of its 2340 design units for the stock list.
        int height = Math.round(218 * density * 2340 / 1540);
        view.layout(0, 0, 540, height);
        java.util.List<PhoneStockLayout.Page> pages = view.layoutPages(stocks, minis);
        if (pages.size() != 1) throw new AssertionError("two default Canvas charts and Mini K must share a page");
        PhoneStockLayout.Page page = pages.get(0);
        view.setPage(page, "");
        for (int i = 0; i < stocks.size(); i++) {
            String code = stocks.get(i);
            JSONObject quote = fixture().put("symbol", code).put("high", 10.06).put("low", 9.95);
            view.setStock(i, quote, code, "", "1m", i == 1);
        }
        Bitmap screen = Bitmap.createBitmap(540, height, Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(screen));
            int top = 550;
            for (int i = 0; i < page.heights.length; i++) {
                int row = page.heights[i];
                int dp = i == 1 ? 26 : 96;
                if (Math.abs(row * height / 2340f - dp * density) > 1.5f)
                    throw new AssertionError("fitted Canvas row height for " + stocks.get(i));
                if (i != 1) {
                    int fromY = Math.round((top + row * .25f) * height / 2340);
                    int toY = Math.round((top + row * .74f) * height / 2340);
                    boolean candle = false;
                    for (int y = fromY; y < toY && !candle; y++) {
                        for (int x = 185; x < 355 && !candle; x++) {
                            int color = screen.getPixel(x, y);
                            candle = color == 0xffdf978c || color == 0xff83bca1;
                        }
                    }
                    if (!candle) throw new AssertionError("no candles inside compressed Canvas chart " + stocks.get(i));
                }
                top += row;
            }
        } finally { screen.recycle(); }
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
        options.edit().remove("chart_mode").remove("show_depth").remove("show_signals").apply();
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
