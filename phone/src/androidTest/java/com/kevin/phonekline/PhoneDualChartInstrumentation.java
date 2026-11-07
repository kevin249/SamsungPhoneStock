package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Check lower mode isolation, both surface types, and removal without changing user preferences. */
final class PhoneDualChartInstrumentation {
    static void check(Context context, SharedPreferences prefs) throws Exception {
        prefs.edit().putString("chart_mode","realtime").apply();
        JSONObject upper=fixture("sh603986",false), lower=fixture("sz000063",false), days=fixture("sz000063",true);
        PhoneCandleView view=new PhoneCandleView(context);
        view.layout(0,0,540,1170);
        view.setTheme(PhoneCandleView.LOCK_BLACK);
        view.setSurface(PhoneCandleView.Surface.LOCK);
        view.setMarket(upper,"sh603986");
        Bitmap single=render(view);
        prefs.edit().putString("second_chart_mode","realtime").apply();
        view.setLowerMarket(lower,"sz000063","测试行情");
        Bitmap realtime=render(view);
        prefs.edit().putString("second_chart_mode","1m").apply();
        view.setLowerMarket(lower,"sz000063","测试行情");
        Bitmap minutes=render(view);
        if (changed(realtime,minutes,260,645)!=0) throw new AssertionError("lower mode changed upper chart");
        if (changed(realtime,minutes,660,1050)<100) throw new AssertionError("realtime/1min identical");
        prefs.edit().putString("second_chart_mode","1d").apply();
        view.setLowerMarket(days,"sz000063","测试行情");
        Bitmap daily=render(view);
        save(context,"dual-realtime",realtime);
        save(context,"dual-1min",minutes);
        save(context,"dual-1day",daily);
        if (changed(minutes,daily,260,645)!=0 || changed(minutes,daily,660,1050)<100)
            throw new AssertionError("daily data not isolated to lower chart");
        if (!view.getContentDescription().toString().contains("1day")) throw new AssertionError("missing lower mode accessibility");
        view.setSurface(PhoneCandleView.Surface.DREAM);
        Bitmap dream=render(view);
        if (changed(daily,dream,260,1050)!=0 || changed(daily,dream,0,250)<100)
            throw new AssertionError("dream must add one clock and retain both charts");
        view.setSurface(PhoneCandleView.Surface.LOCK);
        view.setLowerMarket(null,"","");
        Bitmap removed=render(view);
        if (changed(single,removed,0,1170)!=0) throw new AssertionError("removing second stock did not restore original single chart");
        for (Bitmap bitmap : new Bitmap[]{single,realtime,minutes,daily,dream,removed}) bitmap.recycle();
        checkIndependentPeriods(context,prefs,upper,lower,days);
        prefs.edit().remove("chart_mode").remove("first_chart_mode").remove("second_chart_mode").apply();
    }

    private static void checkIndependentPeriods(Context context,SharedPreferences prefs,JSONObject upper,JSONObject lower,JSONObject days) throws Exception {
        PhoneCandleView view=new PhoneCandleView(context);
        view.layout(0,0,540,1170); view.setTheme(PhoneCandleView.LOCK_BLACK); view.setSurface(PhoneCandleView.Surface.LOCK);
        String[] periods={"realtime","1m","1d"};
        Bitmap[][] frames=new Bitmap[3][3];
        JSONObject upperDays=fixture("sh603986",true);
        for(int a=0;a<3;a++) {
            view.setUpperPeriod(periods[a]);
            view.setMarket(a==2?upperDays:upper,"sh603986");
            for(int b=0;b<3;b++) {
                prefs.edit().putString("second_chart_mode",periods[b]).apply();
                view.setLowerMarket(b==2?days:lower,"sz000063","测试行情");
                frames[a][b]=render(view);
                if(b>0 && changed(frames[a][0],frames[a][b],260,645)!=0)
                    throw new AssertionError("lower period changed upper "+periods[a]);
                if(a>0 && changed(frames[0][b],frames[a][b],660,1050)!=0)
                    throw new AssertionError("upper period changed lower "+periods[b]);
            }
        }
        if(changed(frames[0][0],frames[1][0],260,645)<100 || changed(frames[1][0],frames[2][0],260,645)<100)
            throw new AssertionError("upper period did not change plot");
        if(!new PhoneChartOptions(prefs).intraday) throw new AssertionError("display periods changed AOD mode");
        save(context,"independent-upper-day-lower-realtime",frames[2][0]);
        for(Bitmap[] row:frames) for(Bitmap image:row) image.recycle();
    }

    static JSONObject fixture(String symbol, boolean daily) throws Exception {
        JSONArray rows=new JSONArray();
        LocalDate day=LocalDate.of(2026,10,30);
        for (int i=0;i<100;i++) {
            double close=35+Math.sin(i*.15)*.7+i*.003;
            String stamp=daily ? day.minusDays(140-i).toString()+" 15:00"
                    : LocalDateTime.of(2026,10,30,9,31).plusMinutes(i).toString().replace('T',' ');
            rows.put(new JSONObject().put("time",stamp).put("open",close-.03).put("high",close+.1)
                    .put("low",close-.1).put("close",close));
        }
        if (daily) return PhoneDailyCandles.history(symbol,"中兴通讯",rows);
        return new JSONObject().put("symbol",symbol).put("date",day.toString()).put("bars",rows)
                .put("name",symbol.equals("sh603986")?"兆易创新":"中兴通讯").put("price",35.82).put("previous",35.26)
                .put("high",36.2).put("low",34.0).put("quote_at","2026-10-30 11:10:00");
    }

    private static void save(Context context,String name,Bitmap image) throws Exception {
        java.io.File dir=context.getExternalFilesDir("dual-check");
        if(dir==null) return;
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(dir,name+".png"))) {
            image.compress(Bitmap.CompressFormat.PNG,100,out);
        }
    }

    private static Bitmap render(PhoneCandleView view) {
        Bitmap image=Bitmap.createBitmap(540,1170,Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image)); return image;
    }

    private static int changed(Bitmap a, Bitmap b, int top, int bottom) {
        int changes=0;
        for (int y=top;y<bottom;y++) for(int x=0;x<540;x++) if(a.getPixel(x,y)!=b.getPixel(x,y)) changes++;
        return changes;
    }
}
