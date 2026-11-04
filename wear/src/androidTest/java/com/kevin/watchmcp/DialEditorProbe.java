package com.kevin.watchmcp;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** 由测试者先进入三星系统自定义页面。测试仅滚动原生选项，退出前恢复选值。 */
final class DialEditorProbe {
    private final UiAutomation ui;
    DialEditorProbe(Instrumentation test) { ui=test.getUiAutomation(); }

    private AccessibilityNodeInfo node(String id) {
        AccessibilityNodeInfo root=ui.getRootInActiveWindow();
        if (root==null) throw new AssertionError("编辑器窗口不可见");
        List<AccessibilityNodeInfo> items=root.findAccessibilityNodeInfosByViewId("com.samsung.wear.watchface.runtime:id/"+id);
        if (items.isEmpty()) throw new AssertionError("系统页面没有 "+id);
        return items.get(0);
    }
    private String value() { return String.valueOf(node("preview_layout").getContentDescription()); }
    private void rotate(int offset) throws Exception {
        try (ParcelFileDescriptor descriptor=ui.executeShellCommand("input rotaryencoder scroll --axis SCROLL,"+offset);
             java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            while (in.read()!=-1) { }
        }
        SystemClock.sleep(650);
    }
    private void page(boolean forward) {
        int action=forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        if (!node("pager").performAction(action)) throw new AssertionError("原生编辑器不支持翻页");
        SystemClock.sleep(850);
    }
    void selectDefaults() throws Exception {
        String[] desired={"实时分时","显示","显示"};
        for (int i=0;i<desired.length;i++) {
            if (i>0) page(true);
            if (!desired[i].equals(value())) rotate(1);
            if (!desired[i].equals(value())) throw new AssertionError("默认选项未选中 "+i+": "+value());
        }
    }

    void run() throws Exception {
        String original=value();
        if (!"实时分时".equals(original)) throw new AssertionError("测试需要默认分时，当前 "+original);
        rotate(-1);
        try { if (!"1 分钟蜡烛图".equals(value())) throw new AssertionError("未能切换蜡烛图"); }
        finally { rotate(1); }
        for (int i=0;i<2;i++) {
            page(true);
            if (!"显示".equals(value())) throw new AssertionError("图层开关初始值 "+value());
            rotate(-1);
            try { if (!"隐藏".equals(value())) throw new AssertionError("图层无法关闭 "+i); }
            finally { rotate(1); }
        }
        page(false); page(false);
        if (!original.equals(value())) throw new AssertionError("未恢复分时");
    }
}
