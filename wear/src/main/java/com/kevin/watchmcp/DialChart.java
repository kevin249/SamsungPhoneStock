package com.kevin.watchmcp;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** 与表盘的 360 单位坐标一致，在 Watch7 的 480 像素屏上直接栅格化。 */
final class DialChart {
    private final Bitmap bitmap;
    private final Canvas canvas;
    enum Layer { BASE, LINE, CANDLES, BOOK, SIGNALS }
    private final Layer layer;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int ink, muted, buy, sell;
    private final boolean fold;
    private double floor, ceiling;
    private static final float LEFT = 44, RIGHT = 316, TOP = 187, BASE = 275;

    DialChart(String theme, Layer layer) {
        this.layer = layer;
        boolean base = layer == Layer.BASE;
        bitmap = Bitmap.createBitmap(base ? 480 : 400,base ? 480 : 140,Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        boolean dark = "fold".equals(theme) || "ridge".equals(theme);
        fold = "fold".equals(theme);
        ink = Color.parseColor(fold ? "#F7F5EA" : "ridge".equals(theme) ? "#EDF4DF" : "salt".equals(theme) ? "#454453" : "#483830");
        muted = Color.parseColor(dark ? "#C4D3CF" : "#675C61");
        buy = Color.parseColor(dark ? "#FF938B" : "#B72035");
        sell = Color.parseColor(dark ? "#72E1A1" : "#096A3D");
        canvas.scale(480f/360,480f/360);
        if (!base) canvas.translate(-30,-177);
    }

    static String number(double p) { return p > 0 ? String.format(Locale.ROOT,"%.2f",p) : "—"; }
    private float x(int minute) { return LEFT + (RIGHT-LEFT)*minute/240f; }
    private float y(double price) { return TOP+6+(float)((ceiling-price)/(ceiling-floor))*(BASE-TOP-13); }

    private void text(String value,float x,float baseline,float size,int color,Paint.Align align) {
        paint.setColor(color); paint.setStyle(Paint.Style.FILL); paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL)); paint.setTextAlign(align);
        canvas.drawText(value,x,baseline,paint);
    }
    private void line(float x1,float y1,float x2,float y2,int color,float width) {
        paint.setColor(color); paint.setStrokeWidth(width); canvas.drawLine(x1,y1,x2,y2,paint);
    }

    Bitmap render(JSONObject data, JSONObject history, SharedPreferences prefs, int unread) {
        String count = unread > 99 ? "99+" : String.valueOf(unread);
        if (fold && layer == Layer.BASE) {
            text(count,275,80,20,0xff243a3d,Paint.Align.CENTER);
            text("未读",275,104,12,0xff475851,Paint.Align.CENTER);
        }
        JSONArray bars = data.optJSONArray("bars");
        if (bars == null || bars.length() == 0) {
            if (layer != Layer.BASE) return bitmap;
            text(DialMarket.symbol(prefs).isEmpty() ? "请添加自选股票" : "等待行情",180,207,24,ink,Paint.Align.CENTER);
            return bitmap;
        }
        if (layer != Layer.BASE) {
            chartLayer(data,history,bars);
            return bitmap;
        }
        String name = data.optString("name");
        if (name.length() > 7) name = name.substring(0,6) + "…";
        text(name,37,169,name.length()>5 ? 15 : 18,ink,Paint.Align.LEFT);
        text(number(data.optDouble("price")),243,169,26,ink,Paint.Align.RIGHT);
        text(number(data.optDouble("high")),318,160,14,ink,Paint.Align.RIGHT);
        text(number(data.optDouble("low")),318,176,14,ink,Paint.Align.RIGHT);
        for (int m=0;m<=240;m+=30) {
            boolean half = m < 120 ? m%60==0 : m==120 || m%60==30;
            paint.setPathEffect(half ? new DashPathEffect(new float[]{2.5f,2.5f},0) : null);
            line(x(m),TOP,x(m),BASE,0x887f8586,.8f);
        }
        paint.setPathEffect(null);
        line(LEFT,BASE,RIGHT,BASE,0x887f8586,.8f);
        text("9:30",LEFT,BASE+12,12.5f,muted,Paint.Align.LEFT);
        text("11:30",180,BASE+12,12.5f,muted,Paint.Align.CENTER);
        text("15:00",RIGHT,BASE+12,12.5f,muted,Paint.Align.RIGHT);
        double prev=data.optDouble("previous"), price=data.optDouble("price");
        if (prev>0) text(String.format(Locale.ROOT,"%+.2f%%",(price/prev-1)*100),112,313,16,price>=prev?buy:sell,Paint.Align.CENTER);
        text(fold ? data.optString("time")+" 更新" : count+" 未读",229,313,15,muted,Paint.Align.CENTER);
        text(data.optString("display_date", data.optString("date"))+"　"+data.optString("time"),180,333,11,muted,Paint.Align.CENTER);
        return bitmap;
    }

    private void chartLayer(JSONObject data, JSONObject history, JSONArray bars) {
        floor=data.optDouble("low"); ceiling=data.optDouble("high");
        JSONObject book = data.optJSONObject("book");
        // 纵轴始终以 K 线为准。远端挂单不能把行情压成一条直线。
        double pad = Math.max(0.01,(ceiling-floor)*0.035);
        ceiling+=pad; floor-=pad;
        if (layer == Layer.BOOK) { if (book != null) depth(book); return; }
        if (layer == Layer.SIGNALS) { marks(data,history,bars); return; }
        boolean candles = layer == Layer.CANDLES;
        Path trace = new Path();
        int last=-1;
        float endX=0, endY=0;
        for (int i=0;i<bars.length();i++) {
            JSONArray b = bars.optJSONArray(i);
            if (b==null) continue;
            int m=b.optInt(0); float px=x(m);
            double o=b.optDouble(1), c=b.optDouble(4);
            if (candles) {
                int color=c>=o ? buy : sell;
                line(px,y(b.optDouble(2)),px,y(b.optDouble(3)),color,.55f);
                line(px,y(o),px,Math.abs(y(o)-y(c))<1 ? y(o)+1 : y(c),color,.98f);
            } else {
                if (i==0 && m==1) trace.moveTo(LEFT,y(o));
                else if (last<0 || (last<=120 && m>120)) trace.moveTo(px,y(c));
                trace.lineTo(px,y(c)); endX=px; endY=y(c);
            }
            last=m;
        }
        if (!candles && last>=0) {
            int tick=data.optInt("tick",-1);
            if (tick>=last) { endX=x(tick); endY=y(data.optDouble("price")); trace.lineTo(endX,endY); }
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.75f); paint.setColor(Color.WHITE);
            paint.setStrokeJoin(Paint.Join.ROUND); canvas.drawPath(trace,paint); paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(endX,endY,2.1f,paint);
        }
    }

    private void depth(JSONObject book) {
        JSONArray[] sides = {book.optJSONArray("bid"),book.optJSONArray("ask")};
        if (sides[0] == null || sides[1] == null) return;
        double low = book.optDouble("low"), high = book.optDouble("high"), max = 0;
        for (JSONArray side : sides) for (int i=0;i<side.length();i++) {
            double price=low + (high-low)*i/Math.max(1,side.length()-1);
            if (price>=floor && price<=ceiling) max=Math.max(max,side.optDouble(i,0));
        }
        if (max<=0) return;
        canvas.save();
        canvas.clipRect(LEFT,TOP,RIGHT,BASE);
        for (int s=0;s<sides.length;s++) for (int i=0;i<sides[s].length();i++) {
            double volume=sides[s].optDouble(i,0);
            if (volume<=0) continue;
            double price=low + (high-low)*i/Math.max(1,sides[s].length()-1);
            if (price<floor || price>ceiling) continue;
            float py=y(price)+(s==0 ? .45f : -.45f);
            float width=(float)Math.sqrt(volume/max)*52;
            line(RIGHT-Math.max(width,2),py,RIGHT,py,s==0 ? buy : sell,.95f);
        }
        canvas.restore();
    }

    private void marks(JSONObject data, JSONObject history, JSONArray bars) {
        JSONArray messages=history.optJSONArray("notifications");
        if (messages==null) return;
        for (int i=0;i<messages.length();i++) {
            JSONObject row=messages.optJSONObject(i);
            JSONObject s=row==null ? null : row.optJSONObject("signal");
            if (s==null || !data.optString("symbol").equals(s.optString("symbol"))) continue;
            String stamp=DialMarket.localStamp(s.optString("time"));
            if (stamp.length()<16 || !stamp.startsWith(data.optString("date"))) continue;
            int m=DialMarket.minute(stamp.substring(11));
            if (m<0 || m>Math.max(data.optInt("tick",-1),bars.optJSONArray(bars.length()-1).optInt(0))) continue;
            boolean buySide=s.optBoolean("buy");
            double at=s.optDouble("price");
            float tip=Math.max(TOP+5,Math.min(BASE-5,y(at)+(buySide?2:-2)));
            float edge=tip+(buySide?5:-5), px=x(m);
            Path mark=new Path(); mark.moveTo(px,tip); mark.lineTo(px-3.7f,edge); mark.lineTo(px+3.7f,edge); mark.close();
            paint.setColor(buySide?buy:sell); canvas.drawPath(mark,paint);
        }
    }
}
