package com.kevin.phonekline;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device Canvas and actual Handler boundaries; isolated preferences and clock, no system-time changes. */
final class PhoneAutomaticRegression {
    static void check(Instrumentation test) throws Exception {
        Context target=test.getTargetContext();
        SharedPreferences isolated=target.getSharedPreferences("automatic-regression",Context.MODE_PRIVATE);
        String day=PhoneRealtimeCandles.now().toLocalDate().toString();
        Context context=new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name,int mode) {
                return "phone_dial".equals(name)?isolated:super.getSharedPreferences(name,mode);
            }
        };
        JSONObject minute=fixture("sh603986",day), second=fixture("sz000063",day);
        JSONObject daily=PhoneDailyCandles.history("sh603986","测试",minute.getJSONArray("bars"));
        JSONObject lower=PhoneDailyCandles.history("sz000063","测试",second.getJSONArray("bars"));
        isolated.edit().clear().putBoolean("paused",true).putString("symbol","sh603986")
                .putString("second_symbol","sz000063").putString("snapshot_sh603986",minute.toString())
                .putString("snapshot_sz000063",second.toString()).putString("daily_snapshot_sh603986",daily.toString())
                .putString("daily_snapshot_sz000063",lower.toString()).putString("live_calendar_date",day)
                .putBoolean("live_calendar_open",true).commit();
        try {
            for (String[] boundary:new String[][]{{"09:15","1day","分时"},{"11:30","分时","分时"},
                    {"13:00","分时","分时"},{"15:00","分时","1day"}}) {
                LocalDateTime before=LocalDateTime.parse(day+"T"+boundary[0]).minusNanos(600_000_000);
                long start=SystemClock.elapsedRealtime();
                PhoneSystemDisplay[] display={null};
                try {
                    test.runOnMainSync(() -> {
                        display[0]=new PhoneSystemDisplay(context,true,()->{},
                                ()->before.plusNanos((SystemClock.elapsedRealtime()-start)*1_000_000));
                        display[0].show();
                        expectBoth(display[0],boundary[1]);
                    });
                    SystemClock.sleep(1300);
                    test.runOnMainSync(()->expectBoth(display[0],boundary[2]));
                    progress(test,"LOCK TIMER "+boundary[0]+" "+boundary[1]+" -> "+boundary[2]+" PASS");
                } finally { test.runOnMainSync(()->{if(display[0]!=null)display[0].close();}); }
            }
            test.runOnMainSync(()->{
                try {
                    for(String mode:new String[]{"realtime","1d"}) {
                        View host=PhoneWidgetRenderer.render(context,0,minute,315,285,mode)
                                .apply(context,new FrameLayout(context));
                        ImageView image=host.findViewById(R.id.widget_plot);
                        Bitmap bitmap=((BitmapDrawable)image.getDrawable()).getBitmap();
                        save(target,"regression-aod-"+mode+".png",bitmap);
                        require(hasTdw(bitmap),"AOD TDW bitmap "+mode);
                    }
                } catch(Exception e) {throw new AssertionError(e);}
            });
            progress(test,"AOD daily/realtime TDW bitmaps PASS");
            checkRealSignals(test,target);
        } finally { isolated.edit().clear().commit(); }
    }

    private static void checkRealSignals(Instrumentation test,Context context) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences("phone_dial",Context.MODE_PRIVATE);
        String symbol=prefs.getString("symbol","sh603986");
        String url=prefs.getString("mcp_url","");
        String key=PhoneSecretStore.load(prefs);
        require(!url.isEmpty()&&!key.isEmpty(),"configured MCP credentials");
        PhoneMcpClient client=new PhoneMcpClient(url,key);
        String day=PhoneRealtimeCandles.now().toLocalDate().toString();
        JSONArray expected;
        try {
            JSONObject response=client.call("get_volatility_bs",PhoneMcpClient.arguments(symbol).put("trade_date",day).put("limit",1000));
            expected=PhoneSignalHistory.read(response,symbol,day);
            progress(test,"MCP "+symbol+" confirmed history="+expected.length()+"; notification rows="
                    +client.notifications().getJSONArray("notifications").length());
        } finally { client.cancel(); }
        require(expected.length()>0,"real confirmed B/S history present");
        CountDownLatch done=new CountDownLatch(1);
        PhoneDisplayFeed[] feed={null}; JSONObject[] received={null};
        try {
            test.runOnMainSync(()->{
                feed[0]=new PhoneDisplayFeed(prefs,(quote,status)->{
                    if(quote!=null&&"1d".equals(quote.optString("period"))
                            &&quote.optJSONArray("fills")!=null&&quote.optJSONArray("fills").length()>0) {
                        received[0]=quote;done.countDown();
                    }
                });
                feed[0].start(symbol,"1d",1);
            });
            require(done.await(55,TimeUnit.SECONDS),"real daily display feed delivered B/S within 55s");
            test.runOnMainSync(()->{
                try {
                    JSONObject data=received[0];
                    MarkerCanvas canvas=new MarkerCanvas();
                    new PhoneMarketPlot(data,false,true,true,true).drawWidget(canvas,0,0,650,500);
                    require(!canvas.buy&&!canvas.sell&&canvas.tdw,"real daily Canvas shows TDW and hides B/S");
                    View host=PhoneWidgetRenderer.render(context,0,data,315,285,"1d").apply(context,new FrameLayout(context));
                    require(!host.findViewById(R.id.widget_root).getContentDescription().toString().contains("B/S"),"real daily widget hides B/S");
                    save(context,"regression-aod-real.png",((BitmapDrawable)((ImageView)host.findViewById(R.id.widget_plot)).getDrawable()).getBitmap());
                    PhoneCandleView lock=new PhoneCandleView(context);
                    lock.setUpperPeriod("1d");lock.setMarket(data,symbol);
                    require(!lock.getContentDescription().toString().contains("B/S"),"real daily lock hides B/S");
                    lock.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2340,View.MeasureSpec.EXACTLY));
                    lock.layout(0,0,1080,2340);
                    Bitmap bitmap=Bitmap.createBitmap(1080,2340,Bitmap.Config.ARGB_8888);lock.draw(new Canvas(bitmap));
                    save(context,"regression-lock-real.png",bitmap);
                } catch(Exception e) {throw new AssertionError(e);}
            });
            progress(test,"REAL daily feed retains B/S; AOD/lock daily hide B/S PASS");
        } finally {test.runOnMainSync(()->{if(feed[0]!=null)feed[0].stop();});}
    }

    private static final class MarkerCanvas extends Canvas {
        boolean buy,sell,tdw;
        @Override public void drawText(String text,float x,float y,Paint paint) {
            buy|="B".equals(text);sell|="S".equals(text);tdw|=text.startsWith("TDW");
        }
    }
    private static void expectBoth(PhoneSystemDisplay display,String label) {
        String text=display.view.getContentDescription().toString();
        require(text.contains("下方："),"second stock panel exists");
        String[] parts=text.split("下方：");
        require(parts[0].contains(label)&&parts[1].contains(label),"both lock panels "+label+": "+text);
    }
    private static JSONObject fixture(String symbol,String day) throws Exception {
        JSONArray bars=new JSONArray();
        for(int i=0;i<60;i++) bars.put(new JSONObject().put("time",day+" "+String.format(java.util.Locale.ROOT,"%02d:%02d",9+(31+i)/60,(31+i)%60))
                .put("open",10).put("high",12).put("low",9).put("close",10+Math.sin(i*.2)));
        return new JSONObject().put("symbol",symbol).put("name","测试").put("date",day).put("bars",bars)
                .put("price",10).put("previous",10).put("high",12).put("low",9).put("quote_at",day+" 10:30:00");
    }
    private static boolean hasTdw(Bitmap bitmap) {
        for(int y=bitmap.getHeight()/2;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++) {
            int c=bitmap.getPixel(x,y);
            if(Color.red(c)>60&&Color.blue(c)>60&&Color.red(c)>Color.green(c)*1.5&&Color.blue(c)>Color.green(c)*1.5)return true;
        }
        return false;
    }
    private static void save(Context context,String name,Bitmap bitmap) throws Exception {
        try(java.io.FileOutputStream out=context.openFileOutput(name,Context.MODE_PRIVATE)) {bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    private static void progress(Instrumentation test,String message) {
        ((PhoneRenderInstrumentation)test).record(message,true);
        Bundle data=new Bundle();data.putString("stream",message+"\n");test.sendStatus(0,data);
    }
    private static void require(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
