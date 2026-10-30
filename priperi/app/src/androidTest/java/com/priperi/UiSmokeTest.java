package com.priperi;

import android.app.Activity;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.firebase.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class UiSmokeTest {
    private Context context;
    private interface LocalJob {Object run(Vault v)throws Exception;}
    private void sync(LocalJob job)throws Exception {CountDownLatch latch=new CountDownLatch(1);String[] failure={null};Repository.local(context,v->job.run(v),ok->latch.countDown(),message->{failure[0]=message;latch.countDown();});assertTrue(latch.await(20,TimeUnit.SECONDS));assertNull(failure[0]);}
    @Before public void setup() throws Exception {
        context=ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("notification_access",Context.MODE_PRIVATE).edit().putBoolean("asked",true).commit();
        assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));
        Repository.reset();sync(v->{if(v.account()!=null&&!"00000000-0000-4000-8000-000000000001".equals(v.account().optString("uid"))&&!v.account().optString("username").startsWith("qa_ui_"))throw new SecurityException("Use a disposable QA installation.");Repository.eraseLocal(v);return true;});
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit();
    }
    @After public void cleanup() throws Exception {sync(v->{if(v.account()!=null&&v.account().optString("username").startsWith("qa_ui_"))Session.resetAccount(v);Repository.eraseLocal(v);return true;});Repository.reset();}
    @Test public void mergedContinueScreenRendersInBothThemes() throws Exception {
        for(int mode:new int[]{0,1}) {
            setTheme(mode);
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                awaitText(scenario,"Continue");settle();scenario.onActivity(activity->{assertNotNull(find(activity.getWindow().getDecorView(),"Continue"));assertNull(find(activity.getWindow().getDecorView(),"Email"));assertNotNull(findHint(activity.getWindow().getDecorView(),"Username"));save(activity,"continue-"+mode);});
            }
        }
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit();
    }
    @Test public void returningPhoneShowsOnlyPasswordAfterUsernameChanges() throws Exception {
        String name="qa_ui_"+java.util.UUID.randomUUID().toString().substring(0,8);
        sync(v->{Session.continueAccount(v,name,"Ui-Test-Password-2026!");Repository.call(v,"renameAccount",new JSONObject().put("username",name+"_new"));Session.signOut(v);return true;});
        for(int mode:new int[]{0,1}) {
            setTheme(mode);
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
                awaitText(scenario,"Welcome back");settle();scenario.onActivity(a->{assertNull(findHint(a.getWindow().getDecorView(),"Username"));assertNotNull(findHint(a.getWindow().getDecorView(),"Password"));assertNotNull(find(a.getWindow().getDecorView(),"Forgot password or username?"));save(a,"signin-"+mode);});
            }
        }
    }
    @Test public void privateScreensCloseWhenNoSessionExists() throws Exception {
        for(Class<? extends Activity> activity:new Class[]{InboxActivity.class,SettingsActivity.class,ChatActivity.class,ContactCodeActivity.class,BackupActivity.class,GroupDetailsActivity.class}) {
            Intent intent=new Intent(context,activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("peer","00000000-0000-4000-8000-000000000002");
            try(ActivityScenario<?> scenario=ActivityScenario.launch(intent)) {
                for(int i=0;i<50&&scenario.getState()!=androidx.lifecycle.Lifecycle.State.DESTROYED;i++)SystemClock.sleep(100);
                assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.getState());
            }
        }
    }
    @Test public void rejectedSessionReturnsToSignInWithoutErasingTheAccount() throws Exception {
        sync(v->{Session.continueAccount(v,"qa_ui_"+java.util.UUID.randomUUID().toString().substring(0,8),"Ui-Test-Password-2026!");v.session(v.session().put("access_token","forged"));return true;});
        try(ActivityScenario<InboxActivity> scenario=ActivityScenario.launch(InboxActivity.class)) {
            for(int i=0;i<100&&scenario.getState()!=androidx.lifecycle.Lifecycle.State.DESTROYED;i++)SystemClock.sleep(100);
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.getState());
        }
        sync(v->{assertNull(v.session());assertNotNull(v.account());return true;});
    }
    @Test public void topSearchFindsNewPeopleWithoutOpeningADialog() throws Exception {
        String name="qa_ui_"+java.util.UUID.randomUUID().toString().substring(0,8);Vault peer=new Vault(context,"ui_search_peer_"+name);
        boolean[] created={false};
        try {
            sync(v->{Session.continueAccount(v,name,"Ui-Test-Password-2026!");Session.continueAccount(peer,"maya","Ui-Test-Password-2026!");created[0]=true;Session.restore(v);return true;});
            for(int mode:new int[]{0,1}) {
                setTheme(mode);
                try(ActivityScenario<InboxActivity> scenario=ActivityScenario.launch(InboxActivity.class)) {
                    awaitText(scenario,"New chat");scenario.onActivity(a->((EditText)findHint(a.getWindow().getDecorView(),"Search")).setText("maya"));
                    awaitText(scenario,"@maya");settle();scenario.onActivity(a->{assertNotNull(find(a.getWindow().getDecorView(),"People on Priperi"));save(a,"search-"+mode);});
                }
            }
        } finally {if(created[0])Session.resetAccount(peer);peer.destroy();sync(v->{Session.restore(v);return true;});}
    }
    @Test public void inboxChatAndSettingsRenderWithoutExposingOtherActivities() throws Exception {
        sync(alice->{try(Vault bob=new Vault(context,"ui_bob")) {
            alice.clearHistory(null);alice.account(profile("00000000-0000-4000-8000-000000000001","demo_alice",alice));TestIdentityTrust.pin(alice,profile("00000000-0000-4000-8000-000000000002","demo_bob",bob));
            JSONObject envelope=alice.crypto().encrypt("00000000-0000-4000-8000-000000000001",alice.identity(),alice.contact("00000000-0000-4000-8000-000000000002"),"Did you get home alright?");
            alice.store(new JSONObject().put("peer","00000000-0000-4000-8000-000000000002").put("body","Did you get home alright?").put("envelope",envelope).put("outgoing",true).put("state","delivered"));
            JSONObject reply=bob.crypto().encrypt("00000000-0000-4000-8000-000000000002",bob.identity(),profile("00000000-0000-4000-8000-000000000001","demo_alice",alice),"Just got in. Thanks for checking.");
            alice.store(new JSONObject().put("peer","00000000-0000-4000-8000-000000000002").put("body","Just got in. Thanks for checking.").put("envelope",reply).put("outgoing",false).put("state","received"));
            alice.session(new JSONObject().put("uid","00000000-0000-4000-8000-000000000001").put("access_token","test-only-token").put("refresh_token","test-refresh").put("expires_at",System.currentTimeMillis()/1000L+3600));Session.restore(alice);return true;
        }});
        for(int mode:new int[]{0,1}) {
            setTheme(mode);
            try(ActivityScenario<InboxActivity> scenario=ActivityScenario.launch(InboxActivity.class)) { awaitText(scenario,"@demo_bob");settle();scenario.onActivity(a->{View search=findHint(a.getWindow().getDecorView(),"Search");assertNotNull(search);assertTrue(search.getTop()<a.getWindow().getDecorView().getHeight()/2);save(a,"inbox-"+mode);((EditText)search).setText("home alright");});awaitText(scenario,"@demo_bob"); }
            Intent chat=new Intent(context,ChatActivity.class).putExtra("peer","00000000-0000-4000-8000-000000000002").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try(ActivityScenario<ChatActivity> scenario=ActivityScenario.launch(chat)) { awaitText(scenario,"Did you get home alright?"); settle(); scenario.onActivity(a->{assertTrue(find(a.getWindow().getDecorView(),"Did you get home alright?").getWidth()>0); assertEquals(0,a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON);save(a,"chat-"+mode);}); }
            try(ActivityScenario<SettingsActivity> scenario=ActivityScenario.launch(SettingsActivity.class)) { awaitText(scenario,"Appearance");settle();scenario.onActivity(a->{assertNotNull(find(a.getWindow().getDecorView(),"Security key"));assertTrue((a.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE)!=0);save(a,"settings-"+mode);}); }
        }
        assertFalse(context.getPackageManager().getActivityInfo(new ComponentName(context,SettingsActivity.class),0).exported);
        assertFalse(context.getPackageManager().getActivityInfo(new ComponentName(context,ChatActivity.class),0).exported);
        assertFalse(context.getPackageManager().getActivityInfo(new ComponentName(context,GroupDetailsActivity.class),0).exported);
        context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().clear().commit();
    }
    private JSONObject profile(String uid,String name,Vault v)throws Exception {JSONObject i=v.identity();return new JSONObject().put("uid",uid).put("username",name).put("hpke",i.getString("hpke")).put("signing",i.getString("signing")).put("fingerprint",i.getString("fingerprint"));}
    private void setTheme(int mode) {context.getSharedPreferences("appearance",Context.MODE_PRIVATE).edit().putInt("theme",mode).commit();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->PriperiApp.applyTheme(mode));}
    private static View findHint(View view,String text){if(view instanceof TextView&&((TextView)view).getHint()!=null&&text.contentEquals(((TextView)view).getHint()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findHint(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static View find(View view,String text) {if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private <T extends Activity> void awaitText(ActivityScenario<T> scenario,String text) {for(int i=0;i<100;i++){boolean[] found={false};scenario.onActivity(a->found[0]=find(a.getWindow().getDecorView(),text)!=null);if(found[0])return;SystemClock.sleep(100);}fail("Screen did not load: "+text);}
    private static void settle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); SystemClock.sleep(250); }
    private static void save(Activity activity,String name) {try{View root=activity.getWindow().getDecorView();assertTrue(root.getWidth()>0);Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));File dir=new File(activity.getExternalFilesDir(null),"ui-previews");dir.mkdirs();try(FileOutputStream stream=new FileOutputStream(new File(dir,name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}bitmap.recycle();}catch(IOException e){throw new AssertionError(e);}}
}
