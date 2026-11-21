package com.kevin.phonekline;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercise the two independently updated panels through a real RemoteViews host. */
final class PhoneDualWidgetInstrumentation {
    static void check(Context context, Context host, SharedPreferences prefs) throws Exception {
        prefs.edit().putString("symbol","sh603986").putString("second_symbol","sz000063")
                .putBoolean("show_depth",true).putBoolean("show_signals",true).commit();
        JSONObject upper = PhoneDualChartInstrumentation.fixture("sh603986",false);
        JSONObject lower = PhoneDualChartInstrumentation.fixture("sz000063",false);
        upper.put("price",36.1); lower.put("price",35.2);
        upper.put("book",book(100)); lower.put("book",book(200));
        for (int[] size : new int[][]{{150,95},{280,285},{315,400}}) {
            for (String period : new String[]{"realtime","1m","1d"}) {
                if (period.equals("1d")) {
                    prefs.edit().putString("daily_snapshot_sh603986",PhoneDualChartInstrumentation.fixture("sh603986",true).toString())
                            .putString("daily_snapshot_sz000063",PhoneDualChartInstrumentation.fixture("sz000063",true).toString()).commit();
                }
                RemoteViews remote = PhoneWidgetRenderer.renderDual(context,-1,upper,lower,size[0],size[1],period);
                View root = remote.apply(host,new FrameLayout(host));
                float density = host.getResources().getDisplayMetrics().density;
                int width = Math.round(size[0]*density), height = Math.round(size[1]*density);
                root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
                root.layout(0,0,width,height);
                View top = root.findViewById(R.id.widget_upper_slot), bottom = root.findViewById(R.id.widget_lower_slot);
                require(top.getHeight()>0 && bottom.getHeight()>0 && top.getBottom()<=bottom.getTop(),"two separate visible slots");
                require(description(top).contains("603986") && !description(top).contains("000063"),"upper symbol isolation");
                require(description(bottom).contains("000063") && !description(bottom).contains("603986"),"lower symbol isolation");
                if (size[1]>=285 && !period.equals("1d")) {
                    Bitmap before = plot(top);
                    JSONObject update = new JSONObject(lower.toString()).put("price",35.9).put("book",book(800));
                    PhoneWidgetRenderer.renderDual(context,-1,upper,update,size[0],size[1],period).reapply(host,root);
                    require(before.sameAs(plot(root.findViewById(R.id.widget_upper_slot))),"lower update cannot redraw different upper data");
                    require(((TextView)root.findViewById(R.id.widget_lower_slot).findViewById(R.id.widget_price)).getText().toString().equals("35.90"),"lower quote refreshed");
                    PhoneWidgetRenderer.renderDual(context,-1,upper,upper,size[0],size[1],period).reapply(host,root);
                    require(description(root.findViewById(R.id.widget_lower_slot)).contains("现价 —"),"wrong-symbol response must not leak into lower panel");
                }
            }
        }
        prefs.edit().remove("second_symbol").commit();
        require(PhoneWidgetRenderer.render(context,-1,upper,280,285,"realtime").getLayoutId()==R.layout.phone_market_widget_zsingle,
                "removing second stock restores single widget layout");
    }

    private static JSONObject book(int volume) throws Exception {
        return new JSONObject().put("source_at","2026-10-30 11:10:00").put("levels",new JSONArray()
                .put(new JSONObject().put("side","bid").put("price",35.1).put("volume",volume))
                .put(new JSONObject().put("side","ask").put("price",35.8).put("volume",100)));
    }

    private static String description(View panel) { return panel.findViewById(R.id.widget_root).getContentDescription().toString(); }
    private static Bitmap plot(View panel) { return ((BitmapDrawable)((ImageView)panel.findViewById(R.id.widget_plot)).getDrawable()).getBitmap(); }
    private static void require(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
