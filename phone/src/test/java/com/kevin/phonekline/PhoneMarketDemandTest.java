package com.kevin.phonekline;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneMarketDemandTest {
    @Test public void sharedAodAndSnapshotDoNotRaiseNightDisplayRate() {
        List<PhoneMarketDemand> subscribers = new ArrayList<>();
        subscribers.add(new PhoneMarketDemand(0,false)); // transient widget
        subscribers.add(new PhoneMarketDemand(10,false)); // scheduled AOD
        subscribers.add(new PhoneMarketDemand(2,true)); // visible lock wallpaper
        PhoneMarketDemand result = PhoneMarketDemand.combined(subscribers);
        assertEquals(100, result.period(true,0));
        assertEquals(500, result.period(false,0));
        assertEquals(2, result.depthRate(false));
        assertTrue(result.depthDue(false,1));

        subscribers.add(new PhoneMarketDemand(5,true)); // charging dream joins the same feed
        assertEquals(200,PhoneMarketDemand.combined(subscribers).period(false,0));
        subscribers.remove(3);
        assertEquals(500,PhoneMarketDemand.combined(subscribers).period(false,0));
        subscribers.remove(2);

        PhoneMarketDemand idle = PhoneMarketDemand.combined(subscribers);
        assertEquals(0,idle.depthRate(false));
        assertFalse(idle.depthDue(false,119_999));
        assertTrue(idle.depthDue(false,120_000));
        assertEquals(10_000,idle.period(false,0));
        assertEquals(100,idle.period(true,0));
    }

    @Test public void widgetAloneUsesCacheEligibleOneHzDuringTrading() {
        PhoneMarketDemand widget = new PhoneMarketDemand(0,false);
        assertEquals(0,widget.depthRate(true)); // force is omitted
        assertEquals(1_000,widget.period(true,0));
        assertTrue(widget.depthDue(true,0));
        assertFalse(widget.depthDue(false,0));
        assertEquals(51,widget.period(false,59_999));
    }
}
