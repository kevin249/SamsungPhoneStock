package com.kevin.phonekline;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.service.dreams.DreamService;
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
        setScreenBright(false);
        display = new PhoneSystemDisplay(this, false, () -> display.view.invalidate());
        setContentView(display.view);
    }

    @Override public void onDreamingStarted() {
        super.onDreamingStarted();
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
