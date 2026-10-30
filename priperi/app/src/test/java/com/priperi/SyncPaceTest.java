package com.priperi;
import org.junit.Test;import static org.junit.Assert.*;
public class SyncPaceTest {
 @Test public void onlyIdleListsBackOffAndConversationsStayFast(){SyncPace p=new SyncPace();for(int i=0;i<3;i++)assertEquals(4000,p.complete(false,false,false));assertEquals(8000,p.complete(false,false,false));assertEquals(4000,p.complete(false,false,true));assertEquals(4000,p.complete(true,false,false));}
 @Test public void failuresAreBoundedAndSuccessOrReconnectResets(){SyncPace p=new SyncPace();assertEquals(8000,p.complete(false,true,true));assertEquals(16000,p.complete(false,true,true));assertEquals(32000,p.complete(false,true,true));assertEquals(60000,p.complete(false,true,true));assertEquals(4000,p.complete(true,false,true));p.reset();assertEquals(4000,p.complete(false,false,false));}
}
