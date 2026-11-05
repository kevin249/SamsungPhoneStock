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
    private TextView status;
    private Button lockChoice, pluggedRate, unpluggedRate, pauseButton;
    private Switch liveAod;
    private Button schedulePermission;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("phone_dial", Context.MODE_PRIVATE);
        symbol = StockSearch.normalize(prefs.getString("symbol", "sz000063"));
        if (symbol == null) symbol = "sz000063";
        lockTheme = LOCK_IDS[index(LOCK_IDS, prefs.getInt("lock_theme", PhoneCandleView.LOCK_BLACK), 0)];
        paused = prefs.getBoolean("paused", false);
        screen = state == null ? SETTINGS : state.getInt("screen", SETTINGS);
        if (screen != LOCK_PREVIEW && screen != DREAM_PREVIEW) screen = SETTINGS;
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
            canvas = new PhoneCandleView(this);
            canvas.setSurface(screen == LOCK_PREVIEW ? PhoneCandleView.Surface.LOCK : PhoneCandleView.Surface.DREAM);
            canvas.setTheme(lockTheme);
            canvas.setMarket(snapshot, symbol);
            canvas.setRefreshState(rate, paused);
            canvas.setFeedStatus(paused ? "已暂停" : feedStatus);
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
        status = label("", 14, MUTED);
        status.setPadding(0, dp(6), 0, 0);
        line(status);
        updateStatus();

        buildMarketSettings();
        heading("三星息屏行情（AOD）");
        addLiveControl();
        line(button("添加到息屏显示", v -> showWidgetSetup()));
        line(label("熄屏后，由三星息屏显示在系统时钟下方展示行情小组件。", 13, MUTED));

        heading("锁屏动态壁纸");
        lockChoice = button("共用主题 · " + lockName(), v -> chooseTheme());
        line(lockChoice);
        pair(button("预览锁屏", v -> openScreen(LOCK_PREVIEW)), button("设置系统壁纸", v -> {
            if (!PhoneWallpaperService.openPicker(this)) toast("这台设备没有提供动态壁纸选择器");
        }));
        line(label("选择主题后，在系统壁纸预览中应用到锁屏。亮屏锁定时由系统显示时钟。", 13, MUTED));
        heading("充电屏保");
        pair(button("预览充电屏保", v -> openScreen(DREAM_PREVIEW)), button("充电屏保设置", v -> {
            if (!PhoneMarketDreamService.openSettings(this)) toast("这台设备没有提供系统屏保设置入口");
        }));
        line(label("屏保沿用上方的锁屏主题，插电闲置至屏幕超时后显示行情和一个时钟。", 13, MUTED));
        line(label("三星「日常程序」的充电规则会在插电时关闭 AOD，拔线后自动恢复。", 13, MUTED));
        panel.requestFocus();
    }

    private void buildMarketSettings() {
        heading("行情设置");
        line(new PhoneStockPicker(this, symbol, this::changeSymbol));
        addChartControls();
        addRateControls();
        pauseButton = button(paused ? "继续显示" : "暂停显示", v -> togglePause());
        pair(pauseButton,button("配置实时接口", v -> configureMcp()));
    }

    private void addLiveControl() {
        liveAod = new Switch(this);
        liveAod.setText("盘中持续更新息屏行情");
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
        line(label("交易时段按所选间隔请求逐笔成交与千档盘口。锁屏壁纸和充电屏保显示期间，休市时也按所选间隔获取盘口。", 13, MUTED));
        schedulePermission = button("允许按开盘时间自动启动", v -> {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !PhoneLiveSchedule.canSchedule(this)) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (ActivityNotFoundException unavailable) { toast("请在系统的「闹钟和提醒」中允许本应用"); }
            }
        });
        line(schedulePermission);
        updateLiveControls();
    }

    private void updateLiveControls() {
        boolean enabled = prefs.getBoolean("live_aod_enabled", false);
        if (liveAod != null && liveAod.isChecked() != enabled) liveAod.setChecked(enabled);
        if (schedulePermission != null) schedulePermission.setVisibility(enabled && !PhoneLiveSchedule.canSchedule(this)
                ? View.VISIBLE : View.GONE);
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
                    lockChoice.setText("共用主题 · " + lockName());
                    dialog.dismiss();
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
            if (canvas != null) canvas.setMarket(data, symbol);
            if (SystemClock.elapsedRealtime() - lastSave >= 20_000) saveSnapshot();
        }
        feedStatus = message == null ? "" : message;
        if (canvas != null) canvas.setFeedStatus(feedStatus);
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
    }
    @Override protected void onResume() {
        super.onResume();
        visible = true;
        samplingRate.start();
        updateLiveControls();
        PhoneLiveSchedule.syncFromActivity(this);
        restartFeed();
        ui.removeCallbacks(showRates);
        ui.post(showRates);
    }
    @Override protected void onPause() {
        visible = false;
        samplingRate.stop();
        ui.removeCallbacks(showRates);
        measuredRates = "";
        session.stop();
        saveSnapshot();
        super.onPause();
    }
    @Override protected void onDestroy() {
        session.close();
        super.onDestroy();
    }
}
