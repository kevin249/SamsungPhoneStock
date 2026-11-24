package com.kevin.phonekline;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/** Exact row sizes in the caller's coordinate system, shared by the widget and the wallpaper. */
final class PhoneStockLayout {
    // A compact quote line, its padding and a visible price plot fit in 32 dp.
    static final int MIN_FULL_HEIGHT_DP = 32;

    static final class Page {
        final List<String> stocks;
        final int[] heights;

        Page(List<String> stocks, int[] heights) {
            this.stocks = stocks;
            this.heights = heights;
        }
    }

    static List<Page> pages(List<String> stocks, Set<String> minis, ToIntFunction<String> requestedHeight,
                            int totalHeight, int miniHeight, int pageIndicatorHeight,
                            int autoPreferredHeight, int minFullHeight) {
        // Negative sizes mark an explicit automatic row. Zero marks a Mini row.
        int[] targets = new int[stocks.size()];
        for (int i = 0; i < targets.length; i++) {
            String code = stocks.get(i);
            if (!minis.contains(code)) {
                int height = requestedHeight.applyAsInt(code);
                targets[i] = height > 0 ? height : -autoPreferredHeight;
            }
        }
        int capacity = Math.max(1, totalHeight);
        if (pageEnd(targets, 0, capacity, miniHeight, minFullHeight) < targets.length)
            capacity = Math.max(1, totalHeight - pageIndicatorHeight);
        List<Page> result = new ArrayList<>();
        for (int first = 0; first < targets.length;) {
            int end = pageEnd(targets, first, capacity, miniHeight, minFullHeight);
            int[] heights = rowHeights(targets, first, end, capacity, miniHeight);
            result.add(new Page(new ArrayList<>(stocks.subList(first, end)), heights));
            first = end;
        }
        return result;
    }

    private static int pageEnd(int[] targets, int first, int capacity, int miniHeight, int minimum) {
        long fullTotal = 0, remaining = capacity;
        int shortest = Integer.MAX_VALUE;
        for (int i = first; i < targets.length; i++) {
            int preferred = Math.abs(targets[i]);
            if (preferred == 0) remaining -= miniHeight;
            else {
                fullTotal += preferred;
                shortest = Math.min(shortest, preferred);
            }
            // Test the shortest row under the common scale. Longer rows will also remain legible.
            if (remaining < 0 || (fullTotal > 0 && remaining * shortest < fullTotal * minimum))
                return Math.max(first + 1, i);
        }
        return targets.length;
    }

    private static int[] rowHeights(int[] targets, int first, int end, int capacity, int miniHeight) {
        int available = capacity, autos = 0;
        long total = 0;
        for (int i = first; i < end; i++) {
            int target = targets[i];
            if (target == 0) available -= miniHeight;
            else {
                total += Math.abs(target);
                if (target < 0) autos++;
            }
        }
        int[] heights = new int[end - first];
        long cumulative = 0, allocated = 0;
        int extra = (int)Math.max(0L, available - total);
        for (int i = first; i < end; i++) {
            int target = targets[i];
            int height = Math.abs(target);
            if (target == 0) height = Math.min(miniHeight, capacity);
            else if (total > available) {
                // Round cumulative boundaries so row rounding errors cannot overflow the panel.
                cumulative += height;
                long boundary = Math.round((double)cumulative * available / total);
                height = (int)(boundary - allocated);
                allocated = boundary;
            } else if (target < 0) {
                int share = extra / autos;
                autos--;
                height += share;
                extra -= share;
            }
            heights[i - first] = height;
        }
        return heights;
    }
}
