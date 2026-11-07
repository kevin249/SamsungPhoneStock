package com.kevin.phonekline;

import android.content.SharedPreferences;
import org.json.JSONObject;

/** The canvas and RemoteViews use identical chart and overlay settings. */
final class PhoneChartOptions {
    final boolean intraday, signals, depth;
    final String period;

    PhoneChartOptions(SharedPreferences prefs) {
        this(prefs, prefs.getString("chart_mode", "1m"));
    }

    PhoneChartOptions(SharedPreferences prefs, String period) {
        this.period = period;
        intraday = "realtime".equals(period);
        signals = prefs.getBoolean("show_signals", true);
        depth = prefs.getBoolean("show_depth", true);
    }

    boolean matches(PhoneChartOptions other) {
        return period.equals(other.period) && intraday == other.intraday && signals == other.signals && depth == other.depth;
    }

    PhoneMarketPlot build(JSONObject snapshot) {
        return new PhoneMarketPlot(snapshot, intraday, signals, depth);
    }

    PhoneMarketPlot buildWidget(JSONObject snapshot) {
        return new PhoneMarketPlot(snapshot, intraday, signals, depth, true);
    }

    String title() { return intraday ? "分时" : "1d".equals(period) ? "1day" : "1 分钟 K 线"; }

    static String intervalLabel(int hz) {
        String seconds = hz == 1 ? "1" : Double.toString(1.0 / hz);
        return "获取间隔 " + seconds + " 秒";
    }

    String legend(PhoneMarketPlot plot) {
        String average = intraday ? (plot.hasAverage() ? "成交均价" : "暂无均价") : "MA5";
        return title() + " · " + average;
    }

    String overlays(PhoneMarketPlot plot) {
        String book = depth ? plot.bookLabel() : "盘口已关";
        String marks = !signals ? "B/S 已关" : plot.hasSignals() ? "B/S" : "暂无 B/S";
        return book + " · " + marks;
    }
}
