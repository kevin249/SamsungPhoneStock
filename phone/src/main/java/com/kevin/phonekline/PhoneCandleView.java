package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared native canvas for the foreground display, dream and live wallpaper. */
final class PhoneCandleView extends View {
    enum Surface { LOCK, DREAM, AMBIENT }
    static final int LOCK_BLACK = 114, LOCK_GRAPHITE = 115;
    private static final float W = 1080, H = 2340;
    private static final Typeface SANS = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface HEAVY = Typeface.create("sans-serif-black", Typeface.BOLD);
    private static final Typeface SERIF = Typeface.create("serif", Typeface.NORMAL);
    private static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M 月 d 日", Locale.CHINA);
    private static final DateTimeFormatter WEEK = DateTimeFormatter.ofPattern("EEEE", Locale.CHINA);
    private static final int RED = 0xffdf978c, GREEN = 0xff83bca1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF screen = new RectF(0, 0, W, H);
    private final Path clock = new Path();
    private final BitmapShader dots;
    private long shownMinute = Long.MIN_VALUE, clockOffset;
    private String zone = "", hhmm = "", date = "", week = "", symbol = "", name = "待选股票", price = "—";
    private String change = "—", quoteAt = "", feedStatus = "";
    private String marketDescription = "";
    private int changeColor = Color.GRAY;
    private int theme = 104, rate = 5;
    private Surface surface = Surface.LOCK;
    private boolean paused;
    private final SharedPreferences preferences;
    private PhoneChartOptions chartOptions;
    private JSONObject marketSnapshot;
    private String selectedSymbol = "", overlayStatus;
    private Bitmap art;
    private final Runnable minuteTick = new Runnable() {
        @Override public void run() {
            if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
            invalidate();
            postDelayed(this, 60_070 - Math.floorMod(System.currentTimeMillis() + clockOffset, 60_000L));
        }
    };
    private PhoneMarketPlot plot;

