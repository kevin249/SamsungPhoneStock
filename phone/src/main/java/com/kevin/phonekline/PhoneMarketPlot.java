package com.kevin.phonekline;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A full trading day on widgets, or sixty recent candles on the full-screen candle view. */
final class PhoneMarketPlot {
    static final int WIDGET_UP = 0xffff8484, WIDGET_DOWN = 0xff62ff62;
    private static final int WIDGET_TEXT = 0xffffffff, WIDGET_GRID = 0xff777777;
    static final int AVERAGE_INK = 0xffffdc55;
    private static final int PRICE_INK = 0xffd4d4d4;
    private static final int WINDOW = 60, TRADING_MINUTES = 240;
    private static final double[] TDW_LEVELS = {.2, .5, 3.2, 3.5};
    private static final int[] TDW_COLORS = {0xff93db70, 0xffffff00, 0xff00c6c6, 0xffff75ff};
    private static final int TDW_INK = 0xffa8a8a8;
    private final boolean intraday, sessionAxis;
    private float formingEnd = Float.NaN;
    private final List<Candle> bars = new ArrayList<>();
    private final List<Fill> fills = new ArrayList<>();
    private final List<JSONObject> visibleOrders = new ArrayList<>();
    private double[][] rowVolumes, rowPriceVolumes;
    final List<TracePoint> trace = new ArrayList<>();
    private Paint paint;
    private static DashPathEffect gridDashes;
    private static DashPathEffect strengthDots;
    private double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY, largest;
    private final double dayHigh, dayLow;
    private boolean hasBook;
    private boolean hasAverage;
    private String bookLabel;

    static final class TracePoint {
        final float slot;
        final double price;
        final boolean start;
        TracePoint(float slot, double price, boolean start) {
            this.slot = slot; this.price = price; this.start = start;
        }
    }

    private static final class Candle {
        final String minute;
        final int slot;
        final boolean afternoon;
        final String firstTick;
        final double open, high, low, close;
        double average = Double.NaN;
        double tdw = Double.NaN;
        double priorTdw = Double.NaN;
        int tdwEvents;
        Candle(JSONObject row, String minute) {
            this.minute = minute;
            slot = sessionMinute(minute);
            afternoon = minute.substring(11).compareTo("13:00") >= 0;
            firstTick = row.optString("first_tick_at");
            open = row.optDouble("open");
            high = row.optDouble("high");
            low = row.optDouble("low");
            close = row.optDouble("close");
        }
        boolean valid() {
            return slot >= 0 && low > 0 && Double.isFinite(high) && low <= Math.min(open, close) && high >= Math.max(open, close);
        }
    }

    private static final class Fill {
        final int index;
        final boolean buy;
        final double price;
        Fill(int index, boolean buy, double price) { this.index = index; this.buy = buy; this.price = price; }
    }

    PhoneMarketPlot(JSONObject snapshot) { this(snapshot, false, true, true); }

    PhoneMarketPlot(JSONObject snapshot, boolean intraday, boolean showSignals, boolean showDepth) {
        this(snapshot, intraday, showSignals, showDepth, false);
    }

