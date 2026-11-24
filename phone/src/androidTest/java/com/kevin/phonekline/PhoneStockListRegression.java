package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

final class PhoneStockListRegression {
    static void check(Context context, Context host, SharedPreferences prefs) throws Exception {
        android.os.Bundle lockStar = new android.os.Bundle();
        lockStar.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, 1);
        lockStar.putInt("semWidgetSize", 1);
        require(!PhoneWidgetRenderer.isDesktopWidget(lockStar), "LockStar must not launch settings after unlock");
        lockStar.putInt("hsWidgetDisplayId", 0);
        require(PhoneWidgetRenderer.isDesktopWidget(lockStar), "One UI Home can open settings");
        lockStar.putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, 2);
        require(!PhoneWidgetRenderer.isDesktopWidget(lockStar), "keyguard takes precedence over launcher hints");
        require(!PhoneWidgetRenderer.isDesktopWidget(new android.os.Bundle()), "unknown host is passive");
        prefs.edit().clear().putString("symbol", "sz000063").putString("second_symbol", "sh600000").commit();
        String a = "sz000063", b = "sh600000", c = "sh600001", d = "sz000001";
        require(PhoneDisplayStocks.symbols(prefs).equals(Arrays.asList(a, b)), "legacy order");
        require(PhoneDisplayStocks.minis(prefs).isEmpty(), "legacy style");
        List<String> codes = Arrays.asList(c, a, b, d);
        PhoneDisplayStocks.save(prefs, codes, new HashSet<>(Arrays.asList(c, b, d)));
        require(PhoneDisplayStocks.symbols(prefs).equals(codes), "round-trip order");
        for (String code : codes) {
            JSONObject bar = new JSONObject().put("time", "2026-11-23 09:31").put("open", 10)
                    .put("close", 11).put("low", 10).put("high", 11);
            JSONObject quote = new JSONObject().put("symbol", code).put("date", "2026-11-23")
                    .put("price", 11).put("previous", 10).put("bars", new JSONArray().put(bar));
            prefs.edit().putString("snapshot_" + code, quote.toString()).apply();
        }
        RemoteViews remote = PhoneWidgetRenderer.render(context, -1, null, 300, 300, "realtime", true);
        View root = remote.apply(host, new FrameLayout(host));
        ViewGroup rows = root.findViewById(R.id.widget_zz_stock_rows);
        require(rows.getChildCount() == 4, "four rows");
        int w = Math.round(300 * host.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, w);
        require(rows.getChildAt(0).getHeight() > 0 && rows.getChildAt(1).getHeight() > rows.getChildAt(0).getHeight(), "mixed heights");
        require(rows.getChildAt(0).getContentDescription().toString().contains("+10.00%"), "mini quote");
        require(rows.getChildAt(3).getContentDescription().toString().contains(d), "last symbol");
        require(root.findViewById(R.id.widget_zz_next_page).getVisibility() == View.GONE, "one page fits");

        prefs.edit().putInt(PhoneWidgetRenderer.PAGE + (-1), 1).apply();
        PhoneWidgetRenderer.render(context, -1, null, 200, 79, "realtime", true).reapply(host, root);
        TextView footer = root.findViewById(R.id.widget_zz_next_page);
        require(footer.getText().toString().startsWith("2 / 3"), "page selection");
        require(rows.getChildCount() == 1 && rows.findViewById(R.id.widget_price) != null, "second page loads full panel");
        int lockId = 73;
        String pageKey = PhoneWidgetRenderer.PAGE + lockId;
        PhoneWidgetRenderer.PassivePages rotation = new PhoneWidgetRenderer.PassivePages();
        rotation.update(prefs, lockId, 3, 1000, false);
        rotation.update(prefs, lockId, 3, 30_999, false);
        require(prefs.getInt(pageKey, 0) == 0, "first passive page remains readable");
        rotation.update(prefs, lockId, 3, 31_000, false);
        require(prefs.getInt(pageKey, -1) == 1, "AOD rotates after 30 seconds");
        rotation.update(prefs, lockId, 3, 91_000, true);
        rotation.update(prefs, lockId, 3, 120_999, true);
        require(prefs.getInt(pageKey, -1) == 1, "waking shows the last AOD page for a full interval");
        rotation.update(prefs, lockId, 3, 121_000, true);
        require(prefs.getInt(pageKey, -1) == 2, "lit lock screen resumes rotation");
        rotation.update(prefs, lockId, 3, 190_000, false);
        require(prefs.getInt(pageKey, -1) == 2, "entering AOD keeps the lock page");
        rotation.onReceive(context, new android.content.Intent(android.content.Intent.ACTION_SCREEN_ON));
        rotation.onReceive(context, new android.content.Intent(android.content.Intent.ACTION_SCREEN_OFF));
        rotation.update(prefs, lockId, 3, 990_000, false);
        require(prefs.getInt(pageKey, -1) == 2, "screen transitions without quote updates preserve the page");
        rotation.update(prefs, lockId, 3, 1_020_000, false);
        require(prefs.getInt(pageKey, -1) == 0, "AOD still cycles through all pages");
        prefs.edit().putInt(pageKey, 2).apply();
        new PhoneWidgetRenderer.PassivePages().update(prefs, lockId, 3, 5000, true);
        require(prefs.getInt(pageKey, -1) == 2, "first frame after process restart keeps the saved page");
        require(prefs.getInt(PhoneWidgetRenderer.PAGE + (-1), -1) == 1, "rotation leaves desktop page selected");
        PhoneDisplayStocks.save(prefs, Arrays.asList(d, c), Collections.singleton(c));
        require(PhoneDisplayStocks.minis(prefs).equals(Collections.singleton(c)), "styles follow symbol");
        prefs.edit().clear().commit();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