    PhoneCandleView(Context context) {
        super(context);
        preferences = context.getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        chartOptions = new PhoneChartOptions(preferences);
        plot = chartOptions.build(null);
        overlayStatus = chartOptions.overlays(plot);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        Bitmap tile = Bitmap.createBitmap(9, 9, Bitmap.Config.ARGB_8888);
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setColor(0xffb6edd9);
        new Canvas(tile).drawCircle(4.5f, 4.5f, 3.25f, glow);
        dots = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    void setTheme(int id) {
        int target;
        switch (id) {
            case 95: case 96: case 100: case 101: case 102: case 104: case 108:
            case LOCK_BLACK: case LOCK_GRAPHITE: target = id; break;
            default: target = 104;
        }
        if (target == theme) return;
        theme = target;
        art = null;
        shownMinute = Long.MIN_VALUE;
        invalidate();
    }

    void setSurface(Surface target) {
        if (surface == target) return;
        surface = target;
        shownMinute = Long.MIN_VALUE;
        invalidate();
    }

    private int artResource() {
        switch (theme) {
            case 95: return R.drawable.lock_art_95;
            case 96: return R.drawable.lock_art_96;
            case 100: return R.drawable.lock_art_100;
            case 101: return R.drawable.lock_art_101;
            case 102: return R.drawable.lock_art_102;
            default: return 0;
        }
    }

    void setRefreshState(int rate, boolean paused) {
        this.rate = Math.max(1, rate);
        this.paused = paused;
        invalidate();
    }

    void setFeedStatus(String status) {
        String next = status == null ? "" : status;
        if (feedStatus.equals(next)) return;
        feedStatus = next;
        updateDescription();
        invalidate();
    }

    void setMarket(JSONObject snapshot, String selectedSymbol) {
        String selected = selectedSymbol == null ? "" : selectedSymbol;
        if (snapshot != null && !selected.equals(snapshot.optString("symbol"))) snapshot = null;
        this.selectedSymbol = selected;
        marketSnapshot = snapshot;
        symbol = selected.matches("(?i)(sh|sz|bj)[0-9]{6}") ? selected.substring(2) : selected;
        plot = chartOptions.build(snapshot);
        overlayStatus = chartOptions.overlays(plot);
        name = snapshot == null ? "" : snapshot.optString("name");
        if (name.isEmpty() || "null".equals(name)) name = symbol.isEmpty() ? "待选股票" : "股票行情";
        double last = positive(snapshot, "price");
        if (!Double.isFinite(last)) last = plot.lastClose();
        price = PhoneMarketPlot.format(last);
        double previous = positive(snapshot, "previous");
        double percent = (last / previous - 1) * 100;
        change = Double.isFinite(percent) ? String.format(Locale.ROOT, "%+.2f%%", percent) : "—";
        changeColor = !Double.isFinite(percent) || percent == 0 ? 0xffb8b8b1 : percent > 0 ? RED : GREEN;
        quoteAt = snapshot == null ? "" : snapshot.optString("quote_at");
        if (quoteAt.length() >= 16) quoteAt = quoteAt.substring(5, 16).replace('T', ' ');
        marketDescription = name + " " + symbol + "，现价 " + price + "，涨跌 " + change
                + "，当日最高价 " + PhoneMarketPlot.format(positive(snapshot, "high"))
                + "，当日最低价 " + PhoneMarketPlot.format(positive(snapshot, "low"))
                + "，" + chartOptions.legend(plot) + "，" + overlayStatus;
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        setContentDescription(feedStatus.isEmpty() ? marketDescription : marketDescription + "，" + feedStatus);
    }

    void refreshChartOptions() {
        PhoneChartOptions next = new PhoneChartOptions(preferences);
        if (chartOptions.matches(next)) return;
        chartOptions = next;
        setMarket(marketSnapshot, selectedSymbol);
    }

    private static double positive(JSONObject row, String key) {
        double v = row == null ? Double.NaN : row.optDouble(key);
        return Double.isFinite(v) && v > 0 ? v : Double.NaN;
    }

    // Kept for the older chart-only caller.
    void setBars(JSONArray bars) {
        try { setMarket(new JSONObject().put("symbol", "").put("bars", bars), ""); }
        catch (JSONException invalid) { setMarket(null, ""); }
    }

    void setFrameTime(long millis) {
        clockOffset = millis > 0 ? millis - System.currentTimeMillis() : 0;
        invalidate();
    }

    private void refreshTime(long now) {
        ZoneId zoneId = ZoneId.systemDefault();
        long minute = Math.floorDiv(now, 60_000);
        if (minute == shownMinute && zone.equals(zoneId.getId())) return;
        shownMinute = minute;
        zone = zoneId.getId();
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId);
        date = DATE.format(t);
        week = WEEK.format(t);
        hhmm = HM.format(t);
        if (surface != Surface.AMBIENT) return;
        Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        brush.setTypeface(HEAVY); brush.setTextSize(460);
        clock.reset(); brush.getTextPath(hhmm, 0, hhmm.length(), 0, 0, clock);
        RectF source = new RectF();
        clock.computeBounds(source, true);
        Matrix transform = new Matrix();
        RectF box = theme == 108 ? new RectF(71,353,1009,610) : new RectF(65,452,1015,693);
        transform.setRectToRect(source, box, Matrix.ScaleToFit.FILL);
        clock.transform(transform);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        c.drawColor(theme == LOCK_GRAPHITE ? 0xff15181a : Color.BLACK);
        if (getWidth() < 1 || getHeight() < 1) return;
        long now = System.currentTimeMillis() + clockOffset;
        refreshTime(now);
        int frame = c.save();
        float width = Math.min(getWidth(), getHeight() * .67f);
        c.translate((getWidth() - width) / 2, 0);
        c.scale(width / W, getHeight() / H);
        int id = artResource();
        if (id != 0) {
            if (art == null) art = BitmapFactory.decodeResource(getResources(), id);
            paint.setColor(Color.WHITE);
            if (art != null) c.drawBitmap(art, null, screen, paint);
        } else if (surface == Surface.AMBIENT) {
            int dx = (int)Math.floorMod(shownMinute, 5L) - 2;
            int dy = (int)Math.floorMod(shownMinute / 5, 5L) - 2;
            c.translate(dx * 2, dy * 2);
        }
        if (surface == Surface.AMBIENT) drawClock(c);
        if (surface == Surface.DREAM)
            label(c,hhmm,W/2,173,145,Color.WHITE,SANS,Paint.Align.CENTER,600);
        switch (theme) {
            case 95: bookshop(c); break;
            case 96: convenience(c); break;
            case 100: bridge(c); break;
            case 101: noodles(c); break;
            case 102: cable(c); break;
            case LOCK_BLACK: case LOCK_GRAPHITE: marketBoard(c); break;
            default: ambient(c);
        }
        c.restoreToCount(frame);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(minuteTick);
        post(minuteTick);
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (minuteTick == null) return;
        removeCallbacks(minuteTick);
        if (visibility == VISIBLE && isAttachedToWindow()) post(minuteTick);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(minuteTick);
        super.onDetachedFromWindow();
    }

