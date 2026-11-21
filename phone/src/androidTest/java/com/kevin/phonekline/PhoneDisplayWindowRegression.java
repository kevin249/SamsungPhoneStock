package com.kevin.phonekline;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Handler transitions and socket observations; never changes user time, power or preferences. */
final class PhoneDisplayWindowRegression {
    static void check(Instrumentation test) throws Exception {
        Context target = test.getTargetContext();
        SharedPreferences prefs = target.getSharedPreferences("display-window-test",Context.MODE_PRIVATE);
        String day = PhoneRealtimeCandles.now().toLocalDate().toString();
        JSONObject cached = new JSONObject().put("symbol","sh603986").put("name","测试").put("date",day)
                .put("price",10).put("high",12).put("low",9).put("previous",10).put("quote_at",day+" 10:00:00")
                .put("bars",new JSONArray().put(new JSONObject().put("time",day+" 09:31")
                        .put("open",10).put("high",12).put("low",9).put("close",10)));
        cached.put("fills",new JSONArray().put(new JSONObject().put("bar_time",day+" 09:31").put("side","B").put("price",10)));
        JSONObject daily = PhoneDailyCandles.history("sh603986","测试",cached.getJSONArray("bars"));
        prefs.edit().clear().putBoolean("paused",true).putString("symbol","sh603986")
                .putString("snapshot_sh603986",cached.toString()).putString("daily_snapshot_sh603986",daily.toString())
                .putString("live_calendar_date",day).putBoolean("live_calendar_open",true).commit();
        try {
            for (boolean wallpaper : new boolean[]{true,false}) for (boolean plugged : new boolean[]{true,false}) {
                Context context = context(target,prefs,plugged);
                for (String[] row : new String[][]{{"09:15","1day","分时"},{"15:00","分时","1day"}}) {
                    LocalDateTime before = LocalDateTime.parse(day+"T"+row[0]).minusNanos(400_000_000);
                    long start = SystemClock.elapsedRealtime();
                    PhoneSystemDisplay[] display = {null};
                    try {
                        test.runOnMainSync(() -> {
                            display[0] = new PhoneSystemDisplay(context,wallpaper,()->{},
                                    () -> before.plusNanos((SystemClock.elapsedRealtime()-start)*1_000_000),quote -> {});
                            display[0].show();
                            expect(display[0],row[1]);
                        });
                        SystemClock.sleep(1000);
                        test.runOnMainSync(() -> expect(display[0],row[2]));
                    } finally { test.runOnMainSync(() -> { if (display[0]!=null) display[0].close(); }); }
                }
                test.runOnMainSync(() -> {
                    for (String at : new String[]{"11:30","12:00","13:00"}) {
                        PhoneSystemDisplay display = new PhoneSystemDisplay(context,wallpaper,()->{},
                                () -> LocalDateTime.parse(day+"T"+at),quote -> {});
                        try { display.show(); expect(display,"分时"); } finally { display.close(); }
                    }
                });
                ((PhoneRenderInstrumentation)test).record("WINDOW lock="+wallpaper+" plugged="+plugged+" boundaries+lunch PASS",true);
            }
            Context context = context(target,prefs,false);
            test.runOnMainSync(() -> {
                for (String mode : new String[]{"realtime","1m","1d"}) {
                    prefs.edit().putBoolean(PhoneDisplayWindow.AUTOMATIC,false).putString(PhoneDisplayWindow.MODE,mode).commit();
                    for (boolean wallpaper : new boolean[]{true,false}) for (String at : new String[]{"08:00","12:00","16:00"}) {
                        PhoneSystemDisplay display = new PhoneSystemDisplay(context,wallpaper,()->{},
                                () -> LocalDateTime.parse(day+"T"+at),quote -> {});
                        try { display.show(); expect(display,mode.equals("realtime")?"分时":mode.equals("1d")?"1day":"1 分钟 K 线"); }
                        finally { display.close(); }
                        require(PhoneLiveSchedule.displayPeriod(context,LocalDateTime.parse(day+"T"+at)).equals(mode),"AOD shares manual mode");
                    }
                }
                prefs.edit().putBoolean(PhoneDisplayWindow.AUTOMATIC,true).commit();
            });
            test.runOnMainSync(() -> {
                PhoneDisplayFeed offline = new PhoneDisplayFeed(prefs,(quote,status) -> {
                    require(quote != null && quote.optJSONArray("fills") != null && quote.optJSONArray("fills").length() > 0,
                            "cached daily retains accepted B/S after restart");
                    require(!new PhoneMarketPlot(quote,false,true,true).hasSignals(),"cached daily hides B/S");
                    require(!new PhoneMarketPlot(quote,false,true,true).hasBook(),"cached daily hides depth");
                });
                try { offline.start("sh603986","1d",1,false,true); } finally { offline.stop(); }
            });
            checkNetworkStops(test,context,prefs,day);
        } finally { prefs.edit().clear().commit(); }
    }

