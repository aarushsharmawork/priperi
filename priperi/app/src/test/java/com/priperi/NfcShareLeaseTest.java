package com.priperi;
import org.junit.Test;
import static org.junit.Assert.*;
public class NfcShareLeaseTest {
    private static final String A="00000000-0000-4000-8000-000000000001",B="00000000-0000-4000-8000-000000000002",CODE=ContactCode.encode(A,"a".repeat(64));
    @Test public void processRestartLockLogoutDifferentOwnerExpiryAndClockRollbackDenySharing(){
        NfcShareLease lease=new NfcShareLease();assertNull(lease.current(A,100,true));Object token=lease.open(A,CODE,100);assertSame(token,lease.current(A,100,true));assertEquals(CODE,lease.read(token,A,101,true));assertNull(lease.read(null,A,101,true));assertNull(lease.read(token,A,101,false));assertNull(lease.read(token,null,101,true));assertNull(lease.read(token,B,101,true));assertNull(lease.read(token,A,99,true));assertNull(lease.read(token,A,90100,true));assertNull(new NfcShareLease().read(token,A,101,true));
    }
    @Test public void closingOrRenewingConsentRevokesAnAlreadySelectedConnection(){
        NfcShareLease lease=new NfcShareLease();Object old=lease.open(A,CODE,1),fresh=lease.open(A,CODE,2);assertNull(lease.read(old,A,3,true));lease.close(old);assertEquals(CODE,lease.read(fresh,A,3,true));lease.close(fresh);assertNull(lease.current(A,4,true));
    }
    @Test public void mismatchedIdentityAndInvalidClockCannotArmSharing(){NfcShareLease lease=new NfcShareLease();assertThrows(IllegalArgumentException.class,()->lease.open(B,CODE,0));assertThrows(IllegalArgumentException.class,()->lease.open(A,CODE,-1));assertThrows(IllegalArgumentException.class,()->lease.open(A,CODE,Long.MAX_VALUE));}
}
