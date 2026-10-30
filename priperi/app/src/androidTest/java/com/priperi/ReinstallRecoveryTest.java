package com.priperi;

import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import androidx.test.core.app.*;import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;import org.junit.*;import org.junit.runner.RunWith;
import java.util.*;import java.util.concurrent.*;import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReinstallRecoveryTest {
    private Context context;private String oldProof,oldToken,oldUid,device;
    private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();Repository.local(context,job,f::complete,e->f.completeExceptionally(new AssertionError(e)));return f.get(40,TimeUnit.SECONDS);}
    @Before public void setup()throws Exception{
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();
        local(v->{assertNull(v.account());Session.continueAccount(v,"qa_reinstall_"+UUID.randomUUID().toString().substring(0,8),"Reinstall-Test-2026!");oldProof=v.identity().getString("deviceSecret");device=v.identity().getString("deviceId");oldToken=v.session().getString("access_token");oldUid=Session.uid();Repository.eraseLocal(v);return true;});
    }
    @After public void cleanup()throws Exception{
        // Only this synthetic account; retain its pre-wipe test token solely for cleanup.
        JSONObject reply=Session.request(Session.endpoint(),new JSONObject().put("operation","deleteAccount").put("deviceSecret",oldProof),oldToken,true);assertEquals("deleted",reply.getJSONObject("data").getString("status"));
        local(v->{assertNull(v.account());Repository.eraseLocal(v);return true;});Repository.reset();oldProof=null;oldToken=null;
    }
    @Test public void samePhoneAfterKeyLossCannotTakeOverOrResetTheOldAccount()throws Exception{
        local(v->{assertEquals(device,v.identity().getString("deviceId"));assertNull(Session.uid());try{Session.deviceStatus(v);fail("Lost private key was accepted");}catch(Session.BackendException error){assertEquals("device-key-missing",error.reason);}assertNull(v.account());return true;});
        JSONObject profile=Session.request(Session.endpoint(),new JSONObject().put("operation","getAccount").put("deviceSecret",oldProof),oldToken,true).getJSONObject("data");assertEquals(oldUid,profile.getString("uid"));
    }
    @Test public void reinstallPageExplainsRecoveryAndButtonsRemainUsable()throws Exception{
        try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
            await(screen,"Recovery options");screen.onActivity(a->NavigationRecoveryTest.find(a.getWindow().getDecorView(),"Recovery options").performClick());
            // Use the actual guarded dialog and Done button; it must not close the app.
            AccountControlsFlowTest.click("Done");await(screen,"Recovery options");screen.onActivity(a->NavigationRecoveryTest.find(a.getWindow().getDecorView(),"Check again").performClick());await(screen,"Recovery options");
            assertNull(Session.uid());assertTrue(local(v->v.account()==null));
        }
    }
    private static void await(ActivityScenario<MainActivity> s,String text){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] seen={false};s.onActivity(a->{View v=NavigationRecoveryTest.find(a.getWindow().getDecorView(),text);seen[0]=v!=null&&v.isShown();});if(seen[0])return;SystemClock.sleep(30);}fail("Missing recovery control: "+text);}
}