    PhoneMarketPlot(JSONObject snapshot, boolean intraday, boolean showSignals, boolean showDepth, boolean widget) {
        this.intraday = intraday;
        sessionAxis = intraday || widget;
        bookLabel = snapshot == null ? "暂无盘口" : snapshot.optString("book_status","暂无盘口");
        if (bookLabel.isEmpty()) bookLabel = "暂无盘口";
        dayHigh = snapshot == null ? Double.NaN : snapshot.optDouble("high");
        dayLow = snapshot == null ? Double.NaN : snapshot.optDouble("low");
        if (snapshot == null) return;
        JSONArray rows = snapshot.optJSONArray("bars");
        if (rows == null) return;
        String session = snapshot.optString("date");
        Map<String, Integer> index = new HashMap<>();
        List<Candle> reverse = new ArrayList<>();
        for (int i = rows.length() - 1; i >= 0; i--) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String stamp = minute(row.optString("time"));
            if (stamp.isEmpty()) continue;
            if (session.length() != 10) session = stamp.substring(0, 10);
            if (!stamp.startsWith(session)) continue;
            Candle candle = new Candle(row, stamp);
            if (candle.valid()) reverse.add(candle);
        }
        calculateTdw(reverse);
        Map<String,Double> averages = intraday ? PhoneSessionAverage.prices(snapshot) : null;
        int visible = sessionAxis ? reverse.size() : Math.min(WINDOW, reverse.size());
        for (int i = visible - 1; i >= 0; i--) {
            Candle candle = reverse.get(i);
            candle.average = intraday ? averages.getOrDefault(candle.minute,Double.NaN) : movingAverage(reverse,i);
            hasAverage |= PhoneRealtimeCandles.positive(candle.average);
            index.put(candle.minute, bars.size());
            bars.add(candle);
            low = Math.min(low, candle.low);
            high = Math.max(high, candle.high);
        }
        if (bars.isEmpty()) return;
        if (sessionAxis) {
            formingEnd = observedSlot(snapshot.optString("quote_at"), bars.get(bars.size()-1));
            if (intraday) buildTrace();
        }
        if (PhoneRealtimeCandles.positive(dayLow) && PhoneRealtimeCandles.positive(dayHigh) && dayLow <= dayHigh) {
            high = Math.max(high, dayHigh);
            low = Math.min(low, dayLow);
        }
        double marketLow = low, marketHigh = high;
        // The full-screen corner numbers identify the extrema of the price axis.
        // A flat session still needs a nonzero range to draw its one traded price.
        if (widget || high == low) {
            double extra = Math.max(.012, (high - low) * .17);
            low -= extra;
            high += extra;
        }
        JSONArray trades = showSignals ? snapshot.optJSONArray("fills") : null;
        for (int i = 0; trades != null && i < trades.length(); i++) {
            JSONObject trade = trades.optJSONObject(i);
            if (trade == null) continue;
            Integer position = index.get(minute(trade.optString("bar_time", trade.optString("time"))));
            String side = trade.optString("side");
            double price = trade.optDouble("price");
            if (position == null || !("B".equals(side) || "S".equals(side)) || !Double.isFinite(price)
                    || price <= 0 || price < low || price > high) continue;
            if (intraday && bars.get(position).slot > trace.get(trace.size() - 1).slot) continue;
            fills.add(new Fill(position, "B".equals(side), price));
        }
        JSONObject book = showDepth ? snapshot.optJSONObject("book") : null;
        if (book == null) return;
        String at = book.optString("source_at");
        if (at.length() >= 10 && !at.startsWith(session)
                && !PhoneRealtimeCandles.bookFitsChart(book,snapshot,PhoneRealtimeCandles.now())) return;
        JSONArray levels = book.optJSONArray("levels");
        for (int i = 0; levels != null && i < levels.length(); i++) {
            JSONObject row = levels.optJSONObject(i);
            if (row == null) continue;
            double price = row.optDouble("price"), volume = row.optDouble("volume");
            String side = row.optString("side");
            if (!("bid".equals(side) || "ask".equals(side)) || !(volume > 0) || !Double.isFinite(volume)
                    || !Double.isFinite(price) || price <= 0) continue;
            hasBook = true;
            // Padding gives labels breathing room. Keep the depth overlay within
            // the market range used to draw this set of candles.
            if (price >= marketLow && price <= marketHigh) visibleOrders.add(row);
        }
        if (hasBook) {
            String time = at.length() >= 19 ? " 采样 " + at.substring(11, 19) : "";
            bookLabel = (visibleOrders.isEmpty() ? "盘口超出图幅" : "L2 千档") + time;
        }
    }

    // 逃顶王 (tdx-251): EMA(4*(C-LLV(L,10))/(HHV(H,25)-LLV(L,10)),4).
    private static void calculateTdw(List<Candle> newestFirst) {
        double previous = Double.NaN, oldWeight = 1;
        int size = newestFirst.size();
        int[] lastEvent = {-21, -21, -21, -21};
        for (int i = size - 1; i >= 0; i--) {
            Candle bar = newestFirst.get(i);
            double prior = previous;
            double floor = bar.low, ceiling = bar.high;
            for (int j = i + 1; j < Math.min(size, i + 25); j++) {
                Candle earlier = newestFirst.get(j);
                ceiling = Math.max(ceiling, earlier.high);
                if (j < i + 10) floor = Math.min(floor, earlier.low);
            }
            oldWeight *= .6;
            if (ceiling > floor) {
                double raw = 4 * (bar.close - floor) / (ceiling - floor);
                previous = Double.isNaN(previous) ? raw : (oldWeight * previous + .4 * raw) / (oldWeight + .4);
                oldWeight = 1;
            }
            int ordinal = size - 1 - i;
            bar.tdw = previous;
            bar.priorTdw = prior;
            for (int k = 0; k < TDW_LEVELS.length; k++) {
                double level = TDW_LEVELS[k];
                boolean crossed = k < 2 ? prior <= level && previous > level : prior >= level && previous < level;
                if (crossed && ordinal - lastEvent[k] > 20) {
                    lastEvent[k] = ordinal;
                    bar.tdwEvents |= 1 << k;
                }
            }
        }
    }

    private static double movingAverage(List<Candle> newestFirst, int index) {
        if (index + 4 >= newestFirst.size()) return Double.NaN;
        Candle current = newestFirst.get(index);
        double total = current.close;
        for (int offset = 1; offset < 5; offset++) {
            Candle earlier = newestFirst.get(index+offset);
            if (earlier.slot != current.slot-offset) return Double.NaN;
            total += earlier.close;
        }
        return total / 5;
    }

    /** Slots 0..120 represent 09:30..11:30; 120..240 represent 13:00..15:00. */
    static int sessionMinute(String stamp) {
        String clock = stamp.length() >= 16 ? stamp.substring(11,16) : stamp;
        if (clock.length() != 5 || clock.charAt(2) != ':') return -1;
        int hh = 0, mm = 0;
        for (int i=0; i<5; i++) {
            if (i == 2) continue;
            int digit = clock.charAt(i) - '0';
            if (digit < 0 || digit > 9) return -1;
            if (i < 2) hh = hh * 10 + digit; else mm = mm * 10 + digit;
        }
        if (mm > 59) return -1;
        int t = hh * 60 + mm;
        if (t >= 570 && t <= 690) return t - 570;
        if (t >= 780 && t <= 900) return t - 660;
        return -1;
    }

    private static float observedSlot(String stamp, Candle candle) {
        LocalDateTime observed = PhoneRealtimeCandles.localTime(stamp);
        if (!PhoneRealtimeCandles.tradingMinute(observed)
                || !PhoneRealtimeCandles.BAR.format(PhoneRealtimeCandles.barEnd(observed)).equals(candle.minute)) return Float.NaN;
        int whole = sessionMinute(PhoneRealtimeCandles.BAR.format(observed));
        return Math.min(candle.slot, whole + (observed.getSecond() + observed.getNano()/1e9f) / 60f);
    }

    private void buildTrace() {
        Candle previous = null;
        for (int i=0; i<bars.size(); i++) {
            Candle bar = bars.get(i);
            boolean startsSegment = previous == null || bar.afternoon != previous.afternoon || bar.slot != previous.slot + 1;
            float end = bar.slot;
            if (i == bars.size()-1) {
                // A trade at 14:32:12 has a forming 14:33 candle. Draw the actual
                // trade position, leaving the rest of that minute and the day empty.
                if (Float.isFinite(formingEnd)) end = formingEnd;
            }
            if (startsSegment && (bar.slot == 1 || bar.slot == 121 || !bar.firstTick.isEmpty())) {
                float first = bar.firstTick.isEmpty() ? bar.slot-1 : observedSlot(bar.firstTick, bar);
                if (Float.isFinite(first) && first < end) {
                    trace.add(new TracePoint(first, bar.open, true));
                    startsSegment = false;
                }
            }
            trace.add(new TracePoint(end, bar.close, startsSegment));
            previous = bar;
        }
    }

    private static String minute(String stamp) {
        return stamp.length() >= 16 ? stamp.substring(0, 16).replace('T', ' ') : "";
    }

    boolean hasBook() { return hasBook; }
    boolean hasAverage() { return hasAverage; }
    double averageAt(int i) { return bars.get(i).average; }
    double tdwAt(int i) { return bars.get(i).tdw; }
    int tdwEventsAt(int i) { return bars.get(i).tdwEvents; }
    String bookLabel() { return bookLabel; }
    boolean hasSignals() { return !fills.isEmpty(); }
    double lastClose() { return bars.isEmpty() ? Double.NaN : bars.get(bars.size() - 1).close; }

    float barX(int i, float left, float width) {
        if (!sessionAxis) return left + width * (i + .5f) / Math.max(12, bars.size());
        Candle bar = bars.get(i);
        float slot = i == bars.size()-1 && Float.isFinite(formingEnd) ? formingEnd : bar.slot;
        if (!intraday) {
            // A bar is centered in its observed minute interval. Lunch is folded
            // at slot 120 and missing trading minutes keep their empty slots.
            float start = Math.max(bar.afternoon ? 120 : 0, bar.slot-1);
            float first = bar.firstTick.isEmpty() ? Float.NaN : observedSlot(bar.firstTick, bar);
            if (Float.isFinite(first)) start = Math.max(start, first);
            slot = (Math.min(start, slot) + slot) / 2;
        }
        return left + width * slot / TRADING_MINUTES;
    }

    void draw(Canvas c, float left, float top, float right, float bottom, int rise, int fall, int text, int grid) {
        draw(c, left, top, right, bottom, rise, fall, text, grid, false);
    }

    /** Higher-output strokes for the small bitmap dimmed and rescaled by the AOD host. */
    void drawWidget(Canvas c, float left, float top, float right, float bottom) {
        draw(c, left, top, right, bottom, WIDGET_UP, WIDGET_DOWN, WIDGET_TEXT, WIDGET_GRID, true);
    }

    private void draw(Canvas c, float left, float top, float right, float bottom,
                      int rise, int fall, int text, int grid, boolean widget) {
        if (paint == null) paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (widget) {
            paint.setColor(0xff000000);
            c.drawRect(left, top, right, bottom, paint);
        }
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setFakeBoldText(widget);
        boolean compact = widget && bottom - top < 145;
        float axisText = compact ? 24 : 26;
        float x0 = widget ? left : left + 3, x1 = widget ? right : right - 3;
        float y0 = top + (compact ? 8 : 15), y1 = bottom - (compact ? 30 : 44);
        float tdwBottom = y1;
        boolean showTdw = !widget || !intraday;
        if (showTdw) y1 -= (tdwBottom - y0) * .26f + 5;
        float timeText = widget ? (compact ? 23 : 26) : 24;
        paint.setTextSize(axisText);
        float labelEnd = x0 + 7 + Math.max(PhoneRealtimeCandles.positive(dayHigh) ? paint.measureText(format(dayHigh)) : 0,
                PhoneRealtimeCandles.positive(dayLow) ? paint.measureText(format(dayLow)) : 0);
        if (bars.isEmpty()) {
            caption(c, intraday ? "等待分时行情" : "等待一分钟 K 线", (labelEnd + right) / 2,
                    (top + bottom) / 2, 32, text, Paint.Align.CENTER);
            drawDayRange(c, x0, y0, y1, axisText);
            if (widget) {
                sessionTimes(c, x0, x1, bottom, timeText, text, true);
            }
            return;
        }
        float height = y1 - y0;
        float priceSize = widget ? (compact ? 16 : 20) : 19;
        paint.setTextSize(priceSize);
        float priceRight = x1 - 2;
        float bookEnd = x1 - Math.max(paint.measureText(format(high)),paint.measureText(format(low))) - 10;
        if (!widget) x1 = bookEnd;
        float width = x1 - x0;
        double tick = priceTick(height,priceSize);
        int gray = Math.max(84, Math.min(116, ((grid >> 8) & 255) + 36));
        paint.setColor(widget ? WIDGET_GRID : android.graphics.Color.rgb(gray,gray,gray));
        paint.setStrokeWidth(widget ? 1.7f : 1.5f);
        if (gridDashes == null) gridDashes = new DashPathEffect(new float[]{9f, 7f}, 0);
        paint.setPathEffect(gridDashes);
        drawTimeGrid(c,x0,width,y0,y1);
        double first = Math.ceil(low/tick-1e-8)*tick;
        int tickCount = Math.min(12, 1+(int)Math.floor((high-first)/tick+1e-8));
        for (int i=0; i<tickCount; i++) {
            double p = first + i*tick;
            float y = priceY(p,y1,height);
            c.drawLine(x0,y,x1,y,paint);
        }
        paint.setPathEffect(null);
        drawDepth(c, x0, bookEnd, y1, height, rise, fall, widget);
        Candle last = bars.get(bars.size() - 1);
        if (intraday) drawTrace(c, x0, width, y1, height, widget ? 0xffffffff : text, widget);
        else drawCandles(c, x0, width, y1, height, rise, fall, widget);
        if (hasAverage) drawAverage(c,x0,width,y0,y1,widget);
        paint.setTextSize(priceSize);
        paint.setTextAlign(Paint.Align.RIGHT);
        for (int i=0; i<tickCount; i++) {
            double p = first + i*tick;
            float y = priceY(p,y1,height);
            if (widget && nearRightSignal(x0,width,bookEnd-5,y1,height,y,priceSize)) continue;
            outlinedNumber(c,format(p),priceRight,y-(paint.ascent()+paint.descent())*.5f,PRICE_INK);
        }
        for (Fill f : fills) {
            float x = barX(f.index, x0, width);
            float tip = priceY(f.price, y1, height);
            drawFillArrow(c, x, tip, f.buy, f.buy ? 0xffff5151 : 0xff48e486, y0, y1);
        }
        drawDayRange(c, x0, y0, y1, axisText);
        if (showTdw) drawTdw(c, x0, width, y1 + 5, tdwBottom);
        if (sessionAxis) {
            sessionTimes(c, x0, x1, bottom, timeText, text, widget);
        } else {
            caption(c, bars.get(0).minute.substring(11), barX(0,x0,width), bottom - 6, timeText, text, Paint.Align.LEFT);
            if (bars.size() > 5) {
                float end = barX(bars.size()-1,x0,width);
                caption(c, last.minute.substring(11), end, bottom - 6, timeText, text, Paint.Align.RIGHT);
            }
        }
    }

    private void drawFillArrow(Canvas c, float x, float price, boolean buy, int color, float top, float bottom) {
        float length = Math.min(24, (bottom - top) * .32f);
        float dy = buy ? length : -length;
        float tip = buy ? Math.min(price, bottom - length) : Math.max(price, top + length);
        Path arrow = new Path();
        arrow.moveTo(x, tip);
        arrow.lineTo(x - length * .32f, tip + dy * .53f);
        arrow.lineTo(x - 2, tip + dy * .53f);
        arrow.lineTo(x - 2, tip + dy);
        arrow.lineTo(x + 2, tip + dy);
        arrow.lineTo(x + 2, tip + dy * .53f);
        arrow.lineTo(x + length * .32f, tip + dy * .53f);
        arrow.close();
        paint.setColor(0xff151515);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        c.drawPath(arrow, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(arrow, paint);
    }

    private void drawTdw(Canvas c, float left, float width, float top, float bottom) {
        float height = bottom - top;
        paint.setColor(0xff777777);
        paint.setStrokeWidth(1);
        c.drawLine(left, top, left + width, top, paint);
        paint.setPathEffect(gridDashes);
        drawTimeGrid(c, left, width, top, bottom);
        paint.setPathEffect(null);
        for (int k = 0; k < TDW_LEVELS.length; k++) {
            float y = bottom - (float)(TDW_LEVELS[k] / 4) * height;
            paint.setColor(TDW_COLORS[k]);
            c.drawLine(left, y, left + width, y, paint);
        }
        float strengthY = bottom - height * 1.75f / 4;
        if (strengthDots == null) strengthDots = new DashPathEffect(new float[]{2, 5}, 0);
        paint.setPathEffect(strengthDots);
        paint.setStrokeWidth(2);
        paint.setColor(TDW_COLORS[0]);
        c.drawLine(left, strengthY, left + width, strengthY, paint);
        paint.setPathEffect(null);
        float step = width / (sessionAxis ? TRADING_MINUTES : Math.max(12,bars.size()));
        float halfBar = Math.min(7, step * .34f);
        float lastX = 0, lastY = Float.NaN;
        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            if (!Double.isFinite(bar.tdw)) { lastY = Float.NaN; continue; }
            float x = barX(i, left, width), y = bottom - (float)(bar.tdw / 4) * height;
            if (Double.isFinite(bar.priorTdw)) {
                float priorY = bottom - (float)(bar.priorTdw / 4) * height;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1);
                paint.setColor(bar.tdw > bar.priorTdw ? 0xffff0000 : 0xff00ff00);
                c.drawRect(Math.max(left,x-halfBar), Math.min(priorY,y), Math.min(left+width,x+halfBar),
                        Math.max(priorY,y) + .5f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(TDW_INK);
            paint.setStrokeWidth(1.5f);
            if (Float.isFinite(lastY) && bars.get(i - 1).slot + 1 == bar.slot)
                c.drawLine(lastX, lastY, x, y, paint);
            else c.drawCircle(x, y, 1.5f, paint);
            lastX = x;
            lastY = y;
        }
        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            if (bar.tdwEvents == 0) continue;
            boolean buy = (bar.tdwEvents & 3) != 0;
            float at = bottom - (float)((bar.tdw + .02) / 4) * height;
            drawFillArrow(c, barX(i, left, width), at, buy, buy ? 0xffff5151 : 0xff48e486, top, bottom);
        }
        float size = Math.min(20, height * .6f);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
        double latest = bars.get(bars.size()-1).tdw;
        outlinedNumber(c, Double.isFinite(latest) ? "TDW " + format(latest) : "TDW 待数据", left + 4, top + size, TDW_INK);
    }

    private void drawDepth(Canvas canvas, float left, float right, float bottom, float height,
                           int rise, int fall, boolean widget) {
        if (visibleOrders.isEmpty() || height <= 0 || right <= left) return;
        int rows = Math.max(1, (int)Math.ceil(height / 4f));
        if (rowVolumes == null || rowVolumes[0].length != rows) {
            rowVolumes = new double[2][rows];
            rowPriceVolumes = new double[2][rows];
            largest = 0;
            for (JSONObject order : visibleOrders) {
                double price = order.optDouble("price"), volume = order.optDouble("volume");
                int row = Math.min(rows - 1, (int)((price - low) / (high - low) * rows));
                int side = "bid".equals(order.optString("side")) ? 0 : 1;
                rowVolumes[side][row] += volume;
                rowPriceVolumes[side][row] += price * volume;
                largest = Math.max(largest, rowVolumes[side][row]);
            }
        }
        float stroke = height / rows * .94f;
        float maxBarWidth = (right - left) * .72f;
        for (int side = 0; side < 2; side++) {
            paint.setColor(side == 0 ? rise : fall);
            paint.setAlpha(widget ? 248 : 184);
            for (int row = 0; row < rows; row++) {
                double volume = rowVolumes[side][row];
                if (volume <= 0) continue;
                // An isolated order sits at its exact price. Unresolved neighbors
                // of the same side share their volume-weighted price on the same axis.
                double price = rowPriceVolumes[side][row] / volume;
                float y = priceY(price, bottom, height);
                float length = (float)(maxBarWidth * volume / largest);
                paint.setStrokeWidth(side == 1 && rowVolumes[0][row] > 0 ? stroke * .5f : stroke);
                canvas.drawLine(right - length, y, right, y, paint);
            }
        }
    }

    private double priceTick(float height, float textSize) {
        int intervals = Math.max(2,Math.min(5,(int)(height / (textSize*1.75f))));
        double raw = (high-low)/intervals;
        double scale = Math.pow(10,Math.floor(Math.log10(raw)));
        double unit = raw/scale;
        double step = Math.max(.01, scale * (unit < 1.5 ? 1 : unit < 3 ? 2 : unit < 7 ? 5 : 10));
        // A compact panel still has room for two reference prices when the
        // rounded step happens to leave only one multiple inside its range.
        if (Math.floor(high/step+1e-8) == Math.ceil(low/step-1e-8)) step = Math.max(.01, step*.5);
        return step;
    }

    private void drawTimeGrid(Canvas c, float left, float width, float top, float bottom) {
        if (sessionAxis) {
            for (int slot=0; slot<=TRADING_MINUTES; slot+=60) {
                float x = left + width*slot/TRADING_MINUTES;
                c.drawLine(x,top,x,bottom,paint);
            }
        } else {
            int interval = bars.size() < 8 ? 1 : bars.size() < 30 ? 5 : 15;
            for (int i=0; i<bars.size(); i++) {
                if (i!=0 && i!=bars.size()-1 && bars.get(i).slot%interval!=0) continue;
                float x = barX(i,left,width);
                c.drawLine(x,top,x,bottom,paint);
            }
        }
    }

    private boolean nearRightSignal(float x0,float width,float start,float y1,float height,float y,float size) {
        for (Fill fill : fills) {
            if (barX(fill.index,x0,width) >= start && Math.abs(priceY(fill.price,y1,height)-y) < size*.65f+4)
                return true;
        }
        return false;
    }

    private void sessionTimes(Canvas c, float left, float right, float bottom, float size, int text, boolean widget) {
        caption(c, "09:30", left, bottom-6, size, text, Paint.Align.LEFT);
        if (right-left >= 640) {
            caption(c, "10:30", left+(right-left)*.25f, bottom-6, size, text, Paint.Align.CENTER);
            caption(c, "14:00", left+(right-left)*.75f, bottom-6, size, text, Paint.Align.CENTER);
        }
        if (!widget || right-left >= 350)
            caption(c, "11:30/13:00", (left+right)/2, bottom-6, widget ? 24 : 22, text, Paint.Align.CENTER);
        caption(c, "15:00", right, bottom-6, size, text, Paint.Align.RIGHT);
    }

    private void drawDayRange(Canvas c, float left, float top, float bottom, float size) {
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.LEFT);
        drawRangeLabel(c, dayHigh, left+3, top-paint.ascent());
        drawRangeLabel(c, dayLow, left+3, bottom-paint.descent());
    }

    private void drawRangeLabel(Canvas c, double value, float x, float y) {
        if (!PhoneRealtimeCandles.positive(value)) return;
        outlinedNumber(c,format(value),x,y,WIDGET_TEXT);
    }

    private void outlinedNumber(Canvas c,String number,float x,float y,int ink) {
        // Outline only the glyphs. Early candles remain visible between the digits.
        paint.setColor(0xdc000000);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(3.5f);
        c.drawText(number,x,y,paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ink);
        c.drawText(number,x,y,paint);
    }

    private void drawTrace(Canvas c, float x0, float width, float y1, float height, int color, boolean widget) {
        Path path = new Path();
        paint.setColor(color);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (TracePoint point : trace) {
            float x = x0 + width * point.slot/TRADING_MINUTES;
            float y = priceY(point.price, y1, height);
            if (point.start) {
                path.moveTo(x,y);
                c.drawCircle(x,y,widget ? 3.7f : 1.8f,paint);
            } else path.lineTo(x,y);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(widget ? 4.3f : 2.6f);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        TracePoint end = trace.get(trace.size()-1);
        c.drawCircle(x0 + width * end.slot/TRADING_MINUTES, priceY(end.price,y1,height), widget ? 7.3f : 4.3f, paint);
    }

    private void drawAverage(Canvas c,float x0,float width,float y0,float y1,boolean widget) {
        Path path = new Path();
        boolean connected = false;
        int previous = -1;
        paint.setColor(AVERAGE_INK);
        float stroke = widget ? 3.2f : 2.4f;
        c.save();
        c.clipRect(x0,y0,x0+width,y1);
        for (int i=0; i<bars.size(); i++) {
            Candle bar = bars.get(i);
            if (!PhoneRealtimeCandles.positive(bar.average)) { connected=false; continue; }
            float x = intraday ? x0+width*bar.slot/TRADING_MINUTES : barX(i,x0,width);
            float y = priceY(bar.average,y1,y1-y0);
            boolean sameSession = i>0 && bars.get(i-1).afternoon == bar.afternoon;
            if (connected && previous+1==bar.slot && sameSession) path.lineTo(x,y);
            else {
                path.moveTo(x,y);
                c.drawCircle(x,y,stroke*.55f,paint);
            }
            previous = bar.slot;
            connected = true;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(stroke);
        c.drawPath(path,paint);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    private void drawCandles(Canvas c, float x0, float width, float y1, float height, int rise, int fall, boolean widget) {
        float step = width / (sessionAxis ? TRADING_MINUTES : Math.max(12,bars.size()));
        float half = widget ? step*.31f : Math.min(8f,step*.25f);
        if (widget) {
            c.save();
            float through = Float.isFinite(formingEnd) ? Math.max(1f,width*formingEnd/TRADING_MINUTES) : width;
            c.clipRect(x0,y1-height,x0+through,y1+1);
        }
        for (int i = 0; i < bars.size(); i++) {
            Candle bar = bars.get(i);
            float x = barX(i,x0,width);
            float a = priceY(bar.open, y1, height), b = priceY(bar.close, y1, height);
            paint.setColor(bar.close >= bar.open ? rise : fall);
            paint.setStrokeWidth(widget ? Math.min(3.2f,step*.39f) : Math.min(1.65f,step*.35f));
            c.drawLine(x, priceY(bar.high, y1, height), x, priceY(bar.low, y1, height), paint);
            float upper = Math.min(a, b);
            c.drawRect(x - half, upper, x + half, Math.max(upper + (widget ? 3.6f : 1.8f), Math.max(a, b)), paint);
        }
        if (widget) c.restore();
    }

    float priceY(double price, float y1, float height) {
        return y1 - (float)((price - low) / (high - low)) * height;
    }

    private void caption(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        paint.setColor(color); paint.setTextSize(size); paint.setTextAlign(align);
        c.drawText(s, x, y, paint);
    }
    static String format(double v) { return Double.isFinite(v) ? String.format(Locale.ROOT, "%.2f", v) : "—"; }
}
