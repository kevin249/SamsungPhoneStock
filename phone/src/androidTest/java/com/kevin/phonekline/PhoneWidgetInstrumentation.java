package com.kevin.phonekline;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** One UI owns the ambient clock. Exercise the separate, quote-only RemoteViews as an external host. */
final class PhoneWidgetInstrumentation {
    private static final int ID = AppWidgetManager.INVALID_APPWIDGET_ID;
    private PhoneWidgetInstrumentation() { }

    static void check(Context target, Context host) throws Exception {
        SharedPreferences prefs = host.getSharedPreferences("widget-render-instrumentation", Context.MODE_PRIVATE);
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return "phone_dial".equals(name) ? prefs : super.getSharedPreferences(name, mode);
            }
        };
        JSONObject data = fixture();
        try {
            checkPowerRates(context,prefs);
            prefs.edit().clear().putString("symbol", "sz000063").putInt("aod_theme", 104)
                    .putString("chart_mode", "1m").putBoolean("show_depth", true).putBoolean("show_signals", true).commit();
            RemoteViews first = PhoneWidgetRenderer.render(context, ID, data);
            checkWallpaperVisibility();
            checkBoundHostUpgrade(context, host, R.layout.phone_market_widget, 0x7f030000, first);
            checkBoundHostUpgrade(context, host, R.layout.phone_market_widget_v2, 0x7f030001, first);
            View widget = first.apply(host, new FrameLayout(host));
            checkHostContent(widget);
            require(text(widget, R.id.widget_code).contains("000063"), "market code");
            require(text(widget, R.id.widget_chart_legend).contains("1 分钟 K"), "candle mode");
            require(text(widget, R.id.widget_stamp).contains("盘口 14:30:06"), "actual book source time");
            Bitmap candles = plot(widget);
            checkDepthScale(prefs, data);
            JSONObject bookOnly = new JSONObject(data.toString());
            JSONObject updatedBook = bookOnly.getJSONObject("book");
            updatedBook.put("source_at", "2026-11-05 14:30:11");
            updatedBook.getJSONArray("levels").getJSONObject(0).put("volume", 4700);
            PhoneWidgetRenderer.render(context, ID, bookOnly).reapply(host, widget);
            String updatedStamp = text(widget, R.id.widget_stamp);
            require(updatedStamp.contains("14:30:08") && updatedStamp.contains("盘口 14:30:11")
                    && !candles.sameAs(plot(widget)), "depth-only update with unchanged last trade");

            prefs.edit().putBoolean("show_depth", false).commit();
            PhoneWidgetRenderer.render(context, ID, data).reapply(host, widget);
            Bitmap noBook = plot(widget);
            require(!noBook.sameAs(candles), "real book overlay toggle");

            prefs.edit().putBoolean("show_signals", false).commit();
            PhoneWidgetRenderer.render(context, ID, data).reapply(host, widget);
            Bitmap bare = plot(widget);
            require(!bare.sameAs(noBook), "matched trade markers toggle");
            require(hasYellow(bare), "MA5 on the candle chart");

            prefs.edit().putString("chart_mode", "realtime").commit();
            PhoneWidgetRenderer.render(context, ID, data).reapply(host, widget);
            require(text(widget, R.id.widget_chart_legend).contains("分时"), "intraday mode");
            require(!bare.sameAs(plot(widget)), "intraday line vs candle bodies");
            require(!hasYellow(plot(widget)), "no invented average when turnover is unavailable");
            JSONObject weighted = new JSONObject(data.toString());
            JSONArray minuteRows = weighted.getJSONArray("bars");
            for (int i=0; i<minuteRows.length(); i++) minuteRows.getJSONObject(i).put("session_average",35.4+i*.003);
            PhoneWidgetRenderer.render(context, ID, weighted).reapply(host, widget);
            require(hasYellow(plot(widget)), "yellow weighted session average");

            prefs.edit().putInt("aod_theme", 108).commit();
            for (String mode : new String[]{"1m", "realtime"}) {
                prefs.edit().putString("chart_mode", mode).commit();
                for (int[] size : new int[][]{{150,95},{315,285}}) {
                    checkDailyLabels(context, host, widget, data, size[0], size[1]);
                    checkSessionPositions(context, host, widget, prefs, data, size[0], size[1]);
                }
            }
            JSONObject summary = new JSONObject(data.toString());
            summary.remove("bars");
            PhoneWidgetRenderer.render(context, ID, summary, 150, 95).reapply(host, widget);
            Bitmap summaryPlot = plot(widget);
            summary.put("high", 39.77).put("low", 30.10);
            PhoneWidgetRenderer.render(context, ID, summary, 150, 95).reapply(host, widget);
            require(changedLabel(summaryPlot, plot(widget), true) && changedLabel(summaryPlot, plot(widget), false),
                    "quote-only daily numbers");

            prefs.edit().putString("symbol", "sh600519").commit();
            PhoneWidgetRenderer.render(context, ID, null).reapply(host, widget);
            requireNoClock(widget);
            require(text(widget, R.id.widget_code).contains("600519"), "new symbol");
            require(!text(widget, R.id.widget_name).contains("行情测试"), "stale stock name");
            require("—".equals(text(widget, R.id.widget_price)), "empty price");
            require(widget.findViewById(R.id.widget_plot).getVisibility() != View.VISIBLE, "stale chart");
        } finally {
            prefs.edit().clear().commit();
        }
    }

    private static void checkBoundHostUpgrade(Context target, Context hostContext, int oldId, int shippedId, RemoteViews next) {
        RemoteViews old = new RemoteViews(target.getPackageName(), oldId);
        require(oldId == shippedId && oldId != next.getLayoutId(), "distinct upgrade layout ID");
        AppWidgetHostView host = new AppWidgetHostView(hostContext);
        AppWidgetManager manager = AppWidgetManager.getInstance(target);
        AppWidgetProviderInfo info = null;
        for (AppWidgetProviderInfo item : manager.getInstalledProviders()) {
            if (item.provider.getClassName().equals(PhoneMarketWidgetProvider.class.getName())) { info = item; break; }
        }
        require(info != null, "installed widget provider");
        host.setAppWidget(ID, info);
        host.updateAppWidget(old);
        View oldRoot = host.getChildAt(0);
        require(oldRoot instanceof ViewGroup, "existing host widget");
        // LockStar already holds the inflated native views when the package changes.
        if (oldId == R.layout.phone_market_widget) {
            ((ViewGroup) oldRoot).addView(new TextClock(target));
        } else {
            TextView legacyRange = new TextView(target);
            legacyRange.setText("日高 38.71    日低 32.17");
            ((ViewGroup) oldRoot).addView(legacyRange);
        }
        host.updateAppWidget(next);
        require(host.getChildAt(0) != oldRoot, "in-place upgrade reinflated RemoteViews");
        requireNoClock(host);
        requireNoRangeLine(host);
        require(text(host, R.id.widget_price).equals("35.82"), "same host showing upgraded market");
        RemoteViews placeholder = PhoneWidgetRenderer.wallpaperPlaceholder(target);
        require(placeholder.getLayoutId() != next.getLayoutId(), "distinct wallpaper-only layout");
        host.updateAppWidget(placeholder);
        View empty = host.getChildAt(0);
        require(empty instanceof FrameLayout && ((FrameLayout)empty).getChildCount() == 0,
                "no stale quote nodes over the interactive lock wallpaper");
        require(!empty.isClickable() && !empty.hasOnClickListeners() && empty.getContentDescription() == null
                && empty.getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                "inactive, undecorated placeholder");
        empty.layout(0,0,64,48);
        Bitmap underlay = Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888);
        underlay.eraseColor(Color.MAGENTA);
        empty.draw(new Canvas(underlay));
        int[] colors = new int[64*48];
        underlay.getPixels(colors,0,64,0,0,64,48);
        for (int color : colors) require(color == Color.MAGENTA, "placeholder obscured the wallpaper");
        underlay.recycle();
        host.updateAppWidget(next);
        require(host.getChildAt(0) != empty && text(host,R.id.widget_price).equals("35.82"),
                "AOD market restored on the same host");
        plot(host);
    }

    private static void checkWallpaperVisibility() {
        require(PhoneWallpaperService.shouldCoverWidget(true, true, Display.STATE_ON, true), "interactive lock wallpaper");
        require(!PhoneWallpaperService.shouldCoverWidget(false, true, Display.STATE_ON, true), "wallpaper hidden");
        require(!PhoneWallpaperService.shouldCoverWidget(true, true, Display.STATE_ON, false), "wallpaper on home");
        require(!PhoneWallpaperService.shouldCoverWidget(true, false, Display.STATE_ON, true), "screen going off");
        for (int state : new int[]{Display.STATE_OFF,Display.STATE_DOZE,Display.STATE_DOZE_SUSPEND}) {
            require(!PhoneWallpaperService.shouldCoverWidget(true, true, state, true), "AOD owns the quote at " + state);
        }
    }

    private static void checkPowerRates(Context context, SharedPreferences prefs) {
        prefs.edit().clear().putInt("rate",10).commit();
        BroadcastReceiver[] listener={null};
        Intent battery=new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED,0);
        Context simulated=new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) {
                listener[0]=receiver; return battery;
            }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags) {
                listener[0]=receiver; return battery;
            }
            @Override public void unregisterReceiver(BroadcastReceiver receiver) { listener[0]=null; }
        };
        java.util.List<Integer> delivered=new java.util.ArrayList<>();
        PhoneSamplingRate rate=new PhoneSamplingRate(simulated,prefs,delivered::add);
        require(!prefs.contains("rate") && PhoneSamplingRate.value(prefs,true)==5
                && PhoneSamplingRate.value(prefs,false)==2, "legacy settings get separate defaults");
        rate.start();
        listener[0].onReceive(simulated,battery.putExtra(BatteryManager.EXTRA_PLUGGED,BatteryManager.BATTERY_PLUGGED_USB));
        listener[0].onReceive(simulated,battery);
        prefs.edit().putInt(PhoneSamplingRate.UNPLUGGED,1).putInt(PhoneSamplingRate.PLUGGED,10).commit();
        listener[0].onReceive(simulated,battery.putExtra(BatteryManager.EXTRA_PLUGGED,0));
        rate.stop();
        require(delivered.equals(java.util.Arrays.asList(2,5,10,1)), "live power changes update one demand");
        PhoneSamplingRate.migrate(prefs);
        require(PhoneSamplingRate.value(prefs,true)==10 && PhoneSamplingRate.value(prefs,false)==1
                && listener[0]==null,"preserved custom rates and released observer");
    }

    private static JSONObject fixture() throws Exception {
        JSONArray bars = new JSONArray();
        for (int i = 0; i < 31; i++) {
            double mid = 35.82 + .04 * Math.sin(i * .35);
            bars.put(new JSONObject().put("time", String.format(Locale.ROOT, "2026-11-05 14:%02d", i + 1))
                    .put("open", mid).put("high", mid + .05).put("low", mid - .04).put("close", mid + .02));
        }
        JSONArray levels = new JSONArray().put(new JSONObject().put("price", 35.80).put("side", "bid").put("volume", 1400))
                .put(new JSONObject().put("price", 35.84).put("side", "ask").put("volume", 1300));
        JSONArray fills = new JSONArray().put(new JSONObject().put("bar_time", "2026-11-05 14:08").put("price", 35.84).put("side", "B"))
                .put(new JSONObject().put("bar_time", "2026-11-05 14:21").put("price", 35.87).put("side", "S"));
        return new JSONObject().put("symbol", "sz000063").put("name", "行情测试")
                .put("date", "2026-11-05").put("quote_at", "2026-11-05 14:30:08").put("price", 35.82)
                .put("previous", 35.26).put("high", 38.71).put("low", 32.17).put("bars", bars).put("fills", fills)
                .put("book", new JSONObject().put("source_at", "2026-11-05 14:30:06").put("levels", levels));
    }

    private static void checkHostContent(View widget) {
        requireNoClock(widget);
        require(text(widget, R.id.widget_name).contains("行情测试"), "stock name");
        require("35.82".equals(text(widget, R.id.widget_price)), "quote");
        TextView price = widget.findViewById(R.id.widget_price);
        require(price.getCurrentTextColor() == Color.WHITE, "white quote");
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            require(price.getTypeface().getWeight() >= 700, "bold quote");
        }
        requireNoRangeLine(widget);
        require(text(widget, R.id.widget_stamp).contains("14:30"), "market source time");
        for (int id : new int[]{R.id.widget_name,R.id.widget_price}) {
            TextView field = widget.findViewById(id);
            require(field.getVisibility() == View.VISIBLE && Color.luminance(field.getCurrentTextColor()) > .32f,
                    "readable market text " + widget.getResources().getResourceEntryName(id));
        }
    }

    private static Bitmap plot(View widget) {
        ImageView plot = widget.findViewById(R.id.widget_plot);
        require(plot.getVisibility() == View.VISIBLE && plot.getDrawable() instanceof BitmapDrawable, "chart bitmap");
        return ((BitmapDrawable) plot.getDrawable()).getBitmap();
    }

    private static void checkDailyLabels(Context context, Context host, View widget, JSONObject data, int w, int h) throws Exception {
        PhoneWidgetRenderer.render(context, ID, data, w, h).reapply(host, widget);
        checkHostContent(widget);
        Bitmap original = plot(widget);
        int timePixels = 0;
        for (int y = original.getHeight() * 85 / 100; y < original.getHeight(); y++) {
            for (int x = original.getWidth() / 5; x < original.getWidth(); x++) {
                if (brightText(original.getPixel(x, y))) timePixels++;
            }
        }
        require(timePixels >= 8, "readable time axis at " + w + "x" + h);
        JSONObject changed = new JSONObject(data.toString()).put("high", 39.77);
        PhoneWidgetRenderer.render(context, ID, changed, w, h).reapply(host, widget);
        Bitmap newHigh = plot(widget);
        require(changedLabel(original, newHigh, true), "daily high at upper left " + w + "x" + h);
        changed.put("low", 30.10);
        PhoneWidgetRenderer.render(context, ID, changed, w, h).reapply(host, widget);
        require(changedLabel(newHigh, plot(widget), false), "daily low at lower left " + w + "x" + h);
    }

    private static boolean changedLabel(Bitmap a, Bitmap b, boolean top) {
        // This fixture has only afternoon prices, so its left corners contain only the overlaid labels.
        // In a compact candle widget the TDW panel can put the low above the bitmap midpoint.
        int y0 = 0;
        int y1 = top ? a.getHeight() / 2 : a.getHeight();
        int changed = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < a.getWidth() / 6; x++) {
                if (brightText(a.getPixel(x,y)) != brightText(b.getPixel(x,y))) changed++;
            }
        }
        return changed >= 6;
    }

    private static void checkSessionPositions(Context context, Context host, View widget, SharedPreferences prefs,
                                              JSONObject base, int w, int h) throws Exception {
        JSONArray bars = new JSONArray(), withoutProbes = new JSONArray();
        for (String time : new String[]{"09:31", "10:30", "13:01"}) bars.put(positionBar(time));
        // Keep more than 60 subsequent candles. A rolling 60-minute viewport loses all three probes.
        for (int i = 2; i <= 75; i++) {
            JSONObject bar = positionBar(String.format(Locale.ROOT, "%02d:%02d", 13+i/60, i%60));
            bars.put(bar);
            withoutProbes.put(bar);
        }
        JSONObject tradingDay = new JSONObject(base.toString()).put("bars", bars);
        PhoneWidgetRenderer.render(context, ID, tradingDay, w, h).reapply(host, widget);
        Bitmap full = plot(widget);
        // Capture the literal text and its alignment through the production Canvas draw path.
        int plotWidth = w < 205 ? 450 : 650;
        int plotHeight = Math.round(plotWidth * full.getHeight() / (float)full.getWidth());
        AxisLabels axis = new AxisLabels(plotWidth);
        new PhoneChartOptions(prefs).buildWidget(tradingDay).drawWidget(axis, 0, 0, plotWidth, plotHeight);
        require(axis.openX == 0 && Math.abs(axis.closeX - axis.gridRight) < 1 && axis.closeX < axis.priceLeft,
                "15:00 ends at the grid before the price column " + w + "x" + h);
        require(axis.highX >= 0 && axis.highX <= plotWidth*.03 && axis.lowX >= 0 && axis.lowX <= plotWidth*.03
                && axis.highY > 0 && axis.highY < axis.lowY && axis.lowY <= axis.timeY,
                "daily numbers inside the plotting bounds " + w + "x" + h);
        for (int i = 0; i <= 4; i++)
            require(axis.gridLines.contains(Math.round(axis.closeX * i / 4)), "trading hour grid " + i);
        require(axis.rightPrice, "right-side price ticks " + w + "x" + h);

        tradingDay.put("bars", withoutProbes);
        PhoneWidgetRenderer.render(context, ID, tradingDay, w, h).reapply(host, widget);
        Bitmap omitted = plot(widget);
        require(differsInBand(full, omitted, 0, .025), "opening price under left corner labels");
        float plotFraction = axis.gridRight / plotWidth;
        require(differsInBand(full, omitted, .235 * plotFraction, .265 * plotFraction), "10:30 price at one-quarter session");
        require(differsInBand(full, omitted, .485 * plotFraction, .515 * plotFraction), "afternoon open at half session");
        require(!differsInBand(full, omitted, .30 * plotFraction, .45 * plotFraction)
                && !differsInBand(full, omitted, .60 * plotFraction, .95 * plotFraction),
                "removing three timestamps must not slide the other prices");
    }

    private static JSONObject positionBar(String time) throws Exception {
        return new JSONObject().put("time","2026-11-05 " + time).put("open",35.32).put("high",37.60)
                .put("low",34.20).put("close",35.82);
    }

    private static boolean differsInBand(Bitmap a, Bitmap b, double left, double right) {
        for (int y=0; y<a.getHeight()*4/5; y++) {
            for (int x=(int)(a.getWidth()*left); x<a.getWidth()*right; x++) {
                if (a.getPixel(x,y) != b.getPixel(x,y)) return true;
            }
        }
        return false;
    }

    private static final class AxisLabels extends Canvas {
        final int width;
        final java.util.Set<Integer> gridLines = new java.util.HashSet<>();
        float gridRight = Float.NaN, priceLeft = Float.POSITIVE_INFINITY;
        boolean rightPrice;
        float shortDepth = Float.POSITIVE_INFINITY, longDepth;
        float openX = Float.NaN, closeX = Float.NaN, timeY = Float.NaN;
        float highX = Float.NaN, lowX = Float.NaN, highY = Float.NaN, lowY = Float.NaN;

        AxisLabels(int width) { this.width = width; }

        @Override public void drawLine(float x0, float y0, float x1, float y1, Paint paint) {
            int color = paint.getColor();
            boolean neutral = Math.abs(Color.red(color)-Color.green(color)) <= 24
                    && Math.abs(Color.blue(color)-Color.green(color)) <= 24;
            if (neutral && x0 == x1 && Math.abs(y1-y0) > 10) {
                gridLines.add(Math.round(x0));
            }
            if (neutral && y0 == y1 && Math.abs(x1-x0) >= width*.55f) gridRight = x1;
            if (((color & 0xffffff) == (PhoneMarketPlot.WIDGET_UP & 0xffffff)
                    || (color & 0xffffff) == (PhoneMarketPlot.WIDGET_DOWN & 0xffffff))
                    && y0 == y1 && x1 > width*.55f && x1 > x0) {
                shortDepth = Math.min(shortDepth, x1-x0);
                longDepth = Math.max(longDepth, x1-x0);
            }
        }

        @Override public void drawText(String text, float x, float y, Paint paint) {
            if ("09:30".equals(text) && paint.getTextAlign() == Paint.Align.LEFT) { openX = x; timeY = y; }
            if ("15:00".equals(text) && paint.getTextAlign() == Paint.Align.RIGHT) closeX = x;
            if ("38.71".equals(text) && paint.getTextAlign() == Paint.Align.LEFT) { highX = x; highY = y; }
            if ("32.17".equals(text) && paint.getTextAlign() == Paint.Align.LEFT) { lowX = x; lowY = y; }
            if (paint.getTextAlign() == Paint.Align.RIGHT && x >= width*.95f && text.matches("[0-9]+\\.[0-9]{2}")) {
                rightPrice = true;
                priceLeft = Math.min(priceLeft, x - paint.measureText(text));
            }
        }
    }

    private static void checkDepthScale(SharedPreferences prefs, JSONObject base) throws Exception {
        JSONObject data = new JSONObject(base.toString());
        data.remove("fills");
        JSONArray levels = new JSONArray()
                .put(new JSONObject().put("price",34.5).put("side","bid").put("volume",1000))
                .put(new JSONObject().put("price",36.5).put("side","ask").put("volume",10));
        data.getJSONObject("book").put("levels",levels);
        AxisLabels canvas = new AxisLabels(450);
        new PhoneChartOptions(prefs).buildWidget(data).drawWidget(canvas,0,0,450,160);
        require(canvas.longDepth > 0 && Math.abs(canvas.shortDepth/canvas.longDepth - .01f) < .001f,
                "L2 widths preserve volume ratio including a one-percent order");
    }

    private static boolean brightText(int pixel) {
        int min = Math.min(Color.red(pixel), Math.min(Color.green(pixel), Color.blue(pixel)));
        int max = Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel)));
        return min >= 167 && max - min <= 48;
    }

    private static boolean hasYellow(Bitmap plot) {
        for (int y=0; y<plot.getHeight(); y++) for (int x=0; x<plot.getWidth(); x++) {
            int pixel=plot.getPixel(x,y);
            if (Color.red(pixel)>185 && Color.green(pixel)>150 && Color.green(pixel)-Color.blue(pixel)>65)
                return true;
        }
        return false;
    }

    private static void requireNoRangeLine(View view) {
        if (view instanceof TextView && view.getVisibility() == View.VISIBLE) {
            String value = ((TextView)view).getText().toString();
            require(!value.contains("日高") && !value.contains("日低") && !value.contains("38.71") && !value.contains("32.17"),
                    "daily numbers must be inside the chart");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0; i<group.getChildCount(); i++) requireNoRangeLine(group.getChildAt(i));
        }
    }

    private static void requireNoClock(View view) {
        require(!(view instanceof TextClock), "duplicate system clock/date");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i=0; i<group.getChildCount(); i++) requireNoClock(group.getChildAt(i));
        }
    }

    private static String text(View widget, int id) {
        TextView field = widget.findViewById(id);
        require(field != null, "missing widget field " + id);
        return field.getText().toString();
    }

    private static void require(boolean ok, String detail) {
        if (!ok) throw new AssertionError("widget: " + detail);
    }
}
