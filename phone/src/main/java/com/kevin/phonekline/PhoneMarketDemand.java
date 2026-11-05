package com.kevin.phonekline;

/** Requested rates of all subscribers using one symbol and endpoint. */
final class PhoneMarketDemand {
    final int marketHz, visibleDepthHz;
    static final long IDLE_DEPTH_MS = 120_000L;

    PhoneMarketDemand(int hz, boolean visible) {
        this(hz, visible ? hz : 0);
    }

    private PhoneMarketDemand(int marketHz, int visibleDepthHz) {
        this.marketHz = marketHz;
        this.visibleDepthHz = visibleDepthHz;
    }

    static PhoneMarketDemand combined(Iterable<PhoneMarketDemand> subscribers) {
        int market = 0, visible = 0;
        for (PhoneMarketDemand subscriber : subscribers) {
            market = Math.max(market, subscriber.marketHz);
            visible = Math.max(visible, subscriber.visibleDepthHz);
        }
        return new PhoneMarketDemand(market, visible);
    }

    int depthRate(boolean trading) { return trading ? marketHz : visibleDepthHz; }

    boolean depthDue(boolean trading, long elapsedSinceDepth) {
        return trading || visibleDepthHz > 0 || elapsedSinceDepth >= IDLE_DEPTH_MS;
    }

    long period(boolean trading, long wallTime) {
        if (trading || visibleDepthHz > 0) return 1_000L / Math.max(1, depthRate(trading));
        // Dormant subscribers check the session boundary without frequently polling depth.
        return Math.min(10_000L, 60_050L - wallTime % 60_000L);
    }
}
