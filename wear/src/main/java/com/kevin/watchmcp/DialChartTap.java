package com.kevin.watchmcp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Wear 的复杂功能只派发单击。第二次点击同一图表才轮换自选股。 */
public final class DialChartTap extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("watch", Context.MODE_PRIVATE);
        long now=SystemClock.elapsedRealtime(), last=prefs.getLong("dial_tap_at",0);
        long gap=now-last;
        android.util.Log.d("WatchDial","chart tap interval " + (last==0 ? -1 : gap));
        if (last==0 || gap<0 || gap>750) {
            prefs.edit().putLong("dial_tap_at",now).apply();
            return;
        }
        Watchlist list=new Watchlist(prefs.getString("watchlist_symbols",null),DialMarket.symbol(prefs));
        SharedPreferences.Editor change=prefs.edit().remove("dial_tap_at");
        if (!list.move(1)) { change.apply(); return; }
        change.putString("symbol",list.selected()).apply();
        DialProvider.redraw(context);
        DialMarket.refresh(context);
    }
}
