package com.kevin.phonekline;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import java.util.function.IntConsumer;

/** Shared charge-state policy; each visible consumer observes only while it is running. */
final class PhoneSamplingRate implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String PLUGGED = "rate_plugged", UNPLUGGED = "rate_unplugged";
    private final Context app;
    private final SharedPreferences prefs;
    private final IntConsumer changed;
    private boolean plugged, active;
    private int current;
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (active) applyPower(i);
        }
    };

    PhoneSamplingRate(Context c, SharedPreferences prefs, IntConsumer changed) {
        app=c.getApplicationContext();
        this.prefs=prefs;
        this.changed=changed;
        migrate(prefs);
    }

    static void migrate(SharedPreferences prefs) {
        if (!prefs.contains("rate") && prefs.contains(PLUGGED) && prefs.contains(UNPLUGGED)) return;
        SharedPreferences.Editor edit=prefs.edit().remove("rate");
        if (!prefs.contains(PLUGGED)) edit.putInt(PLUGGED,5);
        if (!prefs.contains(UNPLUGGED)) edit.putInt(UNPLUGGED,2);
        edit.apply();
    }

    static int value(SharedPreferences prefs, boolean plugged) {
        int fallback=plugged?5:2;
        int hz=prefs.getInt(plugged?PLUGGED:UNPLUGGED,fallback);
        return hz==1 || hz==2 || hz==5 || hz==10 ? hz : fallback;
    }

    void start() {
        if (active) return;
        active=true;
        current=0;
        prefs.registerOnSharedPreferenceChangeListener(this);
        IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent sticky=Build.VERSION.SDK_INT>=33 ? app.registerReceiver(power,filter,Context.RECEIVER_NOT_EXPORTED)
                : app.registerReceiver(power,filter);
        applyPower(sticky);
    }

    private void applyPower(Intent intent) {
        plugged=intent!=null && intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
        refresh();
    }
    private void refresh() {
        int next=value(prefs,plugged);
        if (next!=current) { current=next; changed.accept(next); }
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p,String key) {
        if (active && (key==null || PLUGGED.equals(key) || UNPLUGGED.equals(key))) refresh();
    }
    int current() { return current; }
    void stop() {
        if (!active) return;
        active=false;
        app.unregisterReceiver(power);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }
}
