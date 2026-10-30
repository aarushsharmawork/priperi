package com.priperi;

import android.content.*;
import android.os.SystemClock;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Failure cases use real phone crypto/storage and isolated HTTP/SQL; never live accounts. */
@RunWith(AndroidJUnit4.class)
public class ResilienceFlowTest {
    private Context context;private Vault bob,eve;private String uid,bobId,eveId;
    private static final String PASSWORD="Resilience-Password-2026!";
    private <T>T local(Repository.Job<T> job)throws Exception{
        CompletableFuture<T> done=new CompletableFuture<>();
        Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){done.completeExceptionally(e);return null;}},done::complete,e->done.completeExceptionally(new AssertionError(e)));
        return done.get(45,TimeUnit.SECONDS);
    }
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();String suffix=UUID.randomUUID().toString().substring(0,8);
        bob=new Vault(context,"resilience_bob_"+suffix);eve=new Vault(context,"resilience_eve_"+suffix);
        local(v->{assertNull("Disposable installation required",v.account());uid=Session.continueAccount(v,"qa_res_"+suffix,PASSWORD).getString("uid");bobId=Session.continueAccount(bob,"qa_rb_"+suffix,PASSWORD).getString("uid");eveId=Session.continueAccount(eve,"qa_re_"+suffix,PASSWORD).getString("uid");TestIdentityTrust.pin(bob,v.accountWithIdentity());TestIdentityTrust.pin(eve,v.accountWithIdentity());Session.restore(v);TestIdentityTrust.pin(v,bob.accountWithIdentity());TestIdentityTrust.pin(v,eve.accountWithIdentity());return true;});
    }
    @After public void cleanup()throws Exception{
        if(context==null)return;local(v->{Session.restore(bob);Session.resetAccount(bob);Session.restore(eve);Session.resetAccount(eve);Session.restore(v);Session.resetAccount(v);Repository.eraseLocal(v);return true;});bob.destroy();eve.destroy();Repository.reset();
    }
    @Test public void removedRecipientDoesNotStopSendingToOtherPeople()throws Exception{
        local(v->{JSONObject first=Repository.send(v,bobId,"Keep this rejected message safe");Thread.sleep(2);JSONObject second=Repository.send(v,eveId,"This chat must keep working");Session.restore(bob);Repository.call(bob,"deleteAccount",new JSONObject());Session.restore(v);
            String warning=new SyncEngine(v).outbox();assertFalse(warning.isEmpty());assertEquals("pending",v.message(first.getJSONObject("envelope").getString("id")).getString("state"));assertEquals("sent",v.message(second.getJSONObject("envelope").getString("id")).getString("state"));
            Session.restore(eve);Repository.sync(eve);assertEquals("This chat must keep working",eve.message(second.getJSONObject("envelope").getString("id")).getString("body"));Session.restore(v);return true;});
    }
    @Test public void blockedRecipientBacksOffWithoutDroppingOrReorderingTheirOutbox()throws Exception{
        local(v->{JSONObject first=Repository.send(v,bobId,"First queued"),later=Repository.send(v,bobId,"Second queued"),other=Repository.send(v,eveId,"Unaffected chat");String id=first.getJSONObject("envelope").getString("id"),laterId=later.getJSONObject("envelope").getString("id");Session.restore(bob);Repository.call(bob,"setBlocked",new JSONObject().put("uid",uid).put("blocked",true));Session.restore(v);
            assertFalse(new SyncEngine(v).outbox().isEmpty());assertEquals("sent",v.message(other.getJSONObject("envelope").getString("id")).getString("state"));long retry=v.message(id).getLong("retryAfter");assertTrue(retry>System.currentTimeMillis());assertEquals("pending",v.message(laterId).getString("state"));
            new SyncEngine(v).outbox();assertEquals("Repeated polling should not charge another rejected send",retry,v.message(id).getLong("retryAfter"));
            Session.restore(bob);Repository.call(bob,"setBlocked",new JSONObject().put("uid",uid).put("blocked",false));Session.restore(v);JSONObject due=v.message(id);due.put("retryAfter",System.currentTimeMillis()-1);v.store(due);new SyncEngine(v).outbox();assertEquals("sent",v.message(id).getString("state"));assertEquals("sent",v.message(laterId).getString("state"));assertFalse(v.message(id).has("retryAfter"));
            JSONObject full=Repository.send(v,bobId,"Wait for inbox space"),free=Repository.send(v,eveId,"Other inbox still works");Session.request("http://10.0.2.2:5001/qa/inbox-full",new JSONObject().put("uid",bobId),null,false);assertTrue(new SyncEngine(v).outbox().contains("inbox is full"));assertEquals("pending",v.message(full.getJSONObject("envelope").getString("id")).getString("state"));assertEquals("sent",v.message(free.getJSONObject("envelope").getString("id")).getString("state"));return true;});
    }
    @Test public void invalidRefreshReturnsToSignInWithoutLosingKeysOrMessages()throws Exception{
        local(v->{JSONObject message=Repository.send(v,bobId,"Saved before session expired");String identity=v.identity().toString(),id=message.getJSONObject("envelope").getString("id");v.session(new JSONObject(v.session().toString()).put("expires_at",0).put("refresh_token",UUID.randomUUID().toString()));
            Session.BackendException error=assertThrows(Session.BackendException.class,()->Session.access(v));assertEquals("refresh_token_not_found",error.reason);assertNull("Invalid refresh must not loop forever in a signed-in state",Session.uid());assertNull(v.session());assertEquals(identity,v.identity().toString());assertNotNull(v.message(id));assertNotNull(v.account());
            assertEquals(uid,Session.continueAccount(v,null,PASSWORD).getString("uid"));assertEquals(identity,v.identity().toString());assertNotNull(v.message(id));
            v.session(new JSONObject(v.session().toString()).put("expires_at",0).put("refresh_token",UUID.randomUUID().toString()));assertThrows(Session.BackendException.class,()->new SyncEngine(v).outbox());assertNull(Session.uid());assertFalse("Sign-in failure was mistaken for a per-message retry",v.message(id).has("retryAfter"));Session.continueAccount(v,null,PASSWORD);return true;});
    }
    @Test public void temporaryRefreshFailureKeepsTheValidSessionForRetry()throws Exception{
        Session.request("http://10.0.2.2:5001/qa/refresh-error",new JSONObject(),null,false);
        local(v->{v.session(new JSONObject(v.session().toString()).put("expires_at",0));String saved=v.session().toString();Session.BackendException error=assertThrows(Session.BackendException.class,()->Session.access(v));assertEquals(503,error.status);assertEquals(uid,Session.uid());assertEquals(saved,v.session().toString());assertNotNull(Session.access(v));assertEquals(uid,Session.uid());return true;});
    }
    @Test public void queuedChatLoadWhilePausedDoesNotMarkUnseenMessagesRead()throws Exception{
        try(ActivityScenario<ChatActivity> screen=ActivityScenario.launch(new Intent(context,ChatActivity.class).putExtra("peer",bobId))){
            await(screen,a->a.privateContentAvailable());local(v->true);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);long[] sentAt={0};
            try{
                Repository.work(context,v->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));JSONObject envelope=bob.crypto().encrypt(bobId,bob.identity(),v.accountWithIdentity(),"Unread while away");idHolder[0]=envelope.getString("id");sentAt[0]=envelope.getLong("sentAt");v.store(new JSONObject().put("peer",bobId).put("outgoing",false).put("state","received").put("body","Unread while away").put("envelope",envelope));return true;},null,null);
                assertTrue(entered.await(5,TimeUnit.SECONDS));screen.onActivity(a->{try{java.lang.reflect.Method load=ChatActivity.class.getDeclaredMethod("load");load.setAccessible(true);load.invoke(a);}catch(Exception e){throw new AssertionError(e);}});screen.moveToState(Lifecycle.State.CREATED);
            }finally{release.countDown();}
            assertTrue("A paused chat consumed the unread indicator",local(v->v.contact(bobId).optLong("readThrough"))<sentAt[0]);assertNotNull(local(v->v.message(idHolder[0])));
            screen.moveToState(Lifecycle.State.RESUMED);await(screen,a->a.privateContentAvailable());boolean read=false;for(int i=0;i<100;i++){if(local(v->v.contact(bobId).optLong("readThrough"))>=sentAt[0]){read=true;break;}SystemClock.sleep(30);}assertTrue("Visible chat was never marked read",read);
        }
    }
    @Test public void queuedGroupLoadWhilePausedKeepsItsUnreadCount()throws Exception{
        String gid=UUID.randomUUID().toString(),invite=UUID.randomUUID().toString();
        local(v->{GroupRepository.create(v,gid,"Unread group");GroupRepository.action(v,"inviteGroupMember",new JSONObject().put("groupId",gid).put("uid",bobId).put("id",invite));Session.restore(bob);GroupRepository.action(bob,"acceptGroupInvite",new JSONObject().put("id",invite));Session.restore(v);Repository.sync(v);return true;});
        try(ActivityScenario<ChatActivity> screen=ActivityScenario.launch(new Intent(context,ChatActivity.class).putExtra("peer","group:"+gid))){
            await(screen,a->a.privateContentAvailable());local(v->true);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);long[] at={0};
            try{
                Repository.work(context,v->{entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));JSONObject envelope=bob.crypto().encryptGroup(bobId,bob.identity(),v.accountWithIdentity(),"Unread group while away",UUID.randomUUID().toString(),System.currentTimeMillis(),gid,v.group(gid).getInt("version"));at[0]=envelope.getLong("sentAt");v.store(new JSONObject().put("peer","group:"+gid).put("author",bob.accountWithIdentity()).put("outgoing",false).put("state","received").put("body","Unread group while away").put("envelope",envelope));return true;},null,null);
                assertTrue(entered.await(5,TimeUnit.SECONDS));screen.onActivity(a->{try{java.lang.reflect.Method load=ChatActivity.class.getDeclaredMethod("load");load.setAccessible(true);load.invoke(a);}catch(Exception e){throw new AssertionError(e);}});screen.moveToState(Lifecycle.State.CREATED);
            }finally{release.countDown();}
            assertTrue("Paused group consumed the unread count",local(v->v.group(gid).optLong("readThrough"))<at[0]);
            screen.moveToState(Lifecycle.State.RESUMED);await(screen,a->a.privateContentAvailable());boolean read=false;for(int i=0;i<100;i++){if(local(v->v.group(gid).optLong("readThrough"))>=at[0]){read=true;break;}SystemClock.sleep(30);}assertTrue("Visible group was never marked read",read);
        }
    }
    private final String[] idHolder={null};
    private interface Ready{boolean test(ChatActivity activity);}
    private static void await(ActivityScenario<ChatActivity> screen,Ready ready){for(int i=0;i<120;i++){boolean[] yes={false};screen.onActivity(a->yes[0]=ready.test(a));if(yes[0])return;SystemClock.sleep(50);}fail("Chat did not become ready");}
}
