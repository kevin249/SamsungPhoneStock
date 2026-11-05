package com.kevin.phonekline;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/** The picker, not this service, selects the system surfaces on which the wallpaper appears. */
public final class PhoneWallpaperService extends WallpaperService {
    private final List<MarketEngine> engines = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private DisplayManager displays;
    private KeyguardManager keyguard;
    private PowerManager power;
    private boolean screenOff;
    private final Runnable checkWidget = this::syncWidget;
    private final DisplayManager.DisplayListener displayEvents = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { queueWidgetCheck(); }
        @Override public void onDisplayRemoved(int id) { queueWidgetCheck(); }
        @Override public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) queueWidgetCheck();
        }
    };
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            screenOff = Intent.ACTION_SCREEN_OFF.equals(action);
            if (screenOff || Intent.ACTION_USER_PRESENT.equals(action)) {
                PhoneMarketWidgetProvider.setWallpaperCover(PhoneWallpaperService.this, false);
            }
            queueWidgetCheck();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        displays = getSystemService(DisplayManager.class);
        power = getSystemService(PowerManager.class);
        keyguard = getSystemService(KeyguardManager.class);
        displays.registerDisplayListener(displayEvents, main);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        // These protected broadcasts are emitted by the system.
        registerReceiver(screenEvents, filter);
        queueWidgetCheck();
    }

    @Override public void onDestroy() {
        unregisterReceiver(screenEvents);
        displays.unregisterDisplayListener(displayEvents);
        main.removeCallbacks(checkWidget);
        engines.clear();
        PhoneMarketWidgetProvider.setWallpaperCover(this, false);
        super.onDestroy();
    }

    private void queueWidgetCheck() {
        main.removeCallbacks(checkWidget);
        main.post(checkWidget);
    }

    private void syncWidget() {
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        boolean lockWallpaperVisible = false;
        for (MarketEngine engine : engines) {
            engine.syncFeed();
            lockWallpaperVisible |= engine.showsLockWallpaper();
        }
        boolean covered = shouldCoverWidget(lockWallpaperVisible, !screenOff && power.isInteractive(),
                display == null ? Display.STATE_UNKNOWN : display.getState(), keyguard.isKeyguardLocked());
        PhoneMarketWidgetProvider.setWallpaperCover(this, covered);
    }

    static boolean shouldCoverWidget(boolean lockWallpaperVisible, boolean interactive, int displayState,
                                     boolean keyguardLocked) {
        return lockWallpaperVisible && interactive && displayState == Display.STATE_ON && keyguardLocked;
    }

    public static boolean openPicker(Activity activity) {
        WallpaperManager manager = WallpaperManager.getInstance(activity);
        if (manager == null || !manager.isWallpaperSupported() || !manager.isSetWallpaperAllowed()) return false;
        Intent preview = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        new ComponentName(activity, PhoneWallpaperService.class));
        try {
            activity.startActivity(preview);
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            try {
                activity.startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
                return true;
            } catch (ActivityNotFoundException | SecurityException noPicker) {
                return false;
            }
        }
    }

    @Override public Engine onCreateEngine() { return new MarketEngine(); }

    @Override protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        super.dump(fd,out,args);
        for (MarketEngine engine : engines) {
            if (engine.display != null) engine.display.dumpMarket(out);
        }
    }

    private final class MarketEngine extends Engine {
        private PhoneSystemDisplay display;
        private Display targetDisplay;
        private boolean visible, surfaceReady, framePosted, defaultDisplay;

        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            setOffsetNotificationsEnabled(false);
            Context context = Build.VERSION.SDK_INT >= 29 ? getDisplayContext() : PhoneWallpaperService.this;
            // Engine-specific display contexts are available from Android 10.
            targetDisplay = Build.VERSION.SDK_INT >= 30 ? context.getDisplay()
                    : context.getSystemService(WindowManager.class).getDefaultDisplay();
            defaultDisplay = targetDisplay != null && targetDisplay.getDisplayId() == Display.DEFAULT_DISPLAY;
            engines.add(this);
            display = new PhoneSystemDisplay(context, true, this::paint);
        }

        private boolean showsLockWallpaper() {
            if (!visible || !surfaceReady || !framePosted || !defaultDisplay || isPreview()) return false;
            if (Build.VERSION.SDK_INT >= 34) return (getWallpaperFlags() & WallpaperManager.FLAG_LOCK) != 0;
            // Before separate live lock wallpapers, the lock screen inherits the visible system
            // wallpaper only when no dedicated lock-screen image has been configured.
            return WallpaperManager.getInstance(PhoneWallpaperService.this).getWallpaperId(WallpaperManager.FLAG_LOCK) < 0;
        }

        @Override public void onWallpaperFlagsChanged(int which) {
            queueWidgetCheck();
        }

        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            this.visible = visible;
            syncVisibility();
        }

        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            surfaceReady = width > 0 && height > 0;
            syncVisibility();
        }

        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            if (visible && surfaceReady) paint();
        }

        @Override public void onSurfaceDestroyed(SurfaceHolder holder) {
            surfaceReady = false;
            syncVisibility();
            super.onSurfaceDestroyed(holder);
        }

        @Override public void onDestroy() {
            surfaceReady = false;
            engines.remove(this);
            queueWidgetCheck();
            if (display != null) display.close();
            super.onDestroy();
        }

        private void syncVisibility() {
            syncFeed();
            queueWidgetCheck();
        }

        private void syncFeed() {
            if (display == null) return;
            boolean awake = power.isInteractive() && !screenOff && targetDisplay != null
                    && targetDisplay.getState() == Display.STATE_ON;
            if (visible && surfaceReady && awake) display.show();
            else {
                framePosted = false;
                display.hide();
            }
        }

        private void paint() {
            if (!visible || !surfaceReady || display == null) return;
            SurfaceHolder holder = getSurfaceHolder();
            if (!holder.getSurface().isValid()) return;
            Canvas canvas;
            try {
                canvas = holder.lockCanvas();
            } catch (IllegalStateException | IllegalArgumentException surfaceGone) {
                return;
            }
            if (canvas == null) return;
            boolean drawn = false;
            try {
                int width = canvas.getWidth(), height = canvas.getHeight();
                View face = display.view;
                if (face.getWidth() != width || face.getHeight() != height) {
                    face.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    face.layout(0, 0, width, height);
                }
                face.draw(canvas);
                drawn = true;
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas);
                    if (drawn && !framePosted) {
                        framePosted = true;
                        queueWidgetCheck();
                    }
                } catch (IllegalArgumentException | IllegalStateException surfaceReplaced) {
                    // The host can replace the underlying surface during a display or preview switch.
                }
            }
        }
    }
}
