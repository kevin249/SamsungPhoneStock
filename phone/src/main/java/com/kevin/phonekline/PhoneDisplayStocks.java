package com.kevin.phonekline;

import android.content.SharedPreferences;

/** Shared upper/lower selection for widgets, live wallpapers and charging screensavers. */
final class PhoneDisplayStocks {
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
