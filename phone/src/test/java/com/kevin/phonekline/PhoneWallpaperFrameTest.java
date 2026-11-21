package com.kevin.phonekline;

import org.junit.Test;

import static org.junit.Assert.*;

public class PhoneWallpaperFrameTest {
    @Test public void keepWidgetCoveredWhileTheRetainedChartIsBeingCleared() {
        PhoneWallpaperService.WallpaperFrame frame = new PhoneWallpaperService.WallpaperFrame();
        frame.marketPosted();

        assertFalse(frame.clearForAod(() -> false));
        assertTrue(frame.hasMarket());

        assertTrue(frame.clearForAod(() -> true));
        assertFalse(frame.hasMarket());
        assertTrue(frame.clearForAod(() -> {
            throw new AssertionError("The wallpaper is already clear");
        }));
    }

    @Test public void allowWidgetWhenTheSurfaceWasDestroyed() {
        PhoneWallpaperService.WallpaperFrame frame = new PhoneWallpaperService.WallpaperFrame();
        frame.marketPosted();
        frame.surfaceLost();

        assertTrue(frame.clearForAod(() -> {
            throw new AssertionError("A destroyed surface cannot be painted");
        }));
    }

    @Test public void boundRetriesAndAllowClearingAgainAfterWake() {
        PhoneWallpaperService.WallpaperFrame frame = new PhoneWallpaperService.WallpaperFrame();
        frame.marketPosted();

        assertFalse(frame.clearForAod(() -> false));
        assertFalse(frame.clearForAod(() -> false));
        assertTrue(frame.clearForAod(() -> false));

        frame.marketPosted();
        assertTrue(frame.clearForAod(() -> true));
        assertFalse(frame.hasMarket());
    }
}
