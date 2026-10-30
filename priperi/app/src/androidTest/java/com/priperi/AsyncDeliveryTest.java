package com.priperi;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AsyncDeliveryTest {
    private final Context context=ApplicationProvider.getApplicationContext();
    private Vault peer;
    private JSONObject incoming;
    private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();Repository.local(context,job,f::complete,e->f.completeExceptionally(new AssertionError(e)));return f.get(40,TimeUnit.SECONDS);}
    private JSONObject fixture(String name)throws Exception{return Session.request("http://10.0.2.2:5001/qa/"+name,new JSONObject(),null,false);}
    @Before public void setup()throws Exception{
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);Repository.reset();peer=new Vault(context,"async_peer_"+UUID.randomUUID());
        local(v->{assertNull("Disposable installation required",v.account());String suffix=UUID.randomUUID().toString().substring(0,8);Session.continueAccount(v,"qa_async_"+suffix,"Async-Password-2026!");Session.continueAccount(peer,"qa_ap_"+suffix,"Async-Password-2026!");TestIdentityTrust.pin(peer,v.accountWithIdentity());incoming=Repository.send(peer,v.account().getString("uid"),"Durable before receipt "+suffix).getJSONObject("envelope");Repository.sync(peer);Session.restore(v);return true;});
    }
    @After public void cleanup()throws Exception{
        local(v->{try{Session.restore(peer);Session.resetAccount(peer);}finally{Session.restore(v);}Session.resetAccount(v);Repository.eraseLocal(v);return true;});peer.destroy();Repository.reset();
    }
    private void waitDelay()throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(fixture("delay-state").optInt("active")==0&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertEquals(1,fixture("delay-state").getInt("active"));}
    @Test public void committedMessageIsAvailableBeforeSlowAcknowledgementFinishes()throws Exception{
        fixture("ack-delay");SyncEngine engine=new SyncEngine((SyncEngine.Progress)null);CompletableFuture<String> result=CompletableFuture.supplyAsync(()->{try{return engine.inbox();}catch(Exception e){throw new CompletionException(e);}});
        waitDelay();assertFalse(result.isDone());
        assertTrue(local(v->v.seen(incoming)));assertEquals(1,(int)local(v->v.messages(peer.account().getString("uid")).size()));
        assertEquals("",result.get(10,TimeUnit.SECONDS));
        assertEquals(0,(int)local(v->Repository.call(v,"syncInbox",new JSONObject()).getJSONArray("messages").length()));
    }
    @Test public void lateResponseFromOldSessionIsDiscardedAndSafelyRedelivered()throws Exception{
        fixture("sync-delay");SyncEngine old=new SyncEngine((SyncEngine.Progress)null);CompletableFuture<String> result=CompletableFuture.supplyAsync(()->{try{return old.inbox();}catch(Exception e){throw new CompletionException(e);}});
        waitDelay();local(v->{Session.forget();Session.restore(v);return true;});
        assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));assertFalse(local(v->v.seen(incoming)));
        assertEquals("",new SyncEngine((SyncEngine.Progress)null).inbox());assertTrue(local(v->v.seen(incoming)));assertEquals(1,(int)local(v->v.messages(null).size()));
    }
    @Test public void late401DoesNotClearATokenRefreshedDuringTheRequest()throws Exception{
        String original=local(v->v.session().getString("access_token"));long generation=Session.generation();
        fixture("stale-401");SyncEngine old=new SyncEngine((SyncEngine.Progress)null);CompletableFuture<String> result=CompletableFuture.supplyAsync(()->{try{return old.inbox();}catch(Exception e){throw new CompletionException(e);}});
        waitDelay();String refreshed=local(v->{JSONObject session=new JSONObject(v.session().toString()).put("expires_at",0);v.session(session);return Session.access(v);});
        assertNotEquals(original,refreshed);assertEquals(generation,Session.generation());
        assertThrows(ExecutionException.class,()->result.get(10,TimeUnit.SECONDS));assertNotNull(Session.uid());assertEquals(refreshed,local(v->v.session().getString("access_token")));
        assertEquals("",new SyncEngine((SyncEngine.Progress)null).inbox());assertTrue(local(v->v.seen(incoming)));
    }
}
