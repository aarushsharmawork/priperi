package com.priperi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SyncResponsivenessTest {
    private final Context context=ApplicationProvider.getApplicationContext();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> done=new CompletableFuture<>();Repository.local(context,job,done::complete,e->done.completeExceptionally(new AssertionError(e)));return done.get(40,TimeUnit.SECONDS);}
    private JSONObject fixture(String name)throws Exception{return Session.request("http://10.0.2.2:5001/qa/"+name,new JSONObject(),null,false);}
    @Test public void localStorageDoesNotWaitForSlowInbox()throws Exception{
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);Repository.reset();
        local(v->{assertNull("Disposable empty installation required",v.account());Session.continueAccount(v,"qa_speed_"+UUID.randomUUID().toString().substring(0,8),"Speed-Password-2026!");return true;});
        SyncCoordinator.Observer observer=message->{};
        Vault peer=new Vault(context,"speed_peer_"+UUID.randomUUID());
        try{
            String peerId=local(v->{Session.continueAccount(peer,"qa_sppeer_"+UUID.randomUUID().toString().substring(0,8),"Speed-Password-2026!");TestIdentityTrust.pin(v,peer.accountWithIdentity());Session.restore(v);return peer.account().getString("uid");});
            fixture("sync-delay");MAIN.post(()->SyncCoordinator.attach(observer,true));
            long until=SystemClock.elapsedRealtime()+10000;
            while(fixture("delay-state").optInt("active")==0&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
            assertEquals("Delayed inbox request did not start",1,fixture("delay-state").getInt("active"));
            long start=SystemClock.elapsedRealtime();local(v->{v.setSetting("speedProbe",true);return true;});long elapsed=SystemClock.elapsedRealtime()-start;
            System.out.println("LOCAL_DURING_SLOW_SYNC_MS="+elapsed);
            assertTrue("Network wait blocked a local save",elapsed<700);
            start=SystemClock.elapsedRealtime();String id=local(v->Repository.send(v,peerId,"Send while inbox is delayed").getJSONObject("envelope").getString("id"));
            MAIN.post(()->SyncCoordinator.request(context));
            until=SystemClock.elapsedRealtime()+1500;
            while(!local(v->"sent".equals(v.message(id).optString("state")))&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
            long sendTime=SystemClock.elapsedRealtime()-start;assertTrue("Outbox waited for the delayed inbox",sendTime<1500);
            assertEquals("sent",local(v->v.message(id).getString("state")));
            System.out.println("SEND_DURING_SLOW_SYNC_MS="+sendTime);
            android.os.Bundle metrics=new android.os.Bundle();metrics.putLong("localDuringSlowSyncMs",elapsed);metrics.putLong("sendDuringSlowSyncMs",sendTime);androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,metrics);
        }finally{
            MAIN.post(()->SyncCoordinator.detach(observer));
            local(v->{try{if(peer.account()!=null){Session.restore(peer);Session.resetAccount(peer);}}finally{Session.restore(v);}Session.resetAccount(v);Repository.eraseLocal(v);return true;});peer.destroy();Repository.reset();
        }
    }
    @Test public void oldSessionAndPendingMoveCannotApplyNetworkResults()throws Exception{
        local(v->{assertNull(v.account());Session.continueAccount(v,"qa_lease_"+UUID.randomUUID().toString().substring(0,8),"Speed-Password-2026!");return true;});
        Repository.Lease removed=Repository.stage(null,Repository.Lease::new);
        try{
            Repository.Lease old=Repository.stage(null,Repository.Lease::new);
            local(v->{Session.forget();Session.restore(v);return true;});
            assertThrows(SecurityException.class,()->Repository.stage(old,v->true));
            Repository.Lease moving=Repository.stage(null,Repository.Lease::new);
            local(v->{v.transferOut(new JSONObject().put("test",true));return true;});
            assertThrows(SecurityException.class,()->Repository.stage(moving,v->true));
            local(v->{v.transferOut(null);return true;});
            local(v->{
                JSONObject env=v.crypto().encrypt(Session.uid(),v.identity(),v.accountWithIdentity(),"Expired local outbox");env.put("sentAt",System.currentTimeMillis()-8L*24*60*60*1000);env.put("signature",v.crypto().sign((Crypto.context(env)+"\n"+env.getString("ciphertext")).getBytes(Crypto.UTF8)));
                v.store(new JSONObject().put("peer",Session.uid()).put("body","Expired local outbox").put("envelope",env).put("outgoing",true).put("state","sent"));
                new SyncEngine(v).outbox();assertEquals("expired",v.message(env.getString("id")).getString("state"));assertTrue(v.workRecords().isEmpty());return true;
            });
        }finally{
            local(v->{v.transferOut(null);Session.resetAccount(v);Repository.eraseLocal(v);return true;});
            assertThrows(SecurityException.class,()->Repository.stage(removed,v->true));
            String directory="account_"+Crypto.hex(java.security.MessageDigest.getInstance("SHA-256").digest("installation-v2".getBytes(Crypto.UTF8)));
            assertFalse("Late reply recreated removed private storage",new java.io.File(context.getNoBackupFilesDir(),directory).exists());Repository.reset();
        }
    }
}
