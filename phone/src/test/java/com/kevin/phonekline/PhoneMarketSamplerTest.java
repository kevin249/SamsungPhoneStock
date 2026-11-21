package com.kevin.phonekline;

import org.junit.Test;
import static org.junit.Assert.*;

public class PhoneMarketSamplerTest {
    @Test public void normalDepthNeverStacksSlowRequestsAtAnySelectedRate() {
        for (int hz : new int[]{1,2,5,10}) {
            assertEquals(1,PhoneMarketSampler.inFlightLimit(true,PhoneMarketSampler.Run.NORMAL,hz));
            assertEquals(1,PhoneMarketSampler.inFlightLimit(true,PhoneMarketSampler.Run.DISPLAY_PROBE,hz));
            assertTrue(PhoneMarketSampler.inFlightLimit(false,PhoneMarketSampler.Run.NORMAL,hz)>1);
        }
        assertEquals(12,PhoneMarketSampler.inFlightLimit(true,PhoneMarketSampler.Run.BENCHMARK,5));
    }

    @Test public void concurrentTimeoutsCountAsOneFailedBatch() {
        PhoneMarketSampler.RetryBackoff retry = new PhoneMarketSampler.RetryBackoff();
        for (int ticket = 1; ticket <= 12; ticket++) retry.failed(1000 + ticket * 10, ticket, 12, 0);
        assertFalse(retry.allows(3009));
        assertTrue(retry.allows(3010));
        retry.failed(4000, 13, 13, 0);
        assertFalse(retry.allows(7999));
        assertTrue(retry.allows(8000));
    }

    @Test public void successClearsTheRemainingWaitAndFailureCount() {
        PhoneMarketSampler.RetryBackoff retry = new PhoneMarketSampler.RetryBackoff();
        retry.failed(1000, 1, 2, 0);
        retry.reset();
        assertTrue(retry.allows(1100));
        retry.failed(1200, 3, 3, 0);
        assertTrue(retry.allows(3200));
    }

    @Test public void rateLimitsAndAuthorizationErrorsKeepTheirWaits() {
        PhoneMarketSampler.RetryBackoff retry = new PhoneMarketSampler.RetryBackoff();
        retry.failed(1000, 1, 2, 429);
        assertFalse(retry.allows(30_999));
        assertTrue(retry.allows(31_000));
        retry.failed(1100, 2, 2, 403);
        assertFalse(retry.allows(61_099));
        assertTrue(retry.allows(61_100));
    }
}
