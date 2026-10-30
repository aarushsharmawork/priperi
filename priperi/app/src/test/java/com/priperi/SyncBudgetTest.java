package com.priperi;

import org.junit.Test;
import static org.junit.Assert.*;

public class SyncBudgetTest {
    @Test public void sparsePushCanStartImmediatelyAndBurstsStayBelowServerQuota() {
        SyncBudget budget=new SyncBudget();
        for(int i=0;i<24;i++){assertEquals(0,budget.delay(1000+i*10));budget.record(1000+i*10);}
        assertEquals(59760,budget.delay(1240));
        assertEquals(0,budget.delay(61000));budget.record(61000);
        assertEquals(10,budget.delay(61000));
    }
    @Test public void idleTimeReleasesTheBudgetAndDeniedStartsAreNotRecorded() {
        SyncBudget budget=new SyncBudget();
        for(int i=0;i<24;i++)budget.record(1);
        assertThrows(IllegalStateException.class,()->budget.record(2));
        assertEquals(0,budget.delay(60001));
        for(int i=0;i<24;i++)budget.record(60001);
        assertEquals(60000,budget.delay(60001));
    }
}
