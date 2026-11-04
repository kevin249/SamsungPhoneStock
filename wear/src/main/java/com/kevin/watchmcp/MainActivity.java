package com.kevin.watchmcp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.app.NotificationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.annotation.SuppressLint;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 两页原生手表应用。K 线与 MCP 均由手表直接联网。 */
@SuppressLint("SetTextI18n") // 本工具仅提供中文；行情名、消息正文和状态在运行时组合。
public class MainActivity extends Activity {
    static final int TEXT = Color.rgb(241, 245, 255);
    static final int MUTED = Color.rgb(165, 177, 198);
    static final int ACCENT = Color.rgb(139, 170, 255);
    static final int UP = Color.rgb(255, 118, 127);
    static final int DOWN = Color.rgb(86, 215, 177);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final MarketDirectClient market = new MarketDirectClient(() -> this.active && this.prefs!=null
            && this.symbol!=null && this.symbol.equals(DialMarket.symbol(this.prefs)));
    private final ExecutorService stockNetwork = Executors.newFixedThreadPool(2);
    private JSONObject stockNames = new JSONObject();
    private McpHistory history;
    private final SharedPreferences.OnSharedPreferenceChangeListener historyListener = (preferences, key) -> {
        if (McpHistory.STATE_KEY.equals(key) && this.active) handler.post(this::refreshMcp);
    };
    private SharedPreferences prefs;
    private Watchlist watchlist;
    private JSONObject snapshot = new JSONObject();
    private String symbol, period, serverUrl, token;
    private String connection = "正在连接…";
    private LinearLayout root;
    private TextView metadata, mcpStatus;
    private Button header, tabs;
    private CandleChartView chart;
    private LinearLayout notificationList;
    private int page = 0, generation = 0;
    private boolean active, fetching;
    private int swipeAxis; // 0：未识别，1：左右翻页，2：上下切股。
    private float touchX, touchY;
    private long seenCursor;
    private String renderedNotifications = "";

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            fetch();
            handler.postDelayed(this, 15000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("watch", MODE_PRIVATE);
        history = new McpHistory(prefs);
        prefs.registerOnSharedPreferenceChangeListener(historyListener);
        prefs.edit().remove("server").remove("token").apply();
        if (!prefs.getBoolean("direct_v2", false)) prefs.edit().remove("cache").remove("seen").putBoolean("direct_v2", true).apply();
        serverUrl = prefs.getString("mcp_url", "");
        token = SecretStore.load(prefs);
        watchlist = new Watchlist(prefs.getString("watchlist_symbols", null), prefs.getString("symbol", "sh600519"));
        symbol = watchlist.selected();
        try { stockNames = new JSONObject(prefs.getString("stock_names", "{}")); }
        catch (JSONException ignored) { }
        period = prefs.getString("period", "1d");
        saveSelection();
        applyProvisioning();
        seenCursor = prefs.getLong("seen", -1);
        restoreCache();
        refreshMcp();
        showPage(getIntent().getBooleanExtra("show_notifications", false) ? 1 : 0);
    }

    private void applyProvisioning() {
        // 本地输入工具将设置交给手表；Key 随后由 Keystore 加密。
        String encodedServer = getIntent().getStringExtra("mcp_url_b64");
        String encodedToken = getIntent().getStringExtra("mcp_key_b64");
        if (encodedServer == null || encodedToken == null) return;
        try {
            String address = new String(Base64.decode(encodedServer, Base64.DEFAULT), StandardCharsets.UTF_8);
            String secret = new String(Base64.decode(encodedToken, Base64.DEFAULT), StandardCharsets.UTF_8);
            if (!validAddress(address) || secret.isEmpty()) return;
            if (!address.equals(serverUrl) || !secret.equals(token)) resetIdentity();
            SecretStore.save(prefs, secret);
            serverUrl = address.replaceAll("/+$", "");
            token = secret;
            prefs.edit().putString("mcp_url", serverUrl).apply();
            getIntent().removeExtra("mcp_key_b64");
        } catch (Exception ignored) { }
    }

    private void resetIdentity() {
        stopService(new Intent(this, McpService.class));
        McpHistory.reset(prefs);
        prefs.edit().remove("seen").remove("cache").remove("mcp_seen_ids").apply();
        seenCursor = -1;
        snapshot = new JSONObject();
    }

