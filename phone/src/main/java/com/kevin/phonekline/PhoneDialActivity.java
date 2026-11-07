package com.kevin.phonekline;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Iterator;
import com.kevin.market.StockSearch;

/** Theme selection and full-screen display. System surfaces use the same preferences. */
public final class PhoneDialActivity extends Activity {
    private static final int SETTINGS = 0, LOCK_PREVIEW = 2, DREAM_PREVIEW = 3;
    private static final int INK = 0xffebede7, MUTED = 0xffb4bcb6;
    private static final int[] LOCK_IDS = {PhoneCandleView.LOCK_BLACK, PhoneCandleView.LOCK_GRAPHITE, 95, 96, 100, 101, 102};
    private static final String[] LOCK_NAMES = {"114 · 纯黑行情", "115 · 深灰网格", "95 · 深夜书店",
            "96 · 雨停便利店", "100 · 桥影", "101 · 面摊", "102 · 跨江索道"};
    private static final int[] RATES = {1, 2, 5, 10};

    private SharedPreferences prefs;
    private PhoneQuoteSession session;
    private JSONObject snapshot;
    private String symbol, feedStatus = "正在读取行情";
    private int screen, lockTheme, rate = 2;
    private PhoneSamplingRate samplingRate;
    private boolean paused, visible, dirty;
    private long lastSave;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private String measuredRates = "";
    private final Runnable showRates = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            measuredRates = "";
            if (status != null && !paused && !prefs.getString("mcp_url", "").isEmpty()) {
                PhoneQuoteSession.RateSample rates = PhoneQuoteSession.rates(symbol);
                measuredRates = "\n近 1 秒 · 逐笔请求 " + rates.tradeSent + " 次，成功返回 " + rates.tradeOk
                        + " 次\n近 1 秒 · 盘口请求 " + rates.depthSent + " 次，成功返回 " + rates.depthOk + " 次";
            }
            updateStatus();
            ui.postDelayed(this, 1_000);
        }
    };
    private LinearLayout panel;
    private PhoneCandleView canvas;
    private PhoneSystemDisplay previewDisplay;
    private int settingsTab;
    private LinearLayout settingsRoot;
    private TextView status;
    private Button pluggedRate, unpluggedRate, pauseButton;
    private Switch liveAod;
    private Button schedulePermission, backgroundPermission, batteryPermission;
    private TextView livePowerStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        if (!prefs.contains("first_chart_mode")) prefs.edit().putString("first_chart_mode",PhoneDisplayStocks.firstMode(prefs)).apply();
        symbol = StockSearch.normalize(prefs.getString("symbol", "sz000063"));
        if (symbol == null) symbol = "sz000063";
        lockTheme = LOCK_IDS[index(LOCK_IDS, prefs.getInt("lock_theme", PhoneCandleView.LOCK_BLACK), 0)];
        paused = prefs.getBoolean("paused", false);
        screen = state == null ? SETTINGS : state.getInt("screen", SETTINGS);
        if (screen != LOCK_PREVIEW && screen != DREAM_PREVIEW) screen = SETTINGS;
        settingsTab=state==null?0:state.getInt("settings_tab",0);
        session = new PhoneQuoteSession(this::onQuote);
        samplingRate = new PhoneSamplingRate(this,prefs,hz -> {
            rate=hz;
            session.setRate(hz);
            if (canvas!=null) canvas.setRefreshState(hz,paused);
        });
        restoreSnapshot();
        renderScreen();
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private static int index(int[] keys, int value, int fallback) {
        for (int i = 0; i < keys.length; i++) if (keys[i] == value) return i;
        return fallback;
    }

    private TextView label(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private Button button(String value, View.OnClickListener action) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextColor(INK);
        view.setTextSize(15);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setBackgroundTintList(ColorStateList.valueOf(0xff27352f));
        view.setOnClickListener(action);
        return view;
    }

    private void heading(String title) {
        TextView view = label(title, 21, INK);
        view.setPadding(0, dp(23), 0, dp(4));
        panel.addView(view);
    }

    private void line(View view) {
        panel.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void pair(View first, View second) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, -2, 1);
        other.leftMargin = dp(7);
        row.addView(second, other);
        line(row);
    }

    private void renderScreen() {
        if (previewDisplay != null) { previewDisplay.close(); previewDisplay = null; }
        canvas = null;
        status = null;
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(screen == SETTINGS ? View.SYSTEM_UI_FLAG_VISIBLE
                : View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (screen == SETTINGS) {
            buildSettings();
        } else {
            previewDisplay = new PhoneSystemDisplay(this,screen == LOCK_PREVIEW,() -> {
                if (canvas != null) canvas.invalidate();
            });
            canvas = previewDisplay.view;
            if (visible) previewDisplay.show();
            canvas.setOnClickListener(v -> openScreen(SETTINGS));
            setContentView(canvas);
        }
    }

    private void buildSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xff111613);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(20), dp(32));
        panel.setFocusableInTouchMode(true);
        scroll.addView(panel);
        setContentView(scroll);
        line(label("手机行情", 29, INK));
        LinearLayout tabs = new LinearLayout(this);
        String[] titles = {"显示","行情","后台"};
        for (int i=0;i<titles.length;i++) {
            final int tab=i;
            Button item=button(titles[i],v -> { settingsTab=tab; renderScreen(); });
            item.setBackgroundTintList(ColorStateList.valueOf(i==settingsTab ? 0xffa5efbc : 0xff242a27));
            item.setTextColor(i==settingsTab ? 0xff102719 : INK);
            tabs.addView(item,new LinearLayout.LayoutParams(0,-2,1));
        }
        line(tabs);
        settingsRoot=panel;
        liveAod=null; schedulePermission=null; backgroundPermission=null; batteryPermission=null; livePowerStatus=null;
        if (settingsTab==0) {
            beginCard();
            String second=PhoneDisplayStocks.second(prefs);
            LinearLayout stockHeading=new LinearLayout(this);
            stockHeading.setGravity(Gravity.CENTER_VERTICAL);
            stockHeading.addView(label("显示股票",20,INK),new LinearLayout.LayoutParams(0,-2,1));
            stockHeading.addView(label(second.isEmpty()?"1 / 2":"2 / 2",15,MUTED));
            line(stockHeading);
            line(label("锁屏与充电屏保共用",13,MUTED));
            stockRow(symbol,false);
            line(label("上方图表周期",16,INK));
            periodControl(false);
            if (second.isEmpty()) line(button("＋ 添加第二只股票",v -> pickStock(true)));
            else {
                stockRow(second,true);
                line(label("拖动左侧手柄或点击 ⇅ 调整上下顺序",12,MUTED));
                line(label("下方图表周期",16,INK));
                periodControl(true);
            }
            endCard();
            beginCard();
            line(label("显示主题",20,INK));
            LinearLayout themes=new LinearLayout(this);
            String[] themeNames={"纯黑","深灰","场景"};
            for(int i=0;i<themeNames.length;i++) {
                final int id=i;
                boolean selected=i==0?lockTheme==PhoneCandleView.LOCK_BLACK:i==1?lockTheme==PhoneCandleView.LOCK_GRAPHITE:lockTheme<114;
                Button item=button(themeNames[i],v -> {
                    if (id==2) chooseTheme();
                    else {
                        lockTheme=id==0?PhoneCandleView.LOCK_BLACK:PhoneCandleView.LOCK_GRAPHITE;
                        prefs.edit().putInt("lock_theme",lockTheme).apply(); renderScreen();
                    }
                });
                item.setTextColor(selected?0xff102719:INK);
                item.setBackgroundTintList(ColorStateList.valueOf(selected?0xffa5efbc:0xff343a36));
                themes.addView(item,new LinearLayout.LayoutParams(0,-2,1));
            }
            line(themes);
            endCard();
            beginCard();
            line(label("锁屏壁纸",20,INK));
            pair(button("预览",v -> openScreen(LOCK_PREVIEW)),button("应用到锁屏",v -> {
                if (!PhoneWallpaperService.openPicker(this)) toast("这台设备没有提供动态壁纸选择器");
            }));
            endCard();
            beginCard();
            line(label("充电屏保",20,INK));
            pair(button("预览",v -> openScreen(DREAM_PREVIEW)),button("系统屏保设置",v -> {
                if (!PhoneMarketDreamService.openSettings(this)) toast("这台设备没有提供系统屏保设置入口");
            }));
            endCard();
            beginCard();
            line(button("三星 AOD  ›",v -> { settingsTab=2; renderScreen(); }));
            line(label("息屏小组件与持续更新 · 使用上方股票",13,MUTED));
            endCard();
        } else if (settingsTab==1) {
            beginCard();
            line(label("AOD 图表",20,INK));
            addChartControls();
            endCard();
            beginCard();
            line(label("行情获取间隔",20,INK));
            addRateControls();
            pauseButton=button(paused?"继续显示":"暂停显示",v -> togglePause());
            pair(pauseButton,button("配置实时接口",v -> configureMcp()));
            status=label("",13,MUTED); line(status); updateStatus();
            endCard();
        } else {
            beginCard();
            line(label("三星息屏行情（AOD）",20,INK));
            addLiveControl();
            line(button("添加到息屏显示",v -> showWidgetSetup()));
            line(label("AOD 显示上方股票；锁屏壁纸与充电屏保支持上下两只。",13,MUTED));
            endCard();
        }
        panel.requestFocus();
    }

    private void beginCard() {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14),dp(12),dp(14),dp(12));
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(0xff202523); background.setCornerRadius(dp(14));
        background.setStroke(dp(1),0xff343c37); card.setBackground(background);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2); layout.topMargin=dp(12);
        settingsRoot.addView(card,layout); panel=card;
    }

    private void endCard() { panel=settingsRoot; }

    private String stockTitle(String code) {
        try {
            JSONObject cached = code.equals(symbol) && snapshot!=null ? snapshot
                    : new JSONObject(prefs.getString("snapshot_"+code,"{}"));
            String name=cached.optString("name",prefs.getString("stock_name_"+code,""));
            return (name.isEmpty()?"股票行情":name)+"\n"+code.substring(2);
        } catch (Exception invalid) { return code; }
    }

    private void stockRow(String code, boolean second) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        Button order=button("⇅",v -> swapStocks());
        order.setContentDescription("上下股票换位，也可拖动调整顺序");
        order.setEnabled(!PhoneDisplayStocks.second(prefs).isEmpty());
        final float[] start={0};
        order.setOnTouchListener((v,event) -> {
            if (event.getAction()==android.view.MotionEvent.ACTION_DOWN) {
                start[0]=event.getRawY(); v.getParent().requestDisallowInterceptTouchEvent(true); return true;
            }
            if (event.getAction()==android.view.MotionEvent.ACTION_UP) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
                if (Math.abs(event.getRawY()-start[0])>dp(24)) swapStocks(); else v.performClick();
                return true;
            }
            if (event.getAction()==android.view.MotionEvent.ACTION_CANCEL) v.getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        });
        row.addView(order,new LinearLayout.LayoutParams(dp(48),dp(56)));
        TextView title=label(stockTitle(code),16,INK); title.setPadding(dp(6),0,0,0);
        row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button replace=button("更换",v -> pickStock(second)); replace.setContentDescription(second?"更换下方股票":"更换上方股票");
        row.addView(replace,new LinearLayout.LayoutParams(dp(64),dp(56)));
        if (!PhoneDisplayStocks.second(prefs).isEmpty()) {
            Button remove=button("−",v -> {
                if (!second) {
                    prefs.edit().putString("first_chart_mode",PhoneDisplayStocks.mode(prefs)).apply();
                    changeSymbol(PhoneDisplayStocks.second(prefs));
                }
                prefs.edit().remove("second_symbol").apply(); renderScreen();
            });
            remove.setContentDescription(second?"移除下方股票":"移除上方股票");
            row.addView(remove,new LinearLayout.LayoutParams(dp(48),dp(56)));
        }
        line(row);
    }

    private void pickStock(boolean second) {
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(second?"下方股票":"上方股票").setNegativeButton("取消",null).create();
        PhoneStockPicker picker=new PhoneStockPicker(this,second?PhoneDisplayStocks.second(prefs):symbol,code -> {
            String other=second?symbol:PhoneDisplayStocks.second(prefs);
            if (code.equals(other)) { toast("两只股票不能重复"); return; }
            if (second) prefs.edit().putString("second_symbol",code).apply(); else changeSymbol(code);
            dialog.dismiss(); renderScreen();
        });
        picker.setPadding(dp(12),dp(12),dp(12),dp(12)); dialog.setView(picker); dialog.show();
    }

    private void swapStocks() {
        String second=PhoneDisplayStocks.second(prefs);
        if (second.isEmpty()) return;
        String previous=symbol;
        String upperMode=PhoneDisplayStocks.firstMode(prefs), lowerMode=PhoneDisplayStocks.mode(prefs);
        prefs.edit().putString("first_chart_mode",lowerMode).putString("second_chart_mode",upperMode).apply();
        changeSymbol(second);
        prefs.edit().putString("second_symbol",previous).apply();
        renderScreen();
    }

    private void periodControl(boolean second) {
        String preference = second ? "second_chart_mode" : "first_chart_mode";
        String current = second ? PhoneDisplayStocks.mode(prefs) : PhoneDisplayStocks.firstMode(prefs);
        LinearLayout group=new LinearLayout(this);
        String[] names={"实时","1min","1day"}, keys={"realtime","1m","1d"};
        for(int i=0;i<keys.length;i++) {
            final String mode=keys[i]; boolean selected=mode.equals(current);
            Button choice=button(names[i],v -> { prefs.edit().putString(preference,mode).apply(); renderScreen(); });
            choice.setContentDescription((second?"下方":"上方")+"图表周期 "+names[i]); choice.setSelected(selected);
            choice.setTextColor(selected?0xff102719:INK);
            choice.setBackgroundTintList(ColorStateList.valueOf(selected?0xffa5efbc:0xff343a36));
            group.addView(choice,new LinearLayout.LayoutParams(0,-2,1));
        }
        line(group);
    }

    private void addLiveControl() {
        liveAod = new Switch(this);
        liveAod.setText("持续更新息屏行情");
        liveAod.setTextColor(INK);
        liveAod.setMinHeight(dp(48));
        liveAod.setChecked(prefs.getBoolean("live_aod_enabled", false));
        liveAod.setOnCheckedChangeListener((v, enabled) -> {
            if (enabled) {
                prefs.edit().putBoolean("live_aod_enabled", true).apply();
                if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 212);
                }
                PhoneLiveSchedule.syncFromActivity(this);
            } else PhoneLiveSchedule.disable(this);
            updateLiveControls();
        });
        line(liveAod);
        line(label("盘中更新逐笔成交与千档盘口。AOD、锁屏壁纸和充电屏保显示期间，休市时也按所选间隔获取盘口。", 13, MUTED));
        schedulePermission = button("允许按开盘时间自动启动", v -> {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !PhoneLiveSchedule.canSchedule(this)) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException unavailable) { toast("请在系统的「闹钟和提醒」中允许本应用"); }
            }
        });
        line(schedulePermission);
        livePowerStatus = label("", 13, MUTED);
        line(livePowerStatus);
        backgroundPermission = button("解除后台运行限制", v -> {
            toast("请在应用的电池设置中选择「不受限制」");
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException unavailable) { toast("请在系统应用设置中允许后台运行"); }
        });
        line(backgroundPermission);
        batteryPermission = button("允许未插电时持续更新", v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException unavailable) { toast("请在系统电池设置中将本应用设为「不受限制」"); }
        });
        line(batteryPermission);
        updateLiveControls();
    }

    private void updateLiveControls() {
        boolean enabled = prefs.getBoolean("live_aod_enabled", false);
        if (liveAod != null && liveAod.isChecked() != enabled) liveAod.setChecked(enabled);
        if (schedulePermission != null) schedulePermission.setVisibility(enabled && !PhoneLiveSchedule.canSchedule(this)
                ? View.VISIBLE : View.GONE);
        boolean restricted = android.os.Build.VERSION.SDK_INT >= 28
                && getSystemService(android.app.ActivityManager.class).isBackgroundRestricted();
        boolean optimized = !getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        if (backgroundPermission != null) backgroundPermission.setVisibility(enabled && restricted ? View.VISIBLE : View.GONE);
        if (batteryPermission != null) batteryPermission.setVisibility(enabled && optimized ? View.VISIBLE : View.GONE);
        if (livePowerStatus != null) {
            livePowerStatus.setVisibility(enabled && (restricted || optimized) ? View.VISIBLE : View.GONE);
            livePowerStatus.setText(restricted
                    ? "系统已限制后台运行，息屏行情可能停止更新。请解除后台限制。"
                    : "电池优化可能在未插电息屏时暂停联网。允许持续更新后，息屏显示期间耗电会增加。");
        }
    }

    private void addChartControls() {
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setContentDescription("行情图类型");
        RadioButton candles = mode(modes, "1 分钟 K 线");
        RadioButton realtime = mode(modes, "实时分时");
        modes.check("realtime".equals(prefs.getString("chart_mode", "1m")) ? realtime.getId() : candles.getId());
        modes.setOnCheckedChangeListener((group, id) -> {
            prefs.edit().putString("chart_mode", id == realtime.getId() ? "realtime" : "1m").apply();
            refreshCharts();
        });
        line(modes);
        addOverlaySwitch("显示千档挂单", "show_depth");
        addOverlaySwitch("显示 B/S 买卖标记", "show_signals");
    }

    private RadioButton mode(RadioGroup group, String title) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(title);
        button.setTextColor(INK);
        button.setMinHeight(dp(48));
        group.addView(button, new RadioGroup.LayoutParams(0, -2, 1));
        return button;
    }

    private void addOverlaySwitch(String title, String key) {
        Switch control = new Switch(this);
        control.setText(title);
        control.setTextColor(INK);
        control.setMinHeight(dp(48));
        control.setChecked(prefs.getBoolean(key, true));
        control.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean(key, enabled).apply();
            refreshCharts();
        });
        line(control);
    }

    private void refreshCharts() {
        if (canvas != null) canvas.refreshChartOptions();
        PhoneMarketWidgetProvider.updateTheme(this);
    }

    private String lockName() { return LOCK_NAMES[index(LOCK_IDS, lockTheme, 0)]; }
    private String rateName(boolean plugged) {
        return (plugged ? "充电时" : "未充电时") + " · " + PhoneChartOptions.intervalLabel(PhoneSamplingRate.value(prefs,plugged));
    }

    private void addRateControls() {
        pluggedRate = button(rateName(true), v -> chooseRate(true));
        unpluggedRate = button(rateName(false), v -> chooseRate(false));
        line(pluggedRate);
        line(unpluggedRate);
    }

    private void showWidgetSetup() {
        Intent editor = new Intent("com.samsung.systemui.lockstar.action.SETTINGS").setPackage("com.samsung.systemui.lockstar");
        Intent destination = editor.resolveActivity(getPackageManager()) == null
                ? getPackageManager().getLaunchIntentForPackage("com.samsung.android.goodlock") : editor;
        boolean installed = destination != null;
        if (!installed) {
            destination = new Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/com.samsung.android.goodlock"))
                    .setPackage("com.sec.android.app.samsungapps");
        }
        Intent target = destination;
        new AlertDialog.Builder(this).setTitle("添加息屏行情")
                .setMessage("1. 在三星设置的「锁定屏幕和息屏提醒」中开启息屏显示，按需将显示时间设为「始终」。\n\n"
                        + "2. 在锁屏编辑器点「LockStar → 息屏提醒 → 应用程序小组件」，选择「息屏 K 线 → 息屏行情」。调整大小和位置后点「完成」。不同版本的菜单可能略有差异。\n\n"
                        + "3. 按侧键熄屏，查看行情时间和图表。小组件的大小和刷新由系统控制。")
                .setPositiveButton(installed ? "打开三星自定义工具" : "前往三星应用商店", (dialog, which) -> {
                    try { startActivity(target); }
                    catch (ActivityNotFoundException | SecurityException unavailable) {
                        toast("请在 Galaxy Store 搜索 Good Lock（好锁），然后打开 LockStar");
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private void chooseTheme() {
        new AlertDialog.Builder(this).setTitle("锁屏壁纸与充电屏保主题")
                .setSingleChoiceItems(LOCK_NAMES, index(LOCK_IDS, lockTheme, 0), (dialog, which) -> {
                    lockTheme = LOCK_IDS[which];
                    prefs.edit().putInt("lock_theme", lockTheme).apply();
                    dialog.dismiss();
                    renderScreen();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseRate(boolean plugged) {
        String[] choices = new String[RATES.length];
        for (int i = 0; i < RATES.length; i++) choices[i] = PhoneChartOptions.intervalLabel(RATES[i]);
        new AlertDialog.Builder(this).setTitle((plugged ? "充电时" : "未充电时") + "的行情获取间隔")
                .setSingleChoiceItems(choices, index(RATES, PhoneSamplingRate.value(prefs,plugged), 2), (dialog, which) -> {
                    prefs.edit().putInt(plugged ? PhoneSamplingRate.PLUGGED : PhoneSamplingRate.UNPLUGGED, RATES[which]).apply();
                    (plugged ? pluggedRate : unpluggedRate).setText(rateName(plugged));
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void openScreen(int target) {
        screen = target;
        renderScreen();
        if (target != SETTINGS) toast("点按画面或返回键可回到设置");
    }

    @Override public void onBackPressed() {
        if (screen != SETTINGS) openScreen(SETTINGS); else super.onBackPressed();
    }

    private void changeSymbol(String next) {
        if (next.equals(symbol)) return;
        session.stop();
        saveSnapshot();
        symbol = next;
        prefs.edit().putString("symbol", symbol).apply();
        PhoneMarketWidgetProvider.settingsChanged(this, true);
        restoreSnapshot();
        restartFeed();
    }

    private void togglePause() {
        paused = !paused;
        prefs.edit().putBoolean("paused", paused).apply();
        PhoneMarketWidgetProvider.settingsChanged(this, false);
        PhoneLiveSchedule.syncFromActivity(this);
        pauseButton.setText(paused ? "继续显示" : "暂停显示");
        restartFeed();
    }

    private void restoreSnapshot() {
        snapshot = null;
        dirty = false;
        lastSave = 0;
        try {
            JSONObject saved = new JSONObject(prefs.getString("snapshot_" + symbol, "{}"));
            saved.remove("book");
            saved.remove("fills");
            if (symbol.equals(saved.optString("symbol"))) snapshot = saved;
        } catch (org.json.JSONException ignored) { }
    }

    private void saveSnapshot() {
        if (dirty && snapshot != null && symbol.equals(snapshot.optString("symbol"))) {
            JSONObject cached = new JSONObject();
            for (Iterator<String> keys = snapshot.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!"book".equals(key) && !"fills".equals(key)) {
                    try { cached.put(key, snapshot.get(key)); } catch (JSONException invalid) { return; }
                }
            }
            prefs.edit().putString("snapshot_" + symbol, cached.toString()).apply();
            dirty = false;
            lastSave = SystemClock.elapsedRealtime();
        }
    }

    private void onQuote(JSONObject data, String message) {
        if (!visible || paused) return;
        if (data != null && data != snapshot && symbol.equals(data.optString("symbol"))) {
            snapshot = data;
            dirty = true;
            PhoneMarketWidgetProvider.visibleQuote(this, data);

            if (SystemClock.elapsedRealtime() - lastSave >= 20_000) saveSnapshot();
        }
        feedStatus = message == null ? "" : message;

        updateStatus();
    }

    private void updateStatus() {
        if (status == null) return;
        String stamp = snapshot == null ? "" : snapshot.optString("quote_at", "");
        String detail = snapshot == null ? "" : snapshot.optString("name", "");
        String value = symbol + (detail.isEmpty() || detail.equals(symbol) ? "" : " · " + detail)
                + (stamp.isEmpty() ? "" : " · 数据 " + stamp)
                + "\n" + (paused ? "已暂停显示和行情请求" : feedStatus) + measuredRates;
        if (!value.contentEquals(status.getText())) status.setText(value);
    }

    private void restartFeed() {
        session.stop();
        if (canvas != null) canvas.setRefreshState(rate, paused);
        if (visible && !paused) {
            String address = prefs.getString("mcp_url", "");
            String key = address.isEmpty() ? "" : PhoneSecretStore.load(prefs);
            feedStatus = key.isEmpty() ? "公开分钟行情 · 逐笔、B/S 与千档需配置实时接口" : "正在连接实时接口";
            session.startDisplay(symbol, address, key, rate, snapshot);
        }
        updateStatus();
    }

    private void configureMcp() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), dp(8), dp(24), 0);
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("https://你的 MCP 地址");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(prefs.getString("mcp_url", ""));
        fields.addView(url);
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint(PhoneSecretStore.load(prefs).isEmpty() ? "客户端 Key" : "客户端 Key（留空保留）");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        fields.addView(key);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("实时行情接口")
                .setMessage("使用自己的 HTTPS MCP 服务读取逐笔、成交信号和千档盘口。Key 由这台手机的 Android Keystore 加密保存。")
                .setView(fields).setPositiveButton("保存", null).setNeutralButton("清除", null)
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(shown -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String address = url.getText().toString().trim();
                try {
                    URI uri = new URI(address);
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                            || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null)
                        throw new IllegalArgumentException();
                } catch (Exception error) { url.setError("请输入不含凭据或查询参数的 HTTPS 地址"); return; }
                String entered = key.getText().toString().trim();
                if (entered.isEmpty() && !address.equals(prefs.getString("mcp_url", ""))) {
                    key.setError("更换地址时，请重新输入该服务的 Key"); return;
                }
                try {
                    if (!entered.isEmpty()) PhoneSecretStore.save(prefs, entered);
                    if (PhoneSecretStore.load(prefs).isEmpty()) { key.setError("请输入客户端 Key"); return; }
                    resetConnection(address);
                    dialog.dismiss();
                } catch (Exception error) { toast("无法保存接口配置"); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    PhoneSecretStore.save(prefs, "");
                    resetConnection("");
                    dialog.dismiss();
                } catch (Exception error) { toast("无法清除接口配置"); }
            });
        });
        dialog.show();
    }

    private void resetConnection(String address) {
        session.stop();
        snapshot = null;
        dirty = false;
        SharedPreferences.Editor edit = prefs.edit();
        for (String name : prefs.getAll().keySet()) if (name.startsWith("snapshot_")) edit.remove(name);
        edit.putString("mcp_url", address).apply();
        PhoneMarketWidgetProvider.settingsChanged(this, true);
        restartFeed();
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("screen", screen);
        outState.putInt("settings_tab",settingsTab);
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        samplingRate.start();
        updateLiveControls();
        PhoneLiveSchedule.syncFromActivity(this);
        restartFeed();
        if (previewDisplay!=null) previewDisplay.show();
        ui.removeCallbacks(showRates);
        ui.post(showRates);
    }
    @Override protected void onPause() {
        visible = false;
        if (previewDisplay!=null) previewDisplay.hide();
        samplingRate.stop();
        ui.removeCallbacks(showRates);
        measuredRates = "";
        session.stop();
        saveSnapshot();
        super.onPause();
    }
    @Override protected void onDestroy() {
        session.close();
        if (previewDisplay!=null) previewDisplay.close();
        super.onDestroy();
    }
}