    private static void checkNetworkStops(Instrumentation test,Context context,SharedPreferences prefs,String day) throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0,30,InetAddress.getByName("127.0.0.1"))) {
            Thread accept = new Thread(() -> {
                while (!server.isClosed()) {
                    try (java.net.Socket socket = server.accept()) { connections.incrementAndGet(); }
                    catch (java.io.IOException closed) { break; }
                }
            },"WindowLoopback");
            accept.setDaemon(true); accept.start();
            PhoneSecretStore.save(prefs,"window-regression-only");
            prefs.edit().putBoolean("paused",false).putString("mcp_url","https://127.0.0.1:"+server.getLocalPort())
                    .putBoolean(PhoneDisplayWindow.AUTOMATIC,true).putString(PhoneDisplayWindow.MODE,"realtime").commit();
            LocalDateTime[] now = {LocalDateTime.parse(day+"T16:00")};
            PhoneSystemDisplay[] display = {null};
            try {
                test.runOnMainSync(() -> {
                    display[0] = new PhoneSystemDisplay(context,false,()->{},() -> now[0],quote -> {});
                    display[0].show(); expect(display[0],"1day");
                });
                SystemClock.sleep(700);
                require(connections.get()==0,"off-hours cold start must make no MCP connection");
                test.runOnMainSync(() -> {
                    now[0]=LocalDateTime.parse(day+"T12:00");
                    prefs.edit().putString(PhoneDisplayWindow.MODE,"1m").apply();
                });
                awaitConnection(connections,0);
                test.runOnMainSync(() -> {
                    expect(display[0],"分时"); now[0]=LocalDateTime.parse(day+"T16:00");
                    prefs.edit().putString(PhoneDisplayWindow.MODE,"1d").apply();
                });
                SystemClock.sleep(400);
                int stopped = connections.get();
                SystemClock.sleep(1600);
                require(connections.get()==stopped,"no requests after switching to cached daily");
                test.runOnMainSync(() -> {
                    expect(display[0],"1day"); now[0]=LocalDateTime.parse(day+"T12:00");
                    prefs.edit().putString(PhoneDisplayWindow.MODE,"realtime").apply();
                });
                awaitConnection(connections,stopped);
                ((PhoneRenderInstrumentation)test).record("MANUAL/AUTO modes + no off-hours sockets + stop/resume PASS",true);
            } finally { test.runOnMainSync(() -> { if(display[0]!=null)display[0].close(); }); }
        }
    }

    private static void awaitConnection(AtomicInteger connections,int previous) {
        long until = SystemClock.elapsedRealtime()+5000;
        while(connections.get()<=previous && SystemClock.elapsedRealtime()<until) SystemClock.sleep(50);
        require(connections.get()>previous,"active window must resume network requests");
    }

    private static Context context(Context target,SharedPreferences prefs,boolean plugged) {
        return new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return name.equals("phone_dial")?prefs:super.getSharedPreferences(name,mode);
            }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter) { return battery(); }
            @Override public Intent registerReceiver(BroadcastReceiver receiver,IntentFilter filter,int flags) { return battery(); }
            @Override public void unregisterReceiver(BroadcastReceiver receiver) { }
            private Intent battery() { return new Intent(Intent.ACTION_BATTERY_CHANGED)
                    .putExtra(BatteryManager.EXTRA_PLUGGED,plugged?BatteryManager.BATTERY_PLUGGED_USB:0); }
        };
    }

    private static void expect(PhoneSystemDisplay display,String mode) {
        require(display.view.getContentDescription().toString().contains(mode),"display period "+mode);
    }
    private static void require(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
}
