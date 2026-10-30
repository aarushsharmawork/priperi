package com.priperi;
import org.junit.Test;
import static org.junit.Assert.*;
public class ContactAllowanceTest {
    @Test public void queuedAndConfirmedMessagesShareThreePlaces(){assertEquals(3,ContactAllowance.remaining(false,0,0,0));assertEquals(1,ContactAllowance.remaining(false,1,1,1));assertEquals(0,ContactAllowance.remaining(false,3,0,0));assertEquals(0,ContactAllowance.remaining(false,0,2,1));}
    @Test public void clearingHistoryCannotResetKnownCountAndApprovalUnblocks(){assertEquals(0,ContactAllowance.remaining(false,3,0,0));assertEquals(Integer.MAX_VALUE,ContactAllowance.remaining(true,3,100,20));assertEquals(0,ContactAllowance.remaining(false,0,100,0));}
}
