package com.priperi;
import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import androidx.test.core.app.*;import androidx.test.ext.junit.runners.AndroidJUnit4;import org.json.*;import org.junit.*;import org.junit.runner.RunWith;
import java.util.*;import java.util.concurrent.*;import static org.junit.Assert.*;

/** Disposable accounts only, including the old token retained solely for test cleanup. */
@RunWith(AndroidJUnit4.class)
public class ReinstallReplacementTest {
 private Context context;private String oldProof,oldToken,oldUid,oldFingerprint;
 private <T>T local(Repository.Job<T> job)throws Exception{CompletableFuture<T> f=new CompletableFuture<>();Repository.local(context,job,f::complete,e->f.completeExceptionally(new AssertionError(e)));return f.get(45,TimeUnit.SECONDS);}
 @Before public void setup()throws Exception{context=ApplicationProvider.getApplicationContext();assertTrue(BuildConfig.ALLOW_TEST_DEVICES);assertTrue(BuildConfig.BACKEND_URL.startsWith("http://10.0.2.2:"));context.getSharedPreferences("notification_access",0).edit().putBoolean("asked",true).commit();Repository.reset();local(v->{assertNull(v.account());Session.continueAccount(v,"qa_replace_"+UUID.randomUUID().toString().substring(0,8),"Previous-Password-2026!");oldProof=v.identity().getString("deviceSecret");oldToken=v.session().getString("access_token");oldUid=Session.uid();oldFingerprint=v.account().getString("fingerprint");Repository.eraseLocal(v);return true;});}
 @After public void cleanup()throws Exception{
  androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{for(android.app.Activity activity:new ArrayList<>(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)))if(context.getPackageName().equals(activity.getPackageName()))activity.finish();});
  androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  local(v->{if(v.account()!=null)Session.resetAccount(v);Repository.eraseLocal(v);return true;});
  try{Session.request(Session.endpoint(),new JSONObject().put("operation","deleteAccount").put("deviceSecret",oldProof),oldToken,true);}catch(Session.BackendException e){assertEquals(403,e.status);Session.request("http://10.0.2.2:5001/qa/account-expire",new JSONObject().put("deviceSecret",oldProof).put("sweep",true),oldToken,false);}
  Repository.reset();oldToken=null;oldProof=null;
 }
 @Test public void bothWarningsCancelWithoutChangingTheOldAccount()throws Exception{
  try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   await(screen,"Create new account");tap(screen,"Create new account");warningHasSevenDays();AccountControlsFlowTest.click("Cancel");await(screen,"Create new account");
   tap(screen,"Create new account");AccountControlsFlowTest.click("Continue");await(screen,"Create a new account");fill(screen,"Previous-Password-2026!");tap(screen,"Create new account");warningHasSevenDays();AccountControlsFlowTest.click("Cancel");
   screen.onActivity(a->assertNotNull(NavigationRecoveryTest.find(a.getWindow().getDecorView(),"Create a new account")));assertNull(Session.uid());assertOldAccessible();
  }
 }
 @Test public void wrongPreviousPasswordNeverCreatesOrSignsIntoAnAccount()throws Exception{
  try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   await(screen,"Create new account");tap(screen,"Create new account");AccountControlsFlowTest.click("Continue");await(screen,"Create a new account");fill(screen,"Incorrect-Password-2026!");tap(screen,"Create new account");AccountControlsFlowTest.click("Create new account");await(screen,"The previous account password does not match. Nothing was changed.");assertNull(Session.uid());assertTrue(local(v->v.account()==null));assertOldAccessible();
  }
 }
 @Test public void confirmedSignupHasNewIdentityAndNoOldHistory()throws Exception{
  try(ActivityScenario<MainActivity> screen=ActivityScenario.launch(MainActivity.class)){
   await(screen,"Create new account");tap(screen,"Create new account");AccountControlsFlowTest.click("Continue");await(screen,"Create a new account");fill(screen,"Previous-Password-2026!");tap(screen,"Create new account");AccountControlsFlowTest.click("Create new account");
   long end=SystemClock.elapsedRealtime()+15000;while(Session.uid()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(30);assertNotNull(Session.uid());assertNotEquals(oldUid,Session.uid());
   local(v->{assertNotEquals(oldFingerprint,v.account().getString("fingerprint"));assertTrue(v.messages(null).isEmpty());assertEquals(0,Repository.call(v,"getAccountPolicy",new JSONObject()).getInt("days"));return true;});
   try{assertOldAccessible();fail("Old session retained access");}catch(Session.BackendException expected){assertEquals(403,expected.status);}
  }
 }
 private static void warningHasSevenDays(){long end=SystemClock.elapsedRealtime()+5000;while(SystemClock.elapsedRealtime()<end){android.view.accessibility.AccessibilityNodeInfo root=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();if(root!=null&&!root.findAccessibilityNodeInfosByText("7 days").isEmpty())return;SystemClock.sleep(50);}fail("Deletion warning was not visible");}
 private void assertOldAccessible()throws Exception{assertEquals(oldUid,Session.request(Session.endpoint(),new JSONObject().put("operation","getAccount").put("deviceSecret",oldProof),oldToken,true).getJSONObject("data").getString("uid"));}
 private static void fill(ActivityScenario<MainActivity> s,String previous){s.onActivity(a->{View root=a.getWindow().getDecorView();field(root,"Old password").setText(previous);field(root,"New username").setText("qa_fresh_"+UUID.randomUUID().toString().substring(0,8));field(root,"New password").setText("Fresh-Password-2026!");});}
 private static EditText field(View v,String hint){if(v instanceof EditText&&hint.contentEquals(((EditText)v).getHint()))return (EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=field(((ViewGroup)v).getChildAt(i),hint);if(e!=null)return e;}return null;}
 private static void tap(ActivityScenario<MainActivity> s,String label){s.onActivity(a->NavigationRecoveryTest.find(a.getWindow().getDecorView(),label).performClick());}
 private static void await(ActivityScenario<MainActivity> s,String text){long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){boolean[] seen={false};s.onActivity(a->{View v=NavigationRecoveryTest.find(a.getWindow().getDecorView(),text);seen[0]=v!=null&&v.isShown();});if(seen[0])return;SystemClock.sleep(30);}fail("Missing control: "+text);}
}
