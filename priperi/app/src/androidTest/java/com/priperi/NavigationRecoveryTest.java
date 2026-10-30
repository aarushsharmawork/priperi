package com.priperi;

import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Disposable identities only; never approves or bypasses the production biometric gate. */
@RunWith(AndroidJUnit4.class)
public class NavigationRecoveryTest {
    private Context context;private Vault peer;private String peerId;private AccountTransfer.Offer offer;
    private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();Repository.local(context,job,f::complete,e->f.completeExceptionally(new AssertionError(e)));return f.get(60,TimeUnit.SECONDS);}
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();peer=new Vault(context,"navigation_peer_"+UUID.randomUUID());
        local(v->{assertNull("Disposable installation required",v.account());String suffix=UUID.randomUUID().toString().substring(0,8);Session.continueAccount(v,"qa_nav_"+suffix,"Navigation-Test-2026!");Session.continueAccount(peer,"qa_np_"+suffix,"Navigation-Test-2026!");peerId=peer.account().getString("uid");TestIdentityTrust.pin(v,peer.accountWithIdentity());Session.restore(v);AccountPolicy.forget();return true;});
    }
    @After public void cleanup()throws Exception{
        PriperiApp.privateForeground=false;
        try{local(v->{if(v.transferOut()!=null)AccountTransfer.cancel(v);try{Session.restore(peer);Session.resetAccount(peer);}finally{Session.restore(v);}if(v.account()!=null)Session.resetAccount(v);Repository.eraseLocal(v);return true;});}
        finally{if(offer!=null)offer.close();peer.destroy();Repository.reset();}
    }
    @Test public void pendingMoveDoneLeavesASafeUsableLanding()throws Exception{
        local(v->{offer=AccountTransfer.begin(v,"Navigation-Test-2026!".toCharArray());return true;});
        try(ActivityScenario<TransferActivity> screen=ActivityScenario.launch(TransferActivity.class)){
            await(screen,a->find(a.getWindow().getDecorView(),"Account move is pending")!=null,10000);
            screen.onActivity(a->find(a.getWindow().getDecorView(),"Done").performClick());
            long end=SystemClock.elapsedRealtime()+5000;boolean found=false;
            while(SystemClock.elapsedRealtime()<end){android.view.accessibility.AccessibilityNodeInfo n=InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(n!=null&&!n.findAccessibilityNodeInfosByText("Account move paused").isEmpty()){found=true;break;}SystemClock.sleep(50);}
            assertTrue("Done left no usable pending-move screen",found);assertTrue(local(v->v.transferOut()!=null));
        }
    }
    @Test public void completedSourceScreenIsVisibleAfterPauseAndResume()throws Exception{
        try(ActivityScenario<TransferActivity> screen=ActivityScenario.launch(TransferActivity.class)){
            await(screen,a->find(a.getWindow().getDecorView(),"Move this account to another phone")!=null,10000);
            screen.onActivity(a->{try{java.lang.reflect.Field field=TransferActivity.class.getDeclaredField("sourceFinished");field.setAccessible(true);field.setBoolean(a,true);java.lang.reflect.Method show=TransferActivity.class.getDeclaredMethod("showSourceFinished");show.setAccessible(true);show.invoke(a);}catch(Exception e){throw new AssertionError(e);}});
            screen.moveToState(Lifecycle.State.CREATED);screen.moveToState(Lifecycle.State.RESUMED);
            screen.onActivity(a->{View moved=find(a.getWindow().getDecorView(),"Account moved");assertNotNull(moved);assertTrue("Completed move remained invisible after resume",moved.isShown());});
        }
    }
    @Test public void slowPresenceDoesNotHoldChatOpening()throws Exception{
        Session.request("http://10.0.2.2:5001/qa/presence-delay",new JSONObject(),null,false);PriperiApp.privateForeground=true;AccountPolicy.resumed(context);
        long end=SystemClock.elapsedRealtime()+10000;while(delayActive()==0&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(20);assertEquals(1,delayActive());
        long start=SystemClock.elapsedRealtime();
        try(ActivityScenario<ChatActivity> screen=ActivityScenario.launch(new Intent(context,ChatActivity.class).putExtra("peer",peerId))){
            await(screen,a->a.root.isShown()&&composer(a.root)!=null&&composer(a.root).isShown(),900);
            long elapsed=SystemClock.elapsedRealtime()-start;assertEquals("Chat waited for presence response",1,delayActive());
            Bundle metrics=new Bundle();metrics.putLong("chatOpeningDuringSlowPresenceMs",elapsed);InstrumentationRegistry.getInstrumentation().sendStatus(0,metrics);
        }
    }
    @Test public void settingsRemainUsableAfterCurrentPasswordReauthentication()throws Exception{
        try(ActivityScenario<SettingsActivity> screen=ActivityScenario.launch(SettingsActivity.class)){
            await(screen,a->a.root.isShown()&&find(a.root,"Appearance")!=null,10000);
            screen.onActivity(a->assertNull("Storage must not require a button",find(a.root,"Optimise storage")));
            local(v->{Session.continueAccount(v,null,"Navigation-Test-2026!");return true;});
            screen.onActivity(a->{assertTrue(a.privateContentAvailable());View row=find(a.root,"Appearance");while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();row.performClick();});
            AccountControlsFlowTest.click("Cancel");screen.onActivity(a->assertTrue(a.root.isShown()));
            for(int i=0;i<3;i++){
                screen.moveToState(Lifecycle.State.CREATED);screen.moveToState(Lifecycle.State.RESUMED);
                await(screen,a->a.privateContentAvailable()&&a.dialogAllowed()&&find(a.root,"Self-destruct account")!=null,10000);
                screen.onActivity(a->{View row=find(a.root,"Self-destruct account");while(!row.isClickable()&&row.getParent() instanceof View)row=(View)row.getParent();row.performClick();});
                AccountControlsFlowTest.click("After 7 days");AccountControlsFlowTest.click("Cancel");screen.onActivity(a->assertTrue(a.privateContentAvailable()));
            }
        }
    }
    private int delayActive()throws Exception{return Session.request("http://10.0.2.2:5001/qa/delay-state",new JSONObject(),null,false).getInt("active");}
    private interface Ready<T extends android.app.Activity>{boolean ok(T activity);}
    private static <T extends android.app.Activity>void await(ActivityScenario<T> screen,Ready<T> ready,long timeout){long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){boolean[] ok={false};screen.onActivity(a->ok[0]=ready.ok(a));if(ok[0])return;SystemClock.sleep(20);}fail("Screen did not become usable");}
    static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View match=find(((ViewGroup)v).getChildAt(i),text);if(match!=null)return match;}return null;}
    private static EditText composer(View v){if(v instanceof EditText&&"Message".contentEquals(((EditText)v).getHint()))return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=composer(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
}