    private void restoreCache() {
        try {
            snapshot = new JSONObject(prefs.getString("cache", "{}"));
            connection = "缓存 · 等待更新";
        } catch (JSONException ignored) { snapshot = new JSONObject(); }
    }

    @Override protected void onResume() {
        super.onResume();
        String selected = DialMarket.symbol(prefs);
        if (!selected.equals(symbol) && watchlist.symbols().contains(selected)) {
            watchlist.select(selected);
            symbol = selected;
            generation++;
            showPage(page);
        }
        active = true;
        startMcp();
        requestBackgroundPermissions(false);
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override protected void onPause() {
        active = false;
        handler.removeCallbacks(poll);
        market.cancel();
        super.onPause();
    }

    @Override protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(historyListener);
        handler.removeCallbacksAndMessages(null);
        network.shutdownNow();
        stockNetwork.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyProvisioning();
        refreshMcp();
        if (intent.getBooleanExtra("show_notifications", false)) showPage(1);
        startMcp();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextSize(12);
        view.setTextColor(TEXT);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(4), 0, dp(4), 0);
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        return view;
    }

    private void showPage(int next) {
        page = next;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));
        setContentView(root);
        header = button("");
        header.setOnClickListener(v -> showMenu());
        if (page == 0) {
            root.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
            chart = new CandleChartView(this);
            root.addView(chart, new LinearLayout.LayoutParams(-1, 0, 1));
            metadata = text("", 10, MUTED);
            metadata.setMaxLines(2);
            root.addView(metadata, new LinearLayout.LayoutParams(-1, dp(30)));
        } else {
            mcpStatus = text("", 10, MUTED);
            mcpStatus.setMaxLines(2);
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));
            content.addView(mcpStatus, new LinearLayout.LayoutParams(-1, dp(26)));
            notificationList = new LinearLayout(this);
            notificationList.setOrientation(LinearLayout.VERTICAL);
            notificationList.setPadding(dp(8), 0, dp(8), dp(8));
            content.addView(notificationList);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            renderedNotifications = "";
        }
        tabs = button("");
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(dp(128), dp(48));
        root.addView(tabs, tabParams);
        tabs.setTextColor(ACCENT);
        tabs.setOnClickListener(v -> showPage(1 - page));
        render();
    }

    private JSONObject chartData() {
        JSONObject envelope = snapshot.optJSONObject("chart");
        JSONObject data = envelope == null ? null : envelope.optJSONObject("data");
        if (symbol.isEmpty() || data == null || !period.equals(data.optString("period"))) return null;
        // 代码可能是 sh600519 或 600519，比较末尾六位。
        if (!symbol.endsWith(data.optString("symbol", "invalid"))) return null;
        return data;
    }

    private void render() {
        if (root == null) return;
        JSONObject mcp = snapshot.optJSONObject("mcp");
        JSONArray notifications = mcp == null ? new JSONArray() : mcp.optJSONArray("notifications");
        if (notifications == null) notifications = new JSONArray();
        long cursor = mcp == null ? 0 : mcp.optLong("cursor");
        // 首次连接只展示历史；之后仅新事件计未读和振动。
        if (mcp != null && mcp.optBoolean("baseline_ready") && seenCursor < 0) {
            seenCursor = cursor;
            prefs.edit().putLong("seen", seenCursor).apply();
        }
        int unread = 0;
        for (int i = 0; i < notifications.length(); i++) {
            JSONObject event = notifications.optJSONObject(i);
            if (event != null && event.optLong("id") > seenCursor) unread++;
        }
        if (page == 1) {
            if (cursor > seenCursor) {
                seenCursor = cursor;
                prefs.edit().putLong("seen", seenCursor).apply();
                DialProvider.redraw(this);
            }
            header.setText("MCP 通知 · " + notifications.length() + " 条");
            header.setContentDescription("MCP 通知，点击打开设置");
            mcpStatus.setText(mcp == null ? "请设置手表 MCP 接口" : mcp.optString("status"));
            renderNotifications(notifications);
            tabs.setText("← K 线    ○ ●");
            tabs.setContentDescription("返回 K 线，也可向右滑动");
        } else {
            JSONObject data = chartData();
            JSONArray bars = data == null ? new JSONArray() : data.optJSONArray("bars");
            if (bars == null) bars = new JSONArray();
            String title = watchlist.isEmpty() ? "暂无自选" : data == null ? symbol.substring(Math.max(0, symbol.length() - 6)) : data.optString("name", symbol);
            String label = data == null ? periodLabel() : data.optString("period_label");
            JSONObject last = bars.optJSONObject(bars.length() - 1);
            String price = last == null ? "— —" : String.format(Locale.CHINA, "%.2f", last.optDouble("close"));
            String heading = watchlist.isEmpty() ? "暂无自选\n点击添加股票" : title + " · " + label + "\n" + price;
            SpannableString styled = new SpannableString(heading);
            if (!watchlist.isEmpty()) styled.setSpan(new AbsoluteSizeSpan(23, true), heading.indexOf('\n') + 1, heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            header.setText(styled);
            header.setContentDescription(heading + "，点击打开股票列表、周期或连接设置，上滑下一只自选股，下滑上一只自选股");
            header.setOnClickListener(v -> { if (watchlist.isEmpty()) showStockList(); else showMenu(); });
            chart.setBars(bars);
            JSONObject envelope = snapshot.optJSONObject("chart");
            String error = envelope == null ? "" : envelope.optString("error");
            String stamp = last == null ? "" : last.optString("time");
            String source = data == null ? "" : data.optString("source_label") + ("qfq".equals(data.optString("adjust")) ? "·前复权" : "·不复权");
            if (watchlist.isEmpty()) {
                metadata.setText("自选已保存 · 点击顶部添加");
            } else if (!connection.isEmpty()) {
                metadata.setText(connection + (data != null ? "\n保留上次 K 线" : ""));
            } else if (!error.isEmpty()) {
                metadata.setText("取数失败 · " + (data == null ? error : "保留上次 K 线"));
            } else if (last == null) {
                metadata.setText("正在获取 K 线…");
            } else {
                String shortStamp = stamp.length() >= 16 ? stamp.substring(5, 16).replace('T', ' ') : stamp;
                metadata.setText(source + "\nK 线 " + shortStamp);
            }
            tabs.setText("● ○    MCP" + (unread > 0 ? " " + unread : "") + " →");
            tabs.setContentDescription("查看 MCP 通知，" + unread + " 条未读，也可向左滑动");
        }
    }

    private void renderNotifications(JSONArray notifications) {
        String signature = notifications.toString();
        if (signature.equals(renderedNotifications)) return;
        renderedNotifications = signature;
        notificationList.removeAllViews();
        if (notifications.length() == 0) {
            TextView empty = text("暂无通知\n收到 MCP 提醒后会显示在这里", 13, MUTED);
            empty.setPadding(0, dp(12), 0, dp(12));
            notificationList.addView(empty);
            return;
        }
        for (int i = 0; i < notifications.length(); i++) {
            JSONObject event = notifications.optJSONObject(i);
            if (event == null) continue;
            TextView card = text("", 13, TEXT);
            card.setGravity(Gravity.START);
            card.setLineSpacing(dp(2), 1.05f);
            String date = event.optString("created_at");
            String title = event.optString("title", "MCP 提醒");
            String body = event.optString("body");
            card.setText(title + "\n" + body + (date.isEmpty() ? "" : "\n" + date.replace('T', ' ')));
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.rgb(16, 23, 36));
            background.setCornerRadius(dp(12));
            card.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(8);
            notificationList.addView(card, params);
        }
    }

    private String periodLabel() {
        switch (period) {
            case "5m": return "5 分钟";
            case "1m": return "1 分钟";
            case "1w": return "周 K";
            default: return "日 K";
        }
    }

    private void fetch() {
        if (!active || watchlist.isEmpty() || fetching) return;
        fetching = true;
        final int requestedGeneration = generation;
        final String requestedSymbol = symbol, requestedPeriod = period;
        network.execute(() -> {
            JSONObject result = null;
            try { result = market.fetch(requestedSymbol, requestedPeriod); }
            catch (Exception ignored) { }
            final JSONObject data = result;
            handler.post(() -> {
                fetching = false;
                if (isDestroyed()) return;
                if (requestedGeneration != generation) { if (active) fetch(); return; }
                if (!active || !requestedSymbol.equals(DialMarket.symbol(prefs))) return;
                try {
                    if (data != null) {
                        snapshot.put("chart", new JSONObject().put("data", data).put("error", ""));
                        rememberStock(requestedSymbol, data.optString("name"));
                        connection = "";
                        saveCache();
                    } else connection = "行情暂不可用 · 保留上次 K 线";
                } catch (JSONException ignored) { }
                if (active) render();
            });
        });
    }

    private void saveCache() {
        JSONObject cache = new JSONObject();
        try { if (snapshot.has("chart")) cache.put("chart", snapshot.get("chart")); }
        catch (JSONException ignored) { }
        prefs.edit().putString("cache", cache.toString()).apply();
    }

    private void refreshMcp() {
        try { snapshot.put("mcp", history.snapshot()); }
        catch (JSONException ignored) { }
        if (active) render();
    }

    private void startMcp() {
        McpService.ensureRunning(this);
        refreshMcp();
    }

    @SuppressLint("BatteryLife") // 用户主动开启持续 MCP 提醒；服务器仅提供长连接，没有 FCM，用户自行批准电池豁免。
    private void requestBackgroundPermissions(boolean retry) {
        if (token.isEmpty() || !prefs.getBoolean(McpService.ENABLED, true)) return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (retry || !prefs.getBoolean("notification_permission_asked", false)) {
                prefs.edit().putBoolean("notification_permission_asked", true).apply();
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            }
            return;
        }
        PowerManager power = getSystemService(PowerManager.class);
        if (!power.isIgnoringBatteryOptimizations(getPackageName()) && (retry || !prefs.getBoolean("battery_permission_asked", false))) {
            prefs.edit().putBoolean("battery_permission_asked", true).apply();
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()));
            android.content.ComponentName settings = request.resolveActivity(getPackageManager());
            if (settings == null || settings.getClassName().contains("FakeSettingsActivity")) {
                new AlertDialog.Builder(this).setTitle("熄屏联网尚未允许")
                        .setMessage("此手表没有电池授权页面。请使用项目安装脚本更新应用，脚本会为本应用开启熄屏联网；只需设置一次，之后电脑无需常开。持续联网会增加耗电。")
                        .setPositiveButton("知道了", null).show();
                return;
            }
            new AlertDialog.Builder(this).setTitle("熄屏后继续提醒")
                    .setMessage("请允许后台运行，让手表熄屏后保持 MCP 连接。持续联网会增加耗电。")
                    .setPositiveButton("去允许", (dialog, which) -> {
                        try { startActivity(request); }
                        catch (RuntimeException ignored) { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                    }).setNegativeButton("稍后", null).show();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMcp();
            requestBackgroundPermissions(false);
        }
    }

    private void backgroundSettings() {
        boolean enabled = prefs.getBoolean(McpService.ENABLED, true);
        boolean allowed = getSystemService(NotificationManager.class).areNotificationsEnabled();
        boolean unrestricted = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        new AlertDialog.Builder(this).setTitle("后台通知设置")
                .setItems(new String[]{enabled ? "暂停后台接收" : "开启后台接收", "通知权限 · " + (allowed ? "已允许" : "未允许"),
                        "熄屏联网 · " + (unrestricted ? "已允许" : "去允许")}, (dialog, which) -> {
                    if (which == 0) {
                        prefs.edit().putBoolean(McpService.ENABLED, !enabled).apply();
                        if (enabled) {
                            stopService(new Intent(this, McpService.class));
                            history.status("后台接收已暂停");
                        } else { startMcp(); requestBackgroundPermissions(true); }
                        refreshMcp();
                    } else if (which == 1) {
                        startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
                    } else requestBackgroundPermissions(true);
                }).setNegativeButton("返回", null).show();
    }

    private void saveSelection() {
        boolean changed = !symbol.equals(DialMarket.symbol(prefs));
        prefs.edit().putString("watchlist_symbols", watchlist.serialize()).putString("symbol", symbol).putString("period", period).apply();
        if (changed && DialProvider.active(this)) {
            DialProvider.redraw(this);
            DialMarket.refresh(this);
        }
    }

    private void showMenu() {
        String[] options = {"股票列表", "切换 K 线周期", "MCP 接口设置", "后台通知设置", "表盘显示", "立即刷新"};
        new AlertDialog.Builder(this).setTitle("腕上盯盘").setItems(options, (dialog, which) -> {
            if (which == 0) showStockList();
            else if (which == 1) selectPeriod();
            else if (which == 2) connectionSettings();
            else if (which == 3) backgroundSettings();
            else if (which == 4) dialSettings();
            else { connection = "正在刷新…"; render(); fetch(); }
        }).show();
    }

    private void dialSettings() {
        new AlertDialog.Builder(this).setTitle("表盘显示")
                .setMessage("长按当前表盘，点「自定义」调整实时分时或分钟蜡烛、千档挂单和 MCP 买卖标记。\n\n在表盘上双击图表，可轮换自选股票。")
                .setPositiveButton("知道了",null).show();
    }

    private String stockName(String code) {
        return stockNames.optString(code, "待获取名称");
    }

    private void rememberStock(String code, String name) {
        if (name == null || name.isEmpty() || name.equals(code) || name.equals(code.substring(2))) return;
        try { stockNames.put(code, name); }
        catch (JSONException ignored) { }
        prefs.edit().putString("stock_names", stockNames.toString()).apply();
    }

    private LinearLayout stockButton(String code, String name, boolean selected) {
        LinearLayout view = new LinearLayout(this) {
            @Override public CharSequence getAccessibilityClassName() { return Button.class.getName(); }
        };
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(8), dp(8), dp(4), dp(8));
        view.setBackgroundResource(android.R.drawable.list_selector_background);
        view.setFocusable(true);
        TextView title = text(name, 14, selected ? ACCENT : TEXT);
        TextView detail = text(code.substring(2) + (selected ? " ✓" : ""), 12, MUTED);
        for (TextView line : new TextView[]{title, detail}) {
            line.setGravity(Gravity.START);
            line.setSingleLine(true);
            line.setEllipsize(TextUtils.TruncateAt.END);
            line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(line, new LinearLayout.LayoutParams(-1, -2));
        }
        view.setContentDescription(name + "，" + code.substring(2) + (selected ? "，当前股票" : "，点击切换股票"));
        return view;
    }

    private void showStockList() {
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(16), dp(4), dp(8), 0);
        TextView title = text("股票列表", 16, TEXT);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("+");
        add.setTextSize(26);
        add.setTextColor(ACCENT);
        add.setContentDescription("添加股票");
        heading.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(16), 0, dp(16), dp(8));
        scroll.addView(rows);
        AlertDialog dialog = new AlertDialog.Builder(this).setCustomTitle(heading).setView(scroll)
                .setNegativeButton("返回", null).create();
        add.setOnClickListener(v -> { dialog.dismiss(); addStock(); });
        populateStocks(rows, title, dialog);
        dialog.show();
        List<Future<?>> nameRequests = new java.util.ArrayList<>();
        dialog.setOnDismissListener(d -> {
            for (Future<?> request : nameRequests) request.cancel(true);
        });
        // 升级前仅保存代码；在后台补齐名称，并保存在本地供离线使用。
        for (String code : watchlist.symbols()) {
            if (stockNames.has(code)) continue;
            nameRequests.add(stockNetwork.submit(() -> {
                try {
                    List<StockSearchClient.Stock> found = new StockSearchClient().search(code);
                    handler.post(() -> {
                        if (isDestroyed()) return;
                        for (StockSearchClient.Stock stock : found) rememberStock(stock.symbol, stock.name);
                        if (dialog.isShowing()) populateStocks(rows, title, dialog);
                    });
                } catch (Exception ignored) { }
            }));
        }
    }

    private void populateStocks(LinearLayout rows, TextView title, AlertDialog dialog) {
        rows.removeAllViews();
        title.setText("股票列表 · " + watchlist.symbols().size());
        if (watchlist.isEmpty()) {
            TextView empty = text("暂无股票\n点击顶部 + 添加", 13, MUTED);
            empty.setPadding(0, dp(16), 0, dp(16));
            rows.addView(empty);
        }
        for (String code : watchlist.symbols()) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout choose = stockButton(code, stockName(code), code.equals(symbol));
            choose.setOnClickListener(v -> {
                watchlist.select(code);
                symbol = watchlist.selected();
                dialog.dismiss();
                selectionChanged();
            });
            row.addView(choose, new LinearLayout.LayoutParams(0, -2, 1));
            Button up = button("↑");
            up.setTextSize(22);
            up.setContentDescription("上移自选：" + stockName(code) + "，" + code.substring(2)
                    + (watchlist.symbols().indexOf(code) == 0 ? "，移到列表末尾" : "，向上移动一位"));
            up.setOnClickListener(v -> {
                watchlist.moveUp(code);
                saveSelection();
                populateStocks(rows, title, dialog);
            });
            LinearLayout.LayoutParams upParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            upParams.leftMargin = dp(4);
            row.addView(up, upParams);
            Button remove = button("−");
            remove.setTextSize(24);
            remove.setContentDescription("删除自选：" + stockName(code) + "，" + code.substring(2));
            remove.setOnClickListener(v -> {
                watchlist.remove(code);
                watchlistChanged();
                populateStocks(rows, title, dialog);
            });
            LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(48), dp(48));
            removeParams.leftMargin = dp(4);
            row.addView(remove, removeParams);
            rows.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void addStock() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), 0, dp(16), dp(8));
        TextView label = text("代码 / 名称 / 拼音 / 首字母", 12, MUTED);
        form.addView(label);
        EditText input = new EditText(this);
        input.setTextSize(14);
        input.setTextColor(TEXT);
        input.setSingleLine(true);
        input.setMinHeight(dp(48));
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setHint("如 600519 / 茅台 / maotai / gzmt");
        input.setContentDescription("输入股票代码、名称、拼音或拼音首字母");
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));
        TextView status = text("输入后点击搜索结果添加", 12, MUTED);
        status.setPadding(0, dp(4), 0, dp(4));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(status);
        ScrollView scroll = new ScrollView(this);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(results);
        form.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(16), dp(4), dp(8), 0);
        TextView title = text("添加股票", 16, TEXT);
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button back = button("返回");
        back.setContentDescription("返回股票列表");
        heading.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        AlertDialog dialog = new AlertDialog.Builder(this).setCustomTitle(heading).setView(form).create();
        back.setOnClickListener(v -> { dialog.dismiss(); showStockList(); });
        final int[] revision = {0};
        final Runnable[] pending = {null};
        final Future<?>[] request = {null};
        Runnable search = () -> {
            final int version = ++revision[0];
            if (pending[0] != null) handler.removeCallbacks(pending[0]);
            if (request[0] != null) request[0].cancel(true);
            String query = input.getText().toString().trim();
            results.removeAllViews();
            if (query.isEmpty()) { status.setText("输入后点击搜索结果添加"); return; }
            status.setText("正在搜索…");
            pending[0] = () -> request[0] = stockNetwork.submit(() -> {
                List<StockSearchClient.Stock> found;
                String error = "";
                try { found = new StockSearchClient().search(query); }
                catch (Exception failure) { found = java.util.Collections.emptyList(); error = "搜索暂不可用，请重试"; }
                final List<StockSearchClient.Stock> stocks = found;
                final String message = error;
                handler.post(() -> {
                    if (isDestroyed() || !dialog.isShowing() || version != revision[0]) return;
                    results.removeAllViews();
                    status.setText(!message.isEmpty() ? message : stocks.isEmpty() ? "未找到股票，请换个关键词" : "点击结果添加股票");
                    for (StockSearchClient.Stock stock : stocks) {
                        rememberStock(stock.symbol, stock.name);
                        addSearchResult(results, stock.symbol, stock.name, input, dialog);
                    }
                    String code = Watchlist.normalize(query);
                    if (stocks.isEmpty() && code != null) {
                        status.setText("暂未查到名称，可按代码添加");
                        addSearchResult(results, code, stockName(code), input, dialog);
                    }
                });
            });
            handler.postDelayed(pending[0], 350);
        };
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { search.run(); }
            @Override public void afterTextChanged(Editable value) { }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search.run();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
            input.clearFocus();
            return true;
        });
        dialog.setOnCancelListener(d -> showStockList());
        dialog.setOnDismissListener(d -> {
            revision[0]++;
            if (pending[0] != null) handler.removeCallbacks(pending[0]);
            if (request[0] != null) request[0].cancel(true);
        });
        dialog.show();
        // 紧凑标题与返回入口，为圆屏上的搜索结果留出空间。
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
    }

    private void addSearchResult(LinearLayout results, String code, String name, EditText input, AlertDialog dialog) {
        boolean exists = watchlist.symbols().contains(code);
        LinearLayout result = stockButton(code, name, false);
        result.setContentDescription(name + "，" + code.substring(2) + (exists ? "，已在自选中" : "，点击添加股票"));
        if (exists) {
            result.setEnabled(false);
            result.setAlpha(0.5f);
            ((TextView) result.getChildAt(1)).setText(code.substring(2) + " · 已添加");
        }
        result.setOnClickListener(v -> {
            try { watchlist.add(code); }
            catch (IllegalArgumentException error) { input.setError(error.getMessage()); return; }
            rememberStock(code, name.equals("待获取名称") ? "" : name);
            watchlistChanged();
            dialog.dismiss();
            showStockList();
        });
        results.addView(result, new LinearLayout.LayoutParams(-1, -2));
    }

    private void watchlistChanged() {
        String previous = symbol;
        symbol = watchlist.selected();
        if (!symbol.equals(previous)) {
            // 让旧请求失效；删空时也清理旧 K 线，避免重启后显示已删除股票。
            snapshot.remove("chart");
            saveCache();
            selectionChanged();
        } else {
            saveSelection();
            render();
        }
    }

    private void selectPeriod() {
        String[] values = {"1d", "5m", "1m", "1w"};
        new AlertDialog.Builder(this).setTitle("K 线周期").setItems(new String[]{"日 K", "5 分钟", "1 分钟", "周 K"}, (dialog, which) -> {
            period = values[which]; selectionChanged();
        }).show();
    }

    private void selectionChanged() {
        generation++;
        saveSelection();
        connection = "正在切换…";
        showPage(0);
        fetch();
    }

    private static boolean validAddress(String address) {
        try {
            URI uri = new URI(address);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (Exception ignored) { return false; }
    }

    @SuppressLint("WearPasswordInput") // 用户明确要求在手表管理自己的 MCP API Key，非账号登录。
    private void connectionSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(16), dp(8), dp(16), dp(16));
        TextView addressLabel = text("MCP URL", 13, MUTED);
        form.addView(addressLabel);
        EditText address = new EditText(this);
        address.setText(serverUrl);
        address.setHint("https://服务器/mcp");
        address.setTextSize(12);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setContentDescription("手表 MCP URL");
        form.addView(address);
        form.addView(text("客户端 Key", 13, MUTED));
        EditText secret = new EditText(this);
        secret.setText(token);
        secret.setTextSize(12);
        secret.setSingleLine(true);
        secret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setContentDescription("MCP 客户端 Key");
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        form.addView(secret);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("手表 MCP 接口").setView(scroll).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = address.getText().toString().trim().replaceAll("/+$", "");
            if (!validAddress(value)) { address.setError("请输入完整 http(s) 地址"); return; }
            String newKey = secret.getText().toString().trim();
            if (newKey.isEmpty()) { secret.setError("请输入客户端 Key"); return; }
            try { SecretStore.save(prefs, newKey); }
            catch (Exception error) { secret.setError("保存 Key 失败，请重试"); return; }
            if (!value.equals(serverUrl) || !newKey.equals(token)) resetIdentity();
            serverUrl = value;
            token = newKey;
            prefs.edit().putString("mcp_url", serverUrl).apply();
            generation++;
            connection = "正在连接…";
            dialog.dismiss();
            render();
            fetch();
            startMcp();
            requestBackgroundPermissions(false);
        }));
        dialog.show();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            touchX = event.getX(); touchY = event.getY(); swipeAxis = 0;
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = event.getX() - touchX, dy = event.getY() - touchY;
            // 让出左边缘保留系统返回；锁定方向，通知页的竖向手势继续滚动。
            if (swipeAxis == 0 && touchX > dp(16)) {
                if (Math.abs(dx) > dp(32) && Math.abs(dx) > Math.abs(dy) * 1.5f) swipeAxis = 1;
                else if (page == 0 && watchlist.symbols().size() > 1
                        && Math.abs(dy) > dp(32) && Math.abs(dy) > Math.abs(dx) * 1.5f) swipeAxis = 2;
                if (swipeAxis != 0) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
            }
            if (swipeAxis != 0) return true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP && swipeAxis != 0) {
            int axis = swipeAxis;
            swipeAxis = 0;
            float dx = event.getX() - touchX, dy = event.getY() - touchY;
            if (axis == 1) {
                if (dx < -dp(32) && page == 0) showPage(1);
                else if (dx > dp(32) && page == 1) showPage(0);
            } else if (page == 0 && Math.abs(dy) > dp(32) && watchlist.move(dy < 0 ? 1 : -1)) {
                symbol = watchlist.selected();
                selectionChanged();
            }
            return true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) swipeAxis = 0;
        return super.dispatchTouchEvent(event);
    }

    @Override public void onBackPressed() {
        if (page == 1) showPage(0);
        else super.onBackPressed();
    }
}