    private void drawClock(Canvas c) {
        paint.setColor(0xffc5c6c1);
        if (theme == 108) paint.setShader(dots);
        c.drawPath(clock, paint);
        paint.setShader(null);
    }

    private void label(Canvas c, String value, float x, float top, float size, int ink,
                       Typeface type, Paint.Align align, float maxWidth) {
        paint.setColor(ink); paint.setTypeface(type); paint.setTextAlign(align); paint.setTextSize(size);
        float measured = paint.measureText(value);
        if (measured > maxWidth) paint.setTextSize(size * maxWidth / measured);
        c.drawText(value, x, top - paint.ascent(), paint);
    }
    private void left(Canvas c, String t, float x, float y, float size, int ink) {
        label(c,t,x,y,size,ink,SANS,Paint.Align.LEFT, W-x-24);
    }
    private void right(Canvas c, String t, float x, float y, float size, int ink) {
        label(c,t,x,y,size,ink,SANS,Paint.Align.RIGHT,x-24);
    }
    private void center(Canvas c, String t, float y, float size, int ink) {
        label(c,t,W/2,y,size,ink,SANS,Paint.Align.CENTER,W-90);
    }
    private void stock(Canvas c, float x, float y, float size, int ink, Typeface type, float width) {
        label(c,name,x,y,size,ink,type,Paint.Align.LEFT,width);
    }
    private void pair(Canvas c,float x,float y,float r,float priceY,int ink,int dim) {
        stock(c,x,y,54,ink,HEAVY,(r-x)*.54f);
        left(c,symbol,x,y+83,32,dim);
        label(c,price,r,priceY,89,ink,SANS,Paint.Align.RIGHT,(r-x)*.4f);
        right(c,change,r,priceY+114,39,changeColor);
    }
    private void chart(Canvas c,int left,int header,int right,int top,int bottom,int dim,int grid) {
        if (theme != LOCK_BLACK) {
            left(c,chartOptions.legend(plot),left,header,32,dim);
            right(c,overlayStatus,right,header,30,dim);
        }
        plot.draw(c,left,top,right,bottom,RED,GREEN,dim,grid);
    }
    private void footer(Canvas c,int y,int color) {
        footer(c,66,1013,y,color);
    }
    private void footer(Canvas c,int x,int end,int y,int color) {
        String state = theme == LOCK_BLACK ? overlayStatus
                : paused ? "已暂停" : PhoneChartOptions.intervalLabel(rate);
        label(c,state,end,y,30,color,SANS,Paint.Align.RIGHT,(end-x)*.55f);
        float available = Math.max(1f, end-x-paint.measureText(state)-26);
        String info = feedStatus.isEmpty() ? quoteAt : feedStatus;
        label(c,info,x,y,27,color,SANS,Paint.Align.LEFT,available);
    }

