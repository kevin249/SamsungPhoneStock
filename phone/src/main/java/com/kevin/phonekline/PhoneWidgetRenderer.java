package com.kevin.phonekline;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class PhoneWidgetRenderer {
    private static final DateTimeFormatter MARKET_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.CHINA);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

    private PhoneWidgetRenderer() {}

    static RemoteViews wallpaperPlaceholder(Context context) {
        return new RemoteViews(context.getPackageName(), R.layout.phone_market_widget_wallpaper);
    }

    static RemoteViews render(Context context, int widgetId, JSONObject snapshot) {
        Bundle options = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId);
        boolean landscape = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int width = dimension(options, landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
                landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 280);
        int height = dimension(options, landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,
                landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 210);
        return render(context, widgetId, snapshot, width, height);
    }

    static RemoteViews render(Context context, int widgetId, JSONObject snapshot, int width, int height) {
        SharedPreferences prefs = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        String symbol = PhoneRealtimeCandles.normalize(prefs.getString("symbol", "sz000063"));
        if (symbol.isEmpty()) symbol = "sz000063";
        if (snapshot != null && !symbol.equals(PhoneRealtimeCandles.normalize(snapshot.optString("symbol")))) snapshot = null;
        PhoneChartOptions chartOptions = new PhoneChartOptions(prefs);
        PhoneMarketPlot plot = chartOptions.buildWidget(snapshot);
        String chartDescription = chartOptions.legend(plot) + " · " + chartOptions.overlays(plot);
        String code = symbol.substring(2);
        String name = snapshot == null ? "" : snapshot.optString("name");
        if ("null".equals(name) || name.equals(code) || name.equals(symbol)) name = "";

        double last = positive(snapshot, "price");
        if (!Double.isFinite(last)) last = plot.lastClose();
        boolean priced = Double.isFinite(last);
        boolean hasPoints = Double.isFinite(plot.lastClose());
        String price = PhoneMarketPlot.format(last);
        double change = (last / positive(snapshot, "previous") - 1) * 100;
        double dayHigh = positive(snapshot, "high"), dayLow = positive(snapshot, "low");
        boolean compact = width < 205 || height < 125;
        String stamp = freshness(snapshot, priced, hasPoints, plot.hasBook(), compact, chartOptions.title());
        if (prefs.getBoolean("paused", false)) stamp = "已暂停 · " + stamp;

        int padding = compact ? 3 : 7;
        int innerWidth = Math.max(1, width - 2 * padding);
        int lineSize = compact ? 10 : 13;
        int nameSize = compact ? 12 : 16;
        int changeSize = compact ? 11 : 16;
        // Keep room for the stock name even when a four-digit quote replaces a two-digit quote.
        int priceSize = Math.max(14, Math.min(Math.min(height / 3, compact ? 32 : 48),
                innerWidth / price.length()));
        boolean showStamp = height >= 74 || !hasPoints;
        boolean showLegend = hasPoints && height >= 143 && innerWidth >= 200;
        int headerHeight = (int)Math.ceil(1.25 * Math.max(priceSize, nameSize + (Double.isFinite(change) ? changeSize : 0)));
        int lines = (showLegend ? 1 : 0) + (showStamp ? 1 : 0);
        int chartHeight = height - 2 * padding - headerHeight - (int)Math.ceil(lines * lineSize * 1.3);
        boolean showChart = (hasPoints || Double.isFinite(dayHigh) || Double.isFinite(dayLow)) && innerWidth >= 90 && chartHeight >= 21;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.phone_market_widget_v3);
        float density = context.getResources().getDisplayMetrics().density;
        int inset = Math.round(padding * density);
        views.setViewPadding(R.id.widget_root, inset, inset, inset, inset);
        size(views, R.id.widget_price, priceSize);
        size(views, R.id.widget_name, nameSize);
        size(views, R.id.widget_code, lineSize);
        size(views, R.id.widget_change, changeSize);
        size(views, R.id.widget_chart_legend, lineSize);
        size(views, R.id.widget_stamp, lineSize);
        views.setTextViewText(R.id.widget_price, price);
        views.setTextViewText(R.id.widget_name, name.isEmpty() ? code : name);
        views.setTextViewText(R.id.widget_code, code);
        show(views, R.id.widget_code, !name.isEmpty() && innerWidth >= 205);
        views.setTextViewText(R.id.widget_change, Double.isFinite(change) ? String.format(Locale.ROOT, "%+.2f%%", change) : "");
        views.setTextColor(R.id.widget_change, change > 0 ? PhoneMarketPlot.WIDGET_UP : change < 0 ? PhoneMarketPlot.WIDGET_DOWN : Color.WHITE);
        show(views, R.id.widget_change, Double.isFinite(change));
        views.setTextViewText(R.id.widget_chart_legend, chartDescription);
        show(views, R.id.widget_chart_legend, showChart && showLegend);
        show(views, R.id.widget_plot, showChart);
        if (showChart) {
            views.setContentDescription(R.id.widget_plot, chartDescription);
            views.setImageViewBitmap(R.id.widget_plot, chart(plot, innerWidth, chartHeight, density));
        }
        views.setTextViewText(R.id.widget_stamp, stamp);
        show(views, R.id.widget_stamp, showStamp);
        views.setContentDescription(R.id.widget_root, (name.isEmpty() ? "" : name + " ") + code + "，现价 "
                + price + "，当日最高价 " + PhoneMarketPlot.format(dayHigh) + "，当日最低价 " + PhoneMarketPlot.format(dayLow)
                + "，" + chartDescription + "，" + stamp + "，点按打开应用");
        Intent open = new Intent(context, PhoneDialActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, widgetId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return views;
    }

    private static Bitmap chart(PhoneMarketPlot plot, int widthDp, int heightDp, float density) {
        // Draw at the aspect ratio of the chart slot. Limit pixel memory sent to the widget host.
        int designWidth = Math.max(450, Math.min(900, Math.round(widthDp * 2.1f)));
        int designHeight = Math.max(90, Math.round(designWidth * (heightDp / (float)widthDp)));
        float scale = Math.min(widthDp * density / designWidth, (float)Math.sqrt(400_000.0 / designWidth / designHeight));
        int pixelsWide = Math.max(1, Math.round(designWidth * scale));
        int pixelsHigh = Math.max(1, Math.round(designHeight * scale));
        Bitmap bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.RGB_565);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        canvas.scale(pixelsWide / (float)designWidth, pixelsHigh / (float)designHeight);
        plot.drawWidget(canvas, 0, 0, designWidth - 1, designHeight - 1);
        return bitmap;
    }

    private static String freshness(JSONObject snapshot, boolean priced, boolean hasPoints, boolean hasBook,
                                    boolean compact, String chartTitle) {
        if (!priced) return "暂无报价 · 等待" + chartTitle;
        LocalDateTime at = PhoneRealtimeCandles.localTime(snapshot.optString("quote_at"));
        String stamp = at == null ? "快照 · 时间未知" : "快照 " + MARKET_TIME.format(at);
        JSONObject depth = hasBook ? snapshot.optJSONObject("book") : null;
        LocalDateTime book = depth == null ? null : PhoneRealtimeCandles.localTime(depth.optString("source_at"));
        if (book != null && at != null) {
            if (compact && at.toLocalDate().equals(PhoneRealtimeCandles.now().toLocalDate())) {
                stamp = "价 " + CLOCK.format(at) + "  盘 " + CLOCK.format(book);
            } else {
                stamp = MARKET_TIME.format(at) + (compact ? "  盘 " : " · 盘口 ") + CLOCK.format(book);
            }
        }
        if (!hasPoints) stamp += " · 暂无" + chartTitle;
        return stamp;
    }

    private static double positive(JSONObject snapshot, String key) {
        double value = snapshot == null ? Double.NaN : snapshot.optDouble(key);
        return PhoneRealtimeCandles.positive(value) ? value : Double.NaN;
    }

    private static int dimension(Bundle options, String primary, String secondary, int fallback) {
        if (options == null) return fallback;
        int value = options.getInt(primary);
        if (value <= 0) value = options.getInt(secondary);
        return value > 0 ? value : fallback;
    }

    private static void show(RemoteViews views, int id, boolean visible) {
        views.setViewVisibility(id, visible ? View.VISIBLE : View.GONE);
    }

    private static void size(RemoteViews views, int id, int dp) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dp);
    }
}
