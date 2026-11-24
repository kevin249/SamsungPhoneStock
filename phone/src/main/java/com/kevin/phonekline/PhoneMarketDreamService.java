package com.kevin.phonekline;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.view.WindowManager;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** A normal, non-interactive Android charging screensaver. */
public final class PhoneMarketDreamService extends DreamService {
    private PhoneSystemDisplay display;

    @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        super.dump(fd,out,args);
        if (display != null) display.dumpMarket(out);
    }

    public static boolean openSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            return false;
        }
    }

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        // Dimming and keeping the dream alive are separate concerns. Disabling
        // screenBright also removes KEEP_SCREEN_ON and permits idle dimming.
        setScreenBright(true);
        display = new PhoneSystemDisplay(this, false, () -> display.view.invalidate());
        setContentView(display.contentView());
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
        // DreamService's window-attribute callback is a no-op, so submit the
        // brightness to WindowManager explicitly once the decor is attached.
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.screenBrightness = 0.18f;
        getWindow().setAttributes(window);
        getWindow().getWindowManager().updateViewLayout(getWindow().getDecorView(),window);
        if (display != null) display.show();
    }

    @Override public void onDreamingStopped() {
        if (display != null) display.hide();
        super.onDreamingStopped();
    }

    @Override public void onDetachedFromWindow() {
        if (display != null) display.close();
        display = null;
        super.onDetachedFromWindow();
    }
}
