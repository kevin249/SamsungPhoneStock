package com.kevin.watchmcp;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** 使用独立测试存储，实际调用与系统表盘相同的合并和绘图代码。 */
final class DialRenderProbe {
    static void run(Context context) throws Exception {
        SharedPreferences prefs=context.getSharedPreferences("dial-render-test",Context.MODE_PRIVATE);
        prefs.edit().clear().putBoolean("dial_depth",false).commit();
        try {
            String date="2026-11-03", code="sz000001";
            JSONObject q=new JSONObject().put("symbol",code).put("date",date).put("name","测试")
                    .put("price",10).put("high",11).put("low",9).put("previous",10).put("time","09:30");
            JSONArray bars=new JSONArray();
            q.put("bars",bars);
            if (DialMarket.minute("09:29")!=-1 || DialMarket.minute("12:00")!=-1 || DialMarket.minute("15:01")!=-1) throw new AssertionError("session bounds");
            int frames=0;
            for (int t=570;t<=900;t++) {
                String clock=String.format(Locale.ROOT,"%02d:%02d",t/60,t%60);
                int m=DialMarket.minute(clock);
                if (m<0) continue;
                if (m!=(t<=690?t-570:t-660)) throw new AssertionError("axis at "+clock);
                bars.put(new JSONArray().put(m).put(10).put(10.1).put(9.9).put(10));
                q.put("time",clock);
                if (m%30==0) {
                    new DialChart("ridge",DialChart.Layer.LINE).render(q,new JSONObject(),prefs,0).recycle();
                    frames++;
                }
            }
            if (frames!=10 || bars.length()!=242) throw new AssertionError("replay endpoints");
            JSONArray signals=new JSONArray();
            for (int i=0;i<3;i++) {
                JSONObject payload=new JSONObject().put("code",i==2?"600519":"000001")
                        .put("direction",i==0?"buy_first":"sell_first").put("price",10)
                        .put("bar_at",date+"T"+(i==0?"10:30":"14:00")+":00+08:00");
                signals.put(new JSONObject().put("event_id","dial-probe-"+i).put("sequence",i)
                        .put("event_type","trading.holding_t_signal").put("payload",payload));
            }
            McpHistory history=new McpHistory(prefs);
            history.merge(new JSONObject().put("notifications",signals));
            JSONObject messages=history.snapshot();
            Bitmap marks=new DialChart("ridge",DialChart.Layer.SIGNALS).render(q,messages,prefs,0);
            Bitmap blank=new DialChart("ridge",DialChart.Layer.SIGNALS).render(q,new JSONObject(),prefs,0);
            if (changed(marks,blank,102,10,16,123)<5 || changed(marks,blank,282,10,16,123)<5) throw new AssertionError("missing B/S");
            marks.recycle(); blank.recycle();
            Bitmap line=new DialChart("ridge",DialChart.Layer.LINE).render(q,messages,prefs,0);
            Bitmap candles=new DialChart("ridge",DialChart.Layer.CANDLES).render(q,messages,prefs,0);
            if (changed(line,candles,0,0,400,140)<100) throw new AssertionError("chart mode");
            line.recycle(); candles.recycle();
            JSONArray levels=new JSONArray();
            for (int i=1;i<=1000;i++) {
                levels.put(new JSONObject().put("side","bid").put("price",10-i*.0005).put("volume",i));
                levels.put(new JSONObject().put("side","ask").put("price",10+i*.0005).put("volume",2*i));
            }
            JSONObject book=DialBook.profile(levels,q.getDouble("low"),q.getDouble("high"));
            if (book.getInt("bid_count")!=1000 || book.getInt("ask_count")!=1000) throw new AssertionError("depth coverage");
            if (sum(book.getJSONArray("bid"))!=500500 || sum(book.getJSONArray("ask"))!=1001000) throw new AssertionError("lost order volume");
            levels.put(new JSONObject().put("side","bid").put("price",1).put("volume",10000000));
            levels.put(new JSONObject().put("side","ask").put("price",1000).put("volume",10000000));
            JSONObject clipped=DialBook.profile(levels,9,11);
            if (clipped.getDouble("low")!=9 || clipped.getDouble("high")!=11
                    || clipped.getInt("bid_count")!=1001 || clipped.getInt("ask_count")!=1001
                    || sum(clipped.getJSONArray("bid"))!=500500 || sum(clipped.getJSONArray("ask"))!=1001000)
                throw new AssertionError("distant orders changed K-line range or visible volumes");
            for (DialChart.Layer layer:new DialChart.Layer[]{DialChart.Layer.LINE,DialChart.Layer.CANDLES,DialChart.Layer.SIGNALS}) {
                q.remove("book");
                Bitmap original=new DialChart("ridge",layer).render(q,messages,prefs,0);
                q.put("book",new JSONObject(book.toString()).put("low",1).put("high",1000));
                Bitmap extreme=new DialChart("ridge",layer).render(q,messages,prefs,0);
                if (changed(original,extreme,0,0,400,140)!=0) throw new AssertionError("book flattened "+layer);
                original.recycle(); extreme.recycle();
            }
            q.put("book",book);
            Bitmap with=new DialChart("ridge",DialChart.Layer.BOOK).render(q,new JSONObject(),prefs,0);
            q.remove("book");
            Bitmap without=new DialChart("ridge",DialChart.Layer.BOOK).render(q,new JSONObject(),prefs,0);
            if (changed(with,without,315,10,68,123)<5) throw new AssertionError("missing order book");
            with.recycle(); without.recycle();
            checkLiveCandle();
        } finally { prefs.edit().clear().commit(); }
    }

