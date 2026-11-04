package com.kevin.watchmcp;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.RemoteException;
import androidx.wear.watchface.complications.data.*;
import androidx.wear.watchface.complications.datasource.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

public abstract class DialProvider extends ComplicationDataSourceService {
    public static final class Fold extends DialProvider { }
    public static final class Dune extends DialProvider { }
    public static final class Ridge extends DialProvider { }
    public static final class Salt extends DialProvider { }
    public static final class Line extends DialProvider { @Override DialChart.Layer layer() { return DialChart.Layer.LINE; } }
    public static final class Candles extends DialProvider { @Override DialChart.Layer layer() { return DialChart.Layer.CANDLES; } }
    public static final class Book extends DialProvider { @Override DialChart.Layer layer() { return DialChart.Layer.BOOK; } }
    public static final class Signals extends DialProvider { @Override DialChart.Layer layer() { return DialChart.Layer.SIGNALS; } }
    private static final Class<?>[] PROVIDERS = {Fold.class,Dune.class,Ridge.class,Salt.class,Line.class,Candles.class,Book.class,Signals.class};
    DialChart.Layer layer() { return DialChart.Layer.BASE; }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("watch",MODE_PRIVATE); }
    private static String key(Class<?> cls) { return "dial_slots_" + cls.getSimpleName(); }

    private void record(int id, boolean add) {
        SharedPreferences p = prefs(this);
        String key = key(getClass());
        Set<String> ids = new HashSet<>(p.getStringSet(key,java.util.Collections.emptySet()));
        boolean changed = add ? ids.add(String.valueOf(id)) : ids.remove(String.valueOf(id));
        if (changed) p.edit().putStringSet(key,ids).apply();
    }
    @Override public void onComplicationActivated(int id, ComplicationType type) { record(id,true); }
    @Override public void onComplicationDeactivated(int id) { record(id,false); }

    static boolean active(Context c) {
        for (Class<?> cls : PROVIDERS) if (!prefs(c).getStringSet(key(cls),java.util.Collections.emptySet()).isEmpty()) return true;
        return false;
    }
    static void redraw(Context c) {
        for (Class<?> cls : PROVIDERS) {
            if (!prefs(c).getStringSet(key(cls),java.util.Collections.emptySet()).isEmpty())
                ComplicationDataSourceUpdateRequester.create(c,new ComponentName(c,cls)).requestUpdateAll();
        }
    }
    private static PlainComplicationText plain(String value) { return new PlainComplicationText.Builder(value).build(); }
    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        record(request.getComplicationInstanceId(),true);
        try { listener.onComplicationData(build(false)); }
        catch (RemoteException error) { android.util.Log.w("WatchDial","系统表盘连接已断开"); }
        DialMarket.refresh(this);
    }
    @Override public ComplicationData getPreviewData(ComplicationType type) {
        return type == (layer()==DialChart.Layer.BASE ? ComplicationType.LONG_TEXT : ComplicationType.SMALL_IMAGE) ? build(true) : null;
    }
    private ComplicationData build(boolean preview) {
        SharedPreferences p=prefs(this);
        JSONObject market=preview ? previewMarket() : DialMarket.saved(p);
        boolean needsHistory = layer()==DialChart.Layer.BASE || layer()==DialChart.Layer.SIGNALS;
        JSONObject history=preview ? previewSignals() : needsHistory ? new McpHistory(p).snapshot() : new JSONObject();
        JSONArray rows=history.optJSONArray("notifications");
        long seen=p.getLong("seen",-1);
        int unread=0;
        if (seen>=0 && rows!=null) for (int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i);
            if (row!=null && row.optLong("id")>seen) unread++;
        }
        String quote=market.has("price") ? market.optString("name")+"　"+DialChart.number(market.optDouble("price")) : "等待行情";
        String mail=unread==0 ? "暂无未读消息" : unread+" 条未读消息";
        String theme = layer()==DialChart.Layer.BASE ? getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) : "ridge";
        Icon chart=Icon.createWithBitmap(new DialChart(theme,layer()).render(market,history,p,unread));
        SmallImage image = new SmallImage.Builder(chart,SmallImageType.PHOTO).build();
        if (layer()!=DialChart.Layer.BASE) {
            Intent gesture = new Intent(this,DialChartTap.class).addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            PendingIntent tap=PendingIntent.getBroadcast(this,17,gesture,PendingIntent.FLAG_IMMUTABLE);
            return new SmallImageComplicationData.Builder(image,plain("行情图，双击切换自选股")).setTapAction(tap).build();
        }
        Intent intent=new Intent(this,MainActivity.class).putExtra("show_notifications",true);
        return new LongTextComplicationData.Builder(plain(quote),plain(quote+"，"+mail))
                .setSmallImage(image)
                .setTitle(plain(mail)).setTapAction(PendingIntent.getActivity(this,8,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build();
    }

    // 系统编辑器与收藏缩略图只使用明确标为示例的预览，不读取账户或消息。
    private static JSONObject previewMarket() {
        try {
            JSONArray bars = new JSONArray();
            double last=20, low=last, high=last;
            for (int m=1;m<=240;m++) {
                double price=20 + m*.003 + .17*Math.sin(m*.09) + .04*Math.sin(m*.71);
                double h=Math.max(price,last)+.025, l=Math.min(price,last)-.025;
                bars.put(new JSONArray().put(m).put(last).put(h).put(l).put(price));
                high=Math.max(high,h); low=Math.min(low,l); last=price;
            }
            JSONArray levels = new JSONArray();
            for (int i=1;i<=10;i++) for (int side:new int[]{-1,1}) {
                levels.put(new JSONObject().put("side",side<0?"bid":"ask").put("price",last+side*i*.016)
                        .put("volume",4+(i*13)%31));
            }
            return new JSONObject().put("bars",bars).put("book",DialBook.profile(levels,low,high)).put("price",last)
                    .put("previous",20).put("low",low).put("high",high).put("time","15:00").put("date","2000-01-04")
                    .put("display_date","示例").put("name","示例行情").put("symbol","preview");
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    private static JSONObject previewSignals() {
        try {
            JSONArray rows=new JSONArray();
            for (int i=0;i<2;i++) rows.put(new JSONObject().put("signal",new JSONObject().put("symbol","preview")
                    .put("buy",i==0).put("price",i==0?20.07:20.55)
                    .put("time",i==0?"2000-01-04T10:30":"2000-01-04T14:00")));
            return new JSONObject().put("notifications",rows);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
}
