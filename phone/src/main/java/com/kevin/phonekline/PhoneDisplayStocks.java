package com.kevin.phonekline;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered selection shared by settings, widgets, the wallpaper and the charging screensaver. */
final class PhoneDisplayStocks {
    static final String ORDER = "display_symbols", MINI = "mini_symbols";
    static final String HEIGHT_PREFIX = "full_height_dp_";
    static final int DEFAULT_FULL_HEIGHT_DP = 332;
    static final int HEIGHT_STEP = 26, MIN_HEIGHT_DP = 104, MAX_HEIGHT_DP = 312;

    static int fullHeightDp(SharedPreferences prefs, String code) {
        int value = prefs.getInt(HEIGHT_PREFIX + code, DEFAULT_FULL_HEIGHT_DP);
        if (value == 0) return 0; // Explicitly let this row fill the remaining space.
        if (value >= MIN_HEIGHT_DP && value <= MAX_HEIGHT_DP && value % HEIGHT_STEP == 0) return value;
        return DEFAULT_FULL_HEIGHT_DP;
    }

    static List<String> symbols(SharedPreferences prefs) {
        String order = prefs.getString(ORDER, null);
        List<String> raw = order == null
                ? Arrays.asList(prefs.getString("symbol", "sz000063"), prefs.getString("second_symbol", ""))
                : Arrays.asList(order.split(","));
        Set<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            String code = PhoneRealtimeCandles.normalize(item);
            if (!code.isEmpty()) unique.add(code);
        }
        if (unique.isEmpty()) unique.add("sz000063");
        return new ArrayList<>(unique);
    }

    static Set<String> minis(SharedPreferences prefs) {
        return prefs.getStringSet(MINI, Collections.emptySet());
    }

    static void save(SharedPreferences prefs, List<String> stocks, Set<String> minis) {
        Set<String> selected = new LinkedHashSet<>(minis);
        selected.retainAll(stocks);
        prefs.edit().putString(ORDER, String.join(",", stocks)).putStringSet(MINI, selected)
                .putString("symbol", stocks.get(0))
                .putString("second_symbol", stocks.size() > 1 ? stocks.get(1) : "").apply();
    }

    static String period(Set<String> minis, String code, String fullPeriod) {
        return minis.contains(code) ? "realtime" : fullPeriod;
    }

    static String second(SharedPreferences prefs) {
        String value = PhoneRealtimeCandles.normalize(prefs.getString("second_symbol", ""));
        return value.equals(prefs.getString("symbol", "sz000063")) ? "" : value;
    }

    static String firstMode(SharedPreferences prefs) {
        String value = prefs.getString("first_chart_mode",prefs.getString("chart_mode","1m"));
        return "realtime".equals(value) || "1d".equals(value) ? value : "1m";
    }

    static String mode(SharedPreferences prefs) {
        String value = prefs.getString("second_chart_mode", "1m");
        return "realtime".equals(value) || "1d".equals(value) ? value : "1m";
    }
}
