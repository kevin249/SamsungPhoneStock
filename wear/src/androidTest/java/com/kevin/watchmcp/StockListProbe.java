package com.kevin.watchmcp;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** 通过无障碍节点验证实际弹窗、搜索、添加、切换、删除和返回；仅临时添加一只股票。 */
final class StockListProbe {
    private final Instrumentation test;
    StockListProbe(Instrumentation test) { this.test = test; }

    void run() throws Exception {
        SharedPreferences prefs = test.getTargetContext().getSharedPreferences("watch", 0);
        String before = prefs.getString("watchlist_symbols", "");
        String selected = prefs.getString("symbol", "");
        String code = before.contains("sh600036") ? "sz002594" : "sh600036";
        if (before.contains(code)) throw new AssertionError("Test candidates already in watchlist");
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            Method show = MainActivity.class.getDeclaredMethod("showStockList");
            show.setAccessible(true);
            test.runOnMainSync(() -> {
                try { show.invoke(activity); } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            clickDescription("添加股票");
            AccessibilityNodeInfo input = waitFor("android.widget.EditText", null, null);
            Bundle value = new Bundle();
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code.substring(2));
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value)) throw new AssertionError("Cannot enter query");
            AccessibilityNodeInfo result = waitFor(null, null, "，" + code.substring(2) + "，点击添加股票");
            shot("search");
            if (!result.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Cannot add result");
            waitFor(null, null, "删除自选：");
            String after = prefs.getString("watchlist_symbols", "");
            if (!after.equals(before + (before.isEmpty() ? "" : "\n") + code)) throw new AssertionError("Add did not persist");
            String selectionAfterAdd = prefs.getString("symbol", "");
            String[] order = after.split("\n");
            for (int step = 0; step < order.length; step++) {
                clickDescription("上移自选：" + (code.equals("sh600036") ? "招商银行" : "比亚迪") + "，" + code.substring(2));
                SystemClock.sleep(150);
                String[] actual = prefs.getString("watchlist_symbols", "").split("\n");
                int expected = step == order.length - 1 ? order.length - 1 : order.length - 2 - step;
                if (!actual[expected].equals(code)) throw new AssertionError("Move up/wrap failed");
                if (!selectionAfterAdd.equals(prefs.getString("symbol", ""))) throw new AssertionError("Reorder changed selection");
            }
            waitPreference(prefs, "watchlist_symbols", after);
            shot("list");
            clickDescription("，" + code.substring(2) + "，点击切换股票");
            waitPreference(prefs, "symbol", code);
            test.runOnMainSync(() -> {
                try { show.invoke(activity); } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            AccessibilityNodeInfo remove = findRemoval(code.substring(2));
            if (!remove.performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Cannot remove");
            waitPreference(prefs, "watchlist_symbols", before);
            if (prefs.getString("symbol", "").equals(code)) throw new AssertionError("Deleted selection remains active");
            clickDescription("添加股票");
            waitFor("android.widget.EditText", null, null);
            clickText("返回");
            waitFor(null, null, "添加股票");
        } finally {
            // 仅恢复本测试修改的列表/选择，不碰 MCP、通知历史及用户其他设置。
            prefs.edit().putString("watchlist_symbols", before).putString("symbol", selected).commit();
            test.runOnMainSync(activity::finish);
        }
    }

    private AccessibilityNodeInfo findRemoval(String code) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo node = find(root, null, null, "删除自选：", code);
            if (node != null) return node;
            // 自选较多时让列表滚到末尾，本次添加的股票位于末尾。
            AccessibilityNodeInfo scroll = find(root, "android.widget.ScrollView", null, null, null);
            if (scroll != null) scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            SystemClock.sleep(150);
        }
        throw new AssertionError("Missing removal button");
    }

    private void waitPreference(SharedPreferences prefs, String key, String value) {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!value.equals(prefs.getString(key, "")) && SystemClock.elapsedRealtime() < end) SystemClock.sleep(100);
        if (!value.equals(prefs.getString(key, ""))) throw new AssertionError("Preference mismatch: " + key);
    }

    private void clickDescription(String description) throws Exception {
        if (!waitFor(null, null, description).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Click failed: " + description);
    }

    private void clickText(String text) throws Exception {
        if (!waitFor(null, text, null).performAction(AccessibilityNodeInfo.ACTION_CLICK)) throw new AssertionError("Click failed: " + text);
    }

    private AccessibilityNodeInfo waitFor(String type, String text, String description) throws Exception {
        long end = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < end) {
            AccessibilityNodeInfo node = find(test.getUiAutomation().getRootInActiveWindow(), type, text, description, null);
            if (node != null) return node;
            SystemClock.sleep(100);
        }
        throw new AssertionError("Missing UI: " + type + " / " + text + " / " + description);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo root, String type, String text, String description, String extra) {
        if (root == null) return null;
        String desc = String.valueOf(root.getContentDescription());
        if ((type == null || type.contentEquals(root.getClassName()))
                && (text == null || text.equals(String.valueOf(root.getText())))
                && (description == null || desc.contains(description)) && (extra == null || desc.contains(extra))
                && root.isVisibleToUser()) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = find(root.getChild(i), type, text, description, extra);
            if (child != null) return child;
        }
        return null;
    }

    private void shot(String name) throws Exception {
        Bitmap image = test.getUiAutomation().takeScreenshot();
        try (FileOutputStream out = new FileOutputStream(new File(test.getTargetContext().getFilesDir(), "stock-ui-" + name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }
}