    private void ambient(Canvas c) {
        boolean dotted = theme == 108;
        int main = dotted ? 0xffdce5df : 0xffc5c6c1, dim = dotted ? 0xffa9bfb6 : 0xffa5aaa5;
        center(c,date + "    " + week,dotted ? 226 : 307,43,dim);
        int line = dotted ? 735 : 810;
        pair(c,84,line,995,line-16,main,dim);
        int start = dotted ? 1016 : 1090;
        chart(c,81,start-48,999,start,start+345,dim,0xff2d3732);
        footer(c,start+405,dim);
    }
    private void marketBoard(Canvas c) {
        boolean graphite = theme == LOCK_GRAPHITE;
        int main = graphite ? 0xffe9eae5 : 0xfff5f5f2;
        int dim = graphite ? 0xffbbc2bd : 0xffb8bebc;
        if (graphite) {
            stock(c,62,447,53,main,HEAVY,694);
            label(c,symbol,1017,459,36,dim,MONO,Paint.Align.RIGHT,274);
            label(c,price,58,521,133,main,MONO,Paint.Align.LEFT,665);
            label(c,change,1017,596,53,changeColor,MONO,Paint.Align.RIGHT,323);
            paint.setColor(0xff202527);
            c.drawRect(34,803,1046,1622,paint);
            chart(c,62,833,1018,902,1587,dim,0xff5c6062);
            footer(c,62,1018,1661,dim);
        } else {
            left(c,symbol,42,704,32,dim);
            if (!chartOptions.intraday) right(c,chartOptions.legend(plot),1038,704,32,dim);
            paint.setTypeface(HEAVY);
            paint.setTextSize(76);
            float gap = 32;
            float textWidth = paint.measureText(name) + paint.measureText(price) + paint.measureText(change);
            float size = 76 * Math.min(1f, (1038 - 42 - 2*gap) / textWidth);
            paint.setTextSize(size);
            float priceEnd = 1038 - paint.measureText(change) - gap;
            stock(c,42,762,size,main,HEAVY,996);
            label(c,price,priceEnd,762,size,main,HEAVY,Paint.Align.RIGHT,996);
            label(c,change,1038,762,size,changeColor,HEAVY,Paint.Align.RIGHT,996);
            chart(c,42,810,1038,855,1590,dim,0xff555555);
            footer(c,57,1020,1652,dim);
        }
    }
    private void bookshop(Canvas c) {
        int ink = 0xffe8d6b0, dim = 0xffc4bfaa;
        left(c,date,72,110,39,ink); right(c,week,1006,114,32,dim);
        stock(c,72,378,55,ink,SERIF,511);
        left(c,symbol,76,449,33,dim);
        label(c,price,1006,369,79,ink,SERIF,Paint.Align.RIGHT,396);
        label(c,change,1006,467,34,changeColor,SERIF,Paint.Align.RIGHT,345);
        chart(c,29,522,1051,571,909,dim,0xff404b48);
        footer(c,935,dim);
    }
    private void convenience(Canvas c) {
        int ink = 0xffefdbbf, dim = 0xffccb9ba;
        right(c,date + "  ·  " + week,1012,114,36,dim);
        stock(c,292,373,46,ink,SANS,361);
        left(c,symbol,293,435,28,dim);
        label(c,price,1012,365,65,ink,SANS,Paint.Align.RIGHT,326);
        right(c,change,1012,443,28,changeColor);
        chart(c,280,483,1033,530,742,dim,0xff514653);
        footer(c,292,1012,758,dim);
    }
    private void bridge(Canvas c) {
        int ink = 0xffeddbbd, dim = 0xffb9bfbc;
        center(c,date + "   " + week,794,37,dim);
        stock(c,440,92,43,ink,HEAVY,305);
        left(c,symbol,441,148,25,dim);
        label(c,price,1013,86,57,ink,SANS,Paint.Align.RIGHT,244);
        right(c,change,1013,150,26,changeColor);
        chart(c,29,1444,1051,1494,1744,dim,0xff3b505f);
        footer(c,1760,dim);
    }
    private void noodles(Canvas c) {
        int ink = 0xfff1ddb8, dim = 0xffc8c4b4;
        paint.setColor(0xff6d675b); paint.setStrokeWidth(2); c.drawLine(744,378,744,557,paint);
        label(c,name,974,374,37,ink,SERIF,Paint.Align.RIGHT,208);
        label(c,price,975,432,64,ink,HEAVY,Paint.Align.RIGHT,209);
        right(c,change,975,525,33,changeColor);
        left(c,date + "   " + week,114,574,36,ink);
        right(c,symbol,974,579,29,dim);
        chart(c,29,1458,1051,1508,1744,dim,0xff444d47);
        footer(c,1760,dim);
    }
    private void cable(Canvas c) {
        int ink = 0xffeddec4, dim = 0xffa7b4b6;
        stock(c,74,90,44,ink,HEAVY,535);
        left(c,symbol,76,146,25,dim);
        label(c,price,1007,86,57,ink,SANS,Paint.Align.RIGHT,353);
        right(c,change,1007,150,26,changeColor);
        label(c,date,74,239,34,dim,SANS,Paint.Align.LEFT,153);
        right(c,week,1004,239,33,dim);
        chart(c,29,383,1051,431,741,dim,0xff303c48);
        footer(c,755,dim);
    }
}