    private static void checkLiveCandle() throws Exception {
        JSONObject data=new JSONObject().put("symbol","sz000001").put("date","2026-11-04")
                .put("low",9).put("high",11).put("price",10)
                .put("bars",new JSONArray().put(new JSONArray().put(1).put(10).put(10).put(10).put(10)));
        JSONObject quote=new JSONObject().put("datetime","2026-11-04T09:30:20+08:00").put("last_price",10.5);
        DialMarket.applySnapshot(data,quote);
        quote.put("datetime","2026-11-04T09:30:21+08:00").put("last_price",9.5);
        DialMarket.applySnapshot(data,quote);
        JSONArray first=data.getJSONArray("bars").getJSONArray(0);
        if (first.getDouble(1)!=10 || first.getDouble(2)!=10.5 || first.getDouble(3)!=9.5 || first.getDouble(4)!=9.5)
            throw new AssertionError("per-second quote did not update candle OHLC");
        String accepted=data.toString();
        for (String stamp:new String[]{"2026-11-03T09:30:22+08:00","2026-11-04T09:30:19+08:00"}) {
            quote.put("datetime",stamp).put("last_price",999);
            DialMarket.applySnapshot(data,quote);
            if (!accepted.equals(data.toString())) throw new AssertionError("stale quote changed chart");
        }
        quote.put("datetime","2026-11-04T09:31:02+08:00").put("last_price",10.2).put("symbol","600519.SH");
        DialMarket.applySnapshot(data,quote);
        if (!accepted.equals(data.toString())) throw new AssertionError("other stock changed chart");
        quote.put("symbol","000001.SZ");
        DialMarket.applySnapshot(data,quote);
        if (data.getJSONArray("bars").length()!=2 || data.getJSONArray("bars").getJSONArray(1).getInt(0)!=2)
            throw new AssertionError("new minute did not append candle");
        quote.put("datetime","2026-11-04T12:00:00+08:00");
        DialMarket.applySnapshot(data,quote);
        if (data.getJSONArray("bars").length()!=2) throw new AssertionError("lunch appended candle");
        quote.put("datetime","2026-11-04T13:00:01+08:00");
        DialMarket.applySnapshot(data,quote);
        if (data.getJSONArray("bars").getJSONArray(2).getInt(0)!=121) throw new AssertionError("afternoon boundary");
    }

    private static double sum(JSONArray bins) throws Exception {
        double total=0;
        for (int i=0;i<bins.length();i++) total+=bins.getDouble(i);
        return total;
    }

    private static int changed(Bitmap a,Bitmap b,int x,int y,int w,int h) {
        int n=0;
        for (int j=y;j<y+h;j++) for (int i=x;i<x+w;i++) if (a.getPixel(i,j)!=b.getPixel(i,j)) n++;
        return n;
    }
}
