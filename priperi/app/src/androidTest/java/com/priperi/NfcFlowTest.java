package com.priperi;

import android.content.*;
import android.content.pm.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Exercises the real private screen and resolution path; RF transport needs two physical phones. */
@RunWith(AndroidJUnit4.class)
public class NfcFlowTest {
    private Context context;private Vault peer;private String uid,payload;
    private void local(Repository.Job<Boolean> job)throws Exception{CountDownLatch done=new CountDownLatch(1);Throwable[] failure={null};Repository.local(context,v->{try{return job.run(v);}catch(Throwable e){failure[0]=e;return false;}},ok->done.countDown(),e->{failure[0]=new AssertionError(e);done.countDown();});assertTrue(done.await(30,TimeUnit.SECONDS));if(failure[0]!=null)throw new AssertionError(failure[0]);}
    @Before public void setup()throws Exception{
        org.junit.Assume.assumeTrue("yes".equals(InstrumentationRegistry.getArguments().getString("nfc")));
        context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).commit();Repository.reset();
        local(v->{if(v.account()!=null)throw new SecurityException("Disposable empty installation required");Session.continueAccount(v,"qa_nfc_"+UUID.randomUUID().toString().substring(0,8),"Nfc-Test-Password-2026!");return true;});
        peer=new Vault(context,"nfc_peer_"+UUID.randomUUID());
        local(v->{Session.continueAccount(peer,"qa_nfcp_"+UUID.randomUUID().toString().substring(0,8),"Nfc-Test-Password-2026!");uid=Session.uid();payload=ContactCode.encode(uid,peer.identity().getString("fingerprint"));Session.restore(v);return true;});
    }
    @After public void cleanup()throws Exception{if(peer==null)return;local(v->{Session.restore(peer);Session.resetAccount(peer);Session.restore(v);Session.resetAccount(v);Repository.eraseLocal(v);return true;});peer.close();Repository.reset();}
    @Test public void qrAndNfcShareOneScreenAndLeavingRevokesConsent()throws Exception{
        ServiceInfo service=context.getPackageManager().getServiceInfo(new ComponentName(context,NfcContactService.class),0);assertTrue(service.exported);assertEquals("android.permission.BIND_NFC_SERVICE",service.permission);
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.CAMERA);
        try(ActivityScenario<ContactCodeActivity> scenario=ActivityScenario.launch(ContactCodeActivity.class)){
            open(scenario);scenario.onActivity(a->{NfcContactPanel p=panel(a);assertNull(find(a.root,"Tap to share with NFC"));assertNull(find(a.root,"Read a contact"));assertNotNull(findDescription(a.root,"Connect by NFC"));assertTrue(find(a.root,"Scan a code").isShown());arm(a,p);find(a.root,"Scan a code").performClick();assertNull(NfcContactService.SHARING.current(Session.uid(),SystemClock.elapsedRealtime(),true));androidx.appcompat.app.AlertDialog camera=(androidx.appcompat.app.AlertDialog)field(a,"cameraDialog");assertNotNull(camera);assertTrue(camera.isShowing());assertTrue((camera.getWindow().getAttributes().flags&android.view.WindowManager.LayoutParams.FLAG_SECURE)!=0);camera.getButton(-2).performClick();assertTrue(findDescription(a.root,"Connect by NFC").isShown());arm(a,p);});
            scenario.moveToState(Lifecycle.State.CREATED);assertNull(NfcContactService.SHARING.current(Session.uid(),SystemClock.elapsedRealtime(),true));scenario.moveToState(Lifecycle.State.RESUMED);await(scenario,NfcFlowTest::ready);
            scenario.onActivity(a->{assertNotNull(findDescription(a.root,"Connect by NFC"));if(android.nfc.NfcAdapter.getDefaultAdapter(context)==null)assertNull(NfcContactService.SHARING.current(Session.uid(),SystemClock.elapsedRealtime(),true));});scenario.recreate();await(scenario,NfcFlowTest::ready);scenario.onActivity(a->assertTrue(find(a.root,"Scan a code").isShown()));
        }
    }
    @Test public void receivedContactNeedsConfirmationAndVerifiesOnlyTheConfirmedKey()throws Exception{
        try(ActivityScenario<ContactCodeActivity> scenario=ActivityScenario.launch(ContactCodeActivity.class)){
            open(scenario);scenario.onActivity(a->{NfcContactPanel p=panel(a);assertNull(find(a.root,"NFC"));assertNull(find(a.root,"QR code"));assertTrue(find(a.root,"Scan a code").isShown());p.received("https://invalid.example");assertNotNull(find(a.root,"This is not a Priperi contact code."));p.received(payload);});await(scenario,a->dialog(panel(a))!=null&&dialog(panel(a)).isShowing());
            local(v->{assertNull(v.contact(uid));return true;});scenario.onActivity(a->dialog(panel(a)).getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
            local(v->{assertNull(v.contact(uid));return true;});scenario.onActivity(a->panel(a).received(payload));await(scenario,a->dialog(panel(a))!=null&&dialog(panel(a)).isShowing());scenario.onActivity(a->dialog(panel(a)).getButton(DialogInterface.BUTTON_POSITIVE).performClick());
            for(int i=0;i<100&&scenario.getState()!=Lifecycle.State.DESTROYED;i++)SystemClock.sleep(100);assertEquals(Lifecycle.State.DESTROYED,scenario.getState());local(v->{assertNotNull(v.contact(uid));assertTrue(v.isVerified(v.contact(uid)));assertTrue(v.contact(uid).optBoolean("verified"));assertFalse(v.contact(uid).optJSONObject("contactState")!=null&&v.contact(uid).optJSONObject("contactState").optBoolean("approved"));return true;});
        }
    }
    @Test public void selfWrongKeyAndPausedCallbackCannotAddContacts()throws Exception{
        try(ActivityScenario<ContactCodeActivity> scenario=ActivityScenario.launch(ContactCodeActivity.class)){
            open(scenario);String[] own={null};local(v->{own[0]=ContactCode.encode(Session.uid(),v.identity().getString("fingerprint"));return true;});
            scenario.onActivity(a->{panel(a).received(own[0]);assertNotNull(find(a.root,"This is your own contact. Read the other person’s phone instead."));panel(a).received(ContactCode.encode(uid,"0".repeat(64)));});await(scenario,a->find(a.root,"Finding this account…")==null);
            local(v->{assertNull(v.contact(uid));return true;});NfcContactPanel[] paused={null};scenario.onActivity(a->{assertNull(dialog(panel(a)));paused[0]=panel(a);});scenario.moveToState(Lifecycle.State.CREATED);InstrumentationRegistry.getInstrumentation().runOnMainSync(()->paused[0].received(payload));local(v->{assertNull(v.contact(uid));return true;});scenario.moveToState(Lifecycle.State.RESUMED);await(scenario,NfcFlowTest::ready);
        }
    }
    private static void arm(ContactCodeActivity a,NfcContactPanel p){try{String code=(String)field(a,"ownCode");Object token=NfcContactService.SHARING.open(Session.uid(),code,SystemClock.elapsedRealtime());java.lang.reflect.Field f=NfcContactPanel.class.getDeclaredField("shareToken");f.setAccessible(true);f.set(p,token);}catch(Exception e){throw new AssertionError(e);}}
    private static Object field(Object o,String name){try{java.lang.reflect.Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private static NfcContactPanel panel(ContactCodeActivity a){return (NfcContactPanel)field(a,"nfc");}
    private static androidx.appcompat.app.AlertDialog dialog(NfcContactPanel p){return (androidx.appcompat.app.AlertDialog)field(p,"confirmation");}
    private static boolean ready(ContactCodeActivity a){return a.root.isShown()&&field(a,"ownCode")!=null;}
    private static void open(ActivityScenario<ContactCodeActivity> s){await(s,NfcFlowTest::ready);}
    private interface Ready{boolean test(ContactCodeActivity a);}
    private static void await(ActivityScenario<ContactCodeActivity> s,Ready r){for(int i=0;i<100;i++){boolean[] ok={false};s.onActivity(a->ok[0]=r.test(a));if(ok[0])return;SystemClock.sleep(100);}fail("NFC screen did not become usable");}
    private static View findDescription(View v,String text){if(text.equals(v.getContentDescription()))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findDescription(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}return null;}
    private static View find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View found=find(g.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
