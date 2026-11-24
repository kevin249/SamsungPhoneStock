package com.kevin.phonekline;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;

/** Exercises actual SharedPreferences, subscription bootstrap, offline feeds and widget restoration. */
final class PhoneSignalPersistenceRegression {
    static void check(Instrumentation test,boolean repair) throws Exception {
        Context target=test.getTargetContext();
        SharedPreferences prefs=target.getSharedPreferences("signal-persistence-test",Context.MODE_PRIVATE);
        String symbol="sh603986";
        LocalDate date=PhoneRealtimeCandles.now().toLocalDate();
        while (date.getDayOfWeek().getValue()>=6) date=date.minusDays(1);
        String day=date.toString();
        LocalDate earlier=date.minusDays(1);
        while (earlier.getDayOfWeek().getValue()>=6) earlier=earlier.minusDays(1);
        JSONObject daily=PhoneDailyCandles.history(symbol,"测试",new JSONArray()
                .put(bar(earlier+" 15:00")).put(bar(day+" 15:00")));
        JSONArray old=new JSONArray().put(mark(earlier+" 09:31","S"));
        JSONArray current=new JSONArray().put(mark(day+" 09:31","B"));
        JSONObject minute=new JSONObject().put("symbol",symbol).put("date",day).put("quote_at",day+" 15:00:00")
                .put("price",10).put("high",12).put("low",8).put("bars",new JSONArray().put(bar(day+" 09:31")))
                .put("fills",current);
        prefs.edit().clear().putString("symbol",symbol).putString("snapshot_"+symbol,minute.toString())
                .putString("daily_snapshot_"+symbol,daily.toString()).commit();
        Context isolated=new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "phone_dial".equals(name)?prefs:super.getSharedPreferences(name,mode);
            }
        };
        try {
            // Upgrade migration must work before the old caches are replaced by a signal-free response.
            PhoneSignalStore.migrate(prefs,symbol);
            PhoneSignalStore.remember(prefs,new JSONObject().put("symbol",symbol).put("fills",old));
            JSONObject empty=PhoneRealtimeCandles.copy(minute).put("fills",new JSONArray());
            prefs.edit().putString("snapshot_"+symbol,empty.toString()).commit();
            require(PhoneSignalStore.accept(prefs,empty).getJSONArray("fills").length()==1,"empty quote retains today's markers");
            require(PhoneSignalStore.restore(prefs,daily).getJSONArray("fills").length()==2,"daily retains older trading dates");
            JSONObject other=PhoneRealtimeCandles.copy(daily).put("symbol","sz000063");
            require(!PhoneSignalStore.restore(prefs,other).has("fills"),"no markers leak to another stock");
            JSONObject tomorrow=PhoneRealtimeCandles.copy(empty).put("date",date.plusDays(1).toString());
            require(PhoneSignalStore.restore(prefs,tomorrow).getJSONArray("fills").length()==0,"new minute session excludes prior-day markers");
            // This is the previously missing path: initial() rebuilt bars and discarded saved fills.
            test.runOnMainSync(() -> {
                PhoneQuoteSession session=new PhoneQuoteSession((quote,status) -> {
                    require(quote.optJSONArray("fills")!=null && quote.optJSONArray("fills").length()==1,
                            "subscription's initial cached update preserves accepted signals");
                });
                try { session.startDisplay(symbol,"","",1,PhoneSignalStore.restore(prefs,empty)); }
                finally { session.stop(); }
                for(int restart=0;restart<2;restart++) {
                    PhoneDisplayFeed feed=new PhoneDisplayFeed(prefs,(quote,status) -> {
                        require(quote.optJSONArray("fills")!=null && quote.optJSONArray("fills").length()==2,
                                "offline daily feed survives stop and recreation");
                        require(!new PhoneMarketPlot(quote,false,true,true).hasSignals(),"daily hides retained signals");
                    });
                    try { feed.start(symbol,"1d",1,false,true); }
                    finally { feed.stop(); }
                }
                View widget=PhoneWidgetRenderer.render(isolated,0,empty,315,285,"1d")
                        .apply(isolated,new FrameLayout(isolated));
                require(!widget.findViewById(R.id.widget_root).getContentDescription().toString().contains("B/S"),"daily AOD hides B/S");
            });
            ((PhoneRenderInstrumentation)test).record("PERSISTENCE migration/empty update/stock+date isolation/subscription restart/offline daily/AOD PASS",true);
        } finally { prefs.edit().clear().commit(); }
        if (repair) repairCurrent(test,target);
    }

    private static void repairCurrent(Instrumentation test,Context target) throws Exception {
        SharedPreferences prefs=target.getSharedPreferences("phone_dial",Context.MODE_PRIVATE);
        String symbol=prefs.getString("symbol","");
        JSONObject daily=new JSONObject(prefs.getString("daily_snapshot_"+symbol,"{}"));
        String date=daily.optString("date",PhoneRealtimeCandles.now().toLocalDate().toString());
        PhoneMcpClient client=new PhoneMcpClient(prefs.getString("mcp_url",""),PhoneSecretStore.load(prefs));
        JSONArray recovered;
        try {
            recovered=PhoneSignalHistory.read(client.call("get_volatility_bs",PhoneMcpClient.arguments(symbol)
                    .put("trade_date",date).put("limit",1000)),symbol,date);
        } finally { client.cancel(); }
        require(recovered.length()>0,"confirmed server history available for recovery");
        JSONObject accepted=new JSONObject().put("symbol",symbol).put("fills",recovered);
        test.runOnMainSync(() -> {
            PhoneSignalStore.migrate(prefs,symbol);
            PhoneSignalStore.remember(prefs,accepted);
            JSONArray stored = PhoneSignalStore.restore(prefs,daily).optJSONArray("fills");
            require(stored != null && stored.length()>0,"recovered signals stored");
            PhoneMarketWidgetProvider.updateTheme(target);
        });
        ((PhoneRenderInstrumentation)test).record("RECOVERED "+symbol+" "+date+" confirmed="+recovered.length()+"; background polling remains disabled",true);
    }

    private static JSONObject bar(String time) throws Exception {
        return new JSONObject().put("time",time).put("open",10).put("high",12).put("low",8).put("close",10);
    }
    private static JSONObject mark(String time,String side) throws Exception {
        return new JSONObject().put("bar_time",time).put("price",10).put("side",side);
    }
    private static void require(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
}
